package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.Consumer;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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

    private static final long[] PERIOD_MILLIS =
            {CodexUsageStats.DAY_MILLIS, 7 * CodexUsageStats.DAY_MILLIS, 30 * CodexUsageStats.DAY_MILLIS};
    private static final String[] PERIOD_LABELS = {"24h", "7d", "30d"};
    /** Widest strings the value columns can show, so both tabs share one column layout. */
    private static final String TOKENS_TEMPLATE = "999.9M";
    private static final String SHARE_TEMPLATE = "100.0%";
    /** Period label plus Uncached, Total and Cached. */
    private static final int COLUMNS = 4;
    /** Unscaled vertical gap between table rows. */
    private static final int ROW_GAP = 4;
    /** The theme's own muted "success" green, so it fits both Light and Darcula. */
    private static final Color ACTIVE_COLOR =
            JBColor.namedColor("Label.successForeground", new JBColor(0x368746, 0x5FAD65));

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
        // Both tabs are built from one snapshot up front: switching only flips
        // the visible tab, and the tabbed pane is sized for the larger of the
        // two, so the popup never recomputes or resizes.
        List<CodexUsageStats.Sample> samples = CodexUsageTracker.getInstance().snapshot().samples();
        long now = System.currentTimeMillis();
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("This project", scopePanel(samples, now, CodexUsageStats.projectFilter(project.getBasePath())));
        tabs.addTab("All agents", scopePanel(samples, now, _ -> true));
        tabs.setSelectedIndex(0);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
        content.add(tabs, BorderLayout.CENTER);
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

    /**
     * One tab: activity status, then TOKEN USAGE (uncached / total / cached
     * share) and AVERAGE SPEED per period. Everything sits on one grid, so the
     * uncached token counts and the speeds line up in the same column.
     */
    private static JComponent scopePanel(List<CodexUsageStats.Sample> samples, long now, Predicate<String> filter) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(10, 4, 2, 4));

        CodexUsageStats.Usage current = CodexUsageStats.current(samples, now, filter);
        CodexUsageStats.Usage[] averages = CodexUsageStats.averages(samples, now, PERIOD_MILLIS, filter);

        int row = 0;
        add(panel, statusRow(current.agents()), 0, row++, COLUMNS, 0);

        row = sectionHeader(panel, "TOKEN USAGE", row);
        add(panel, caption("Uncached"), 1, row, 1, 0);
        add(panel, caption("Total"), 2, row, 1, 0);
        add(panel, caption("Cached"), 3, row++, 1, 0);
        for (int i = 0; i < PERIOD_MILLIS.length; i++) {
            CodexUsageStats.Usage usage = averages[i];
            boolean empty = usage.totalTokens() <= 0;
            add(panel, new JBLabel(PERIOD_LABELS[i]), 0, row, 1, ROW_GAP);
            add(panel, number(tokens(empty, usage.nonCachedTokens()), TOKENS_TEMPLATE), 1, row, 1, ROW_GAP);
            add(panel, number(tokens(empty, usage.totalTokens()), TOKENS_TEMPLATE), 2, row, 1, ROW_GAP);
            add(panel, number(CodexUsageStats.formatCachedShare(usage), SHARE_TEMPLATE), 3, row++, 1, ROW_GAP);
        }

        row = sectionHeader(panel, "AVERAGE SPEED", row);
        for (int i = 0; i < PERIOD_MILLIS.length; i++) {
            CodexUsageStats.Usage usage = averages[i];
            boolean empty = usage.agents() == 0;
            add(panel, new JBLabel(PERIOD_LABELS[i]), 0, row, 1, ROW_GAP);
            add(panel, number(tokens(empty, usage.nonCachedTokensPerMinute()), TOKENS_TEMPLATE), 1, row, 1, ROW_GAP);
            if (!empty) {
                panel.add(caption("tok/min"),
                        cell(2, row, 2, JBUI.insets(ROW_GAP, 6, 0, 0), GridBagConstraints.WEST));
            }
            row++;
        }
        return panel;
    }

    /** A token count or rate, or {@code —} when the period has no data. */
    private static String tokens(boolean empty, double value) {
        return empty ? "—" : CodexUsageStats.formatTokens(value);
    }

    private static JComponent statusRow(int agents) {
        JBLabel dot = new JBLabel("●");
        dot.setForeground(agents > 0 ? ACTIVE_COLOR : JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        Box left = Box.createHorizontalBox();
        left.add(dot);
        left.add(Box.createHorizontalStrut(JBUI.scale(6)));
        left.add(new JBLabel(agents > 0 ? "Active" : "Idle"));

        Box right = Box.createHorizontalBox();
        right.add(caption("Active agents"));
        right.add(Box.createHorizontalStrut(JBUI.scale(6)));
        right.add(new JBLabel(String.valueOf(agents)));

        JPanel status = new JPanel(new BorderLayout());
        status.setOpaque(false);
        status.add(left, BorderLayout.WEST);
        status.add(right, BorderLayout.EAST);
        return status;
    }

    /** Section title with a separator under it; returns the next free row. */
    private static int sectionHeader(JPanel panel, String title, int row) {
        JBLabel label = new JBLabel(title);
        label.setFont(JBFont.small().asBold());
        label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        add(panel, label, 0, row++, COLUMNS, 16);
        panel.add(new JSeparator(), cell(0, row++, COLUMNS, JBUI.insets(4, 0, 2, 0), GridBagConstraints.WEST));
        return row;
    }

    private static JBLabel caption(String text) {
        JBLabel label = new JBLabel(text, SwingConstants.RIGHT);
        label.setFont(JBFont.small());
        label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        return label;
    }

    /**
     * A right-aligned value at least as wide as {@code template} (the widest
     * value its column can show). Both tabs then get the same column widths,
     * so nothing shifts sideways when switching between them.
     */
    private static JBLabel number(String text, String template) {
        JBLabel label = new JBLabel(text, SwingConstants.RIGHT);
        Dimension size = label.getPreferredSize();
        int width = Math.max(size.width, label.getFontMetrics(label.getFont()).stringWidth(template));
        label.setPreferredSize(new Dimension(width, size.height));
        return label;
    }

    /**
     * Column 0 (labels) hugs the left edge; value columns are right-aligned
     * with a gap before them. {@code top} is in unscaled pixels, like every
     * other JBUI size.
     */
    private static void add(JPanel panel, JComponent component, int column, int row, int width, int top) {
        panel.add(component, cell(column, row, width, JBUI.insets(top, column == 0 ? 0 : 18, 0, 0),
                column == 0 ? GridBagConstraints.WEST : GridBagConstraints.EAST));
    }

    /** A grid cell; a component spanning all columns stretches across them. */
    private static GridBagConstraints cell(int column, int row, int width, Insets insets, int anchor) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = width;
        constraints.insets = insets;
        constraints.anchor = anchor;
        if (width == COLUMNS) {
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.weightx = 1;
        }
        return constraints;
    }
}
