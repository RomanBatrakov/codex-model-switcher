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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Predicate;

/**
 * Status bar text such as {@code ⛽ 4.2k tok/min · 🤖 2 · 5h 9% · wk 36%}:
 * the live per-agent token rate across all Codex agents (without cached
 * input, which Codex re-sends on every step and which otherwise makes up
 * almost the whole number), how many agents
 * are active, and the plan's rate limits. Clicking it opens a breakdown of
 * this project vs. all agents with 24h/7d/30d averages.
 */
final class CodexUsageWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    static final String ID = "CodexModelSwitcher.Usage";
    private static final DateTimeFormatter RESET_TIME = DateTimeFormatter.ofPattern("EEE HH:mm");

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
        long now = System.currentTimeMillis();
        CodexUsageStats.Usage usage = CodexUsageStats.current(snapshot.samples(), now, _ -> true);
        StringBuilder text = new StringBuilder("⛽ ")
                .append(CodexUsageStats.formatTokens(usage.nonCachedTokensPerMinute()))
                .append(" tok/min · 🤖 ")
                .append(usage.agents());
        CodexUsageParser.RateLimits limits = snapshot.rateLimits();
        if (limits != null) {
            appendLimit(text, limits.primary(), now);
            appendLimit(text, limits.secondary(), now);
        }
        return text.toString();
    }

    private static void appendLimit(StringBuilder text, CodexUsageParser.RateLimit limit, long now) {
        if (CodexUsageStats.isCurrent(limit, now)) {
            text.append(" · ").append(CodexUsageStats.windowLabel(limit.windowMinutes()))
                .append(' ').append(Math.round(limit.usedPercent())).append('%');
        }
    }

    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    @Override
    public String getTooltipText() {
        return "Codex tokens per minute per agent over the last 5 minutes, excluding cached input "
                + "(usually ~98% of the total). Click for details";
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
        CodexUsageTracker.Snapshot snapshot = CodexUsageTracker.getInstance().snapshot();
        List<CodexUsageStats.Sample> samples = snapshot.samples();
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
        html.append("</table>");
        CodexUsageParser.RateLimits limits = snapshot.rateLimits();
        if (limits != null) {
            html.append("<p>Limits: ");
            appendLimitDetails(html, limits.primary(), now);
            appendLimitDetails(html, limits.secondary(), now);
            html.append("</p>");
        }
        html.append("<p><small>Rates are per agent while it was working, not summed across agents.<br>")
            .append("Main numbers exclude cached input, which Codex re-sends on every step;<br>")
            .append("\"with cache\" is the full count including it.</small></p>")
            .append("</html>");
        return html.toString();
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

    private static void appendLimitDetails(StringBuilder html, CodexUsageParser.RateLimit limit, long now) {
        if (!CodexUsageStats.isCurrent(limit, now)) {
            return;
        }
        html.append(CodexUsageStats.windowLabel(limit.windowMinutes())).append(' ')
            .append(Math.round(limit.usedPercent())).append('%');
        if (limit.resetsAtEpochSeconds() > 0) {
            html.append(" (resets ").append(RESET_TIME.format(
                    Instant.ofEpochSecond(limit.resetsAtEpochSeconds()).atZone(ZoneId.systemDefault()))).append(')');
        }
        html.append("&nbsp;&nbsp;");
    }
}
