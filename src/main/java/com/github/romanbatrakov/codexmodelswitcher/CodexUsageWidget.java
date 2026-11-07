package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.Consumer;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Predicate;

/**
 * Status bar text such as {@code ⛽ 7.0k tok/min · 🤖 2 · 24h 8.4k · 7d 5.2k}:
 * the live per-agent token rate across all Codex agents, how many agents are
 * active, and the per-agent averages over the last day and week. Every rate
 * excludes cached input, which Codex re-sends on every step and which
 * otherwise makes up almost the whole number. Clicking opens a breakdown of
 * this project vs. all agents.
 */
final class CodexUsageWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    static final String ID = "CodexModelSwitcher.Usage";

    private final Project project;
    private final Runnable refreshListener;
    private volatile StatusBar statusBar;

    CodexUsageWidget(Project project) {
        this.project = project;
        this.refreshListener = () -> ApplicationManager.getApplication().invokeLater(() -> {
            StatusBar bar = statusBar;
            if (bar != null) {
                bar.updateWidget(ID);
            }
        }, project.getDisposed());
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        CodexUsageTracker.getInstance().addListener(refreshListener);
    }

    @Override
    public void dispose() {
        CodexUsageTracker.getInstance().removeListener(refreshListener);
        statusBar = null;
    }

    @Override
    public @NotNull String getText() {
        CodexUsageTracker.Snapshot snapshot = CodexUsageTracker.getInstance().snapshot();
        if (!snapshot.hasSessions()) {
            return "⛽ Codex: no data";
        }
        List<CodexUsageStats.Sample> samples = snapshot.samples();
        long now = System.currentTimeMillis();
        CodexUsageStats.Usage current = CodexUsageStats.current(samples, now, _ -> true);
        CodexUsageStats.Usage day = CodexUsageStats.average(samples, now, CodexUsageStats.DAY_MILLIS, _ -> true);
        CodexUsageStats.Usage week = CodexUsageStats.average(samples, now, 7 * CodexUsageStats.DAY_MILLIS, _ -> true);
        return "⛽ " + CodexUsageStats.formatTokens(current.nonCachedTokensPerMinute()) + " tok/min"
                + " · 🤖 " + current.agents()
                + " · 24h " + CodexUsageStats.formatTokens(day.nonCachedTokensPerMinute())
                + " · 7d " + CodexUsageStats.formatTokens(week.nonCachedTokensPerMinute());
    }

    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    @Override
    public String getTooltipText() {
        return "Codex tokens per minute per agent: now, and 24-hour and 7-day averages. Click for details";
    }

    @Override
    public Consumer<MouseEvent> getClickConsumer() {
        return this::showDetails;
    }

    private void showDetails(MouseEvent event) {
        JBLabel content = new JBLabel(detailsHtml());
        content.setBorder(JBUI.Borders.empty(8, 12));
        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setTitle("Codex Usage")
                .setRequestFocus(false)
                .createPopup();
        // The status bar sits at the bottom of the window: open the popup above the click.
        Component source = event.getComponent();
        int height = popup.getContent().getPreferredSize().height;
        popup.show(new RelativePoint(source, new Point(0, -height - JBUI.scale(4))));
    }

    private String detailsHtml() {
        List<CodexUsageStats.Sample> samples = CodexUsageTracker.getInstance().snapshot().samples();
        long now = System.currentTimeMillis();
        String projectPath = project.getBasePath();
        Predicate<String> thisProject = cwd -> CodexUsageStats.belongsTo(cwd, projectPath);
        Predicate<String> everything = _ -> true;

        StringBuilder html = new StringBuilder("<html><table cellpadding='2'>")
                .append("<tr><td></td><td><b>This project</b></td><td><b>All agents</b></td></tr>");
        CodexUsageStats.Usage projectNow = CodexUsageStats.current(samples, now, thisProject);
        CodexUsageStats.Usage allNow = CodexUsageStats.current(samples, now, everything);
        row(html, "Now (5 min)", rate(projectNow), rate(allNow));
        row(html, "Active agents", String.valueOf(projectNow.agents()), String.valueOf(allNow.agents()));
        for (int days : new int[]{1, 7, 30}) {
            long period = days * CodexUsageStats.DAY_MILLIS;
            CodexUsageStats.Usage projectAverage = CodexUsageStats.average(samples, now, period, thisProject);
            CodexUsageStats.Usage allAverage = CodexUsageStats.average(samples, now, period, everything);
            String label = days == 1 ? "24 hours" : days + " days";
            row(html, "Avg, " + label, rate(projectAverage), rate(allAverage));
            row(html, "Total, " + label, total(projectAverage), total(allAverage));
        }
        return html.append("</table></html>").toString();
    }

    private static String rate(CodexUsageStats.Usage usage) {
        if (usage.agents() == 0) {
            return "—";
        }
        return CodexUsageStats.formatTokens(usage.nonCachedTokensPerMinute()) + " tok/min<br><small>"
                + CodexUsageStats.formatTokens(usage.tokensPerMinute()) + " with cache</small>";
    }

    private static String total(CodexUsageStats.Usage usage) {
        if (usage.totalTokens() == 0) {
            return "—";
        }
        return CodexUsageStats.formatTokens(usage.nonCachedTokens()) + "<br><small>"
                + CodexUsageStats.formatTokens(usage.totalTokens()) + " with cache</small>";
    }

    private static void row(StringBuilder html, String label, String project, String all) {
        html.append("<tr><td>").append(label).append("</td><td>").append(project)
            .append("</td><td>").append(all).append("</td></tr>");
    }
}
