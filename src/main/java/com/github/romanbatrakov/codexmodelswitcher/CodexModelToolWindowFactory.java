package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the "Model Switcher" tool window: the New chat / Choose chat buttons,
 * the four model presets, any configured launch profiles, and Settings pinned
 * to the bottom.
 */
public final class CodexModelToolWindowFactory implements ToolWindowFactory {
    private static final Color CHAT_ACCENT = new JBColor(new Color(0x356CCB), new Color(0x5B8DEF));
    private static final Color PICKER_ACCENT = new JBColor(new Color(0x7652B8), new Color(0xA77BDF));
    private static final Color TERRA_ACCENT = new JBColor(new Color(0x4A9B5F), new Color(0x72CF78));
    private static final Color TERRA_XHIGH_ACCENT = new JBColor(new Color(0x8050B5), new Color(0xB184E8));
    private static final Color SOL_ACCENT = new JBColor(new Color(0xC47B24), new Color(0xF2AE4B));
    private static final Color LUNA_ACCENT = new JBColor(new Color(0x3978C5), new Color(0x70A8F0));

    /** Installs a single {@link ToolWindowPanel} as this tool window's content. */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ToolWindowPanel panel = new ToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(content, panel);
    }

    /** A small, muted section title such as MODELS; the top gap separates it from the previous section. */
    private static void addSection(JPanel sections, String title) {
        JBLabel label = new JBLabel(title);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(JBUI.Borders.empty(sections.getComponentCount() > 0 ? 14 : 0, 8, 4, 0));
        label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        label.setFont(JBFont.small().asBold());
        sections.add(label);
    }

    private static void addCard(JPanel sections, CardButton card) {
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        sections.add(card);
        sections.add(Box.createVerticalStrut(JBUI.scale(2)));
    }

    private static Color mix(Color first, Color second, float secondWeight) {
        float firstWeight = 1f - secondWeight;
        return new Color(Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
            Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
            Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight));
    }

    /** The actual tool window content: sections of {@link CardButton}s, rebuilt whenever settings change. */
    private static final class ToolWindowPanel extends JBPanel<ToolWindowPanel> implements Disposable {
        private final Project project;
        private final Timer statusTimer;
        private final Map<ModelPreset.PresetId, CardButton> modelCards = new EnumMap<>(ModelPreset.PresetId.class);
        private ModelPreset.PresetId lastSelectedPreset;

        private ToolWindowPanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            setBorder(JBUI.Borders.empty(8));
            statusTimer = new Timer(1000, ignored -> refreshState());
            statusTimer.setInitialDelay(0);
            rebuild();
            statusTimer.start();
        }

        private void rebuild() {
            removeAll();
            modelCards.clear();
            lastSelectedPreset = null;

            JPanel sections = new JPanel();
            sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
            sections.setOpaque(false);

            sections.add(actionsRow());

            addSection(sections, "MODELS");
            addModelCard(sections, ModelPreset.PresetId.TERRA_MEDIUM, "▲", TERRA_ACCENT);
            addModelCard(sections, ModelPreset.PresetId.TERRA_XHIGH, "●", TERRA_XHIGH_ACCENT);
            addModelCard(sections, ModelPreset.PresetId.SOL_XHIGH, "◆", SOL_ACCENT);
            addModelCard(sections, ModelPreset.PresetId.LUNA_MAX, "■", LUNA_ACCENT);

            List<LaunchProfile> profiles = CodexModelSwitcherSettings.getInstance().launchProfiles();
            if (!profiles.isEmpty()) {
                addSection(sections, "PROFILES");
                for (LaunchProfile profile : profiles) {
                    addCard(sections, new CardButton(CardButton.Kind.ROW, profile.name, profile.name, "»",
                        PICKER_ACCENT, () -> CodexWorkflow.launchProfile(project, profile), null));
                }
            }

            JBScrollPane scrollPane = new JBScrollPane(sections);
            scrollPane.setBorder(null);
            scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            scrollPane.setOpaque(false);
            scrollPane.getViewport().setOpaque(false);
            add(scrollPane, BorderLayout.CENTER);
            add(settingsArea(), BorderLayout.SOUTH);
            refreshState();
            revalidate();
            repaint();
        }

        /** New chat and Choose chat side by side, sharing the width equally. */
        private JComponent actionsRow() {
            JPanel row = new JPanel(new GridLayout(1, 2, JBUI.scale(6), 0)) {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(new CardButton(CardButton.Kind.ACTION, "New chat", "Start a new chat with the current preset",
                "+", CHAT_ACCENT, () -> CodexWorkflow.startNewChat(project), null));
            row.add(new CardButton(CardButton.Kind.ACTION, "Choose chat", "Pick a saved chat and resume it",
                "□", PICKER_ACCENT, () -> CodexWorkflow.chooseAndOpenChat(project), null));
            return row;
        }

        /** Settings below a thin line, pinned to the bottom of the tool window. */
        private JComponent settingsArea() {
            JPanel area = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            area.setOpaque(false);
            area.setBorder(JBUI.Borders.emptyTop(8));
            area.add(new JSeparator(), BorderLayout.NORTH);
            area.add(new CardButton(CardButton.Kind.ROW, "Settings", "Settings — presets and shortcuts", "⚙",
                PICKER_ACCENT, this::openSettings, null), BorderLayout.CENTER);
            return area;
        }

        private void addModelCard(JPanel sections, ModelPreset.PresetId presetId, String glyph, Color accent) {
            ModelPreset preset = ModelPreset.forId(presetId);
            String title = preset.model() + " · " + preset.effort();
            CardButton card = new CardButton(CardButton.Kind.MODEL, title, title + " — " + preset.label(), glyph,
                accent, () -> CodexWorkflow.switchModel(project, ModelPreset.forId(presetId)),
                CodexModelSwitcherSettings.getInstance().shortcut(presetId));
            modelCards.put(presetId, card);
            addCard(sections, card);
        }

        private void openSettings() {
            if (new CodexSettingsDialog(project).showAndGet()) {
                rebuild();
            }
        }

        private void refreshState() {
            ModelPreset.PresetId currentPreset = CodexWorkflow.lastPresetId();
            if (currentPreset == lastSelectedPreset) {
                return;
            }
            lastSelectedPreset = currentPreset;
            for (Map.Entry<ModelPreset.PresetId, CardButton> entry : modelCards.entrySet()) {
                // A model action performs its own connection/readiness check and
                // can start the local server when it is offline. Keeping the
                // card enabled makes the action recoverable after an IDE/plugin
                // restart instead of turning a transient status into a dead UI.
                entry.getValue().setEnabled(true);
                entry.getValue().setHighlighted(entry.getKey() == currentPreset);
            }
        }

        /** Stops the once-a-second preset-highlight refresh timer. */
        @Override
        public void dispose() {
            statusTimer.stop();
        }
    }

    /**
     * A flat row with a colored icon badge, used for every item in the tool
     * window. Three states read differently: hover is the theme's neutral
     * hover fill, while the selected model gets a light tint of its own color
     * plus a bar on the left, and keeps both while hovered.
     *
     * <p>Rows are reachable from the keyboard (Tab, ↑/↓, Enter or Space), but a
     * mouse click doesn't take focus, so clicking a model keeps typing in the
     * terminal uninterrupted.
     */
    private static final class CardButton extends JButton {
        /** Unscaled heights; ACTION rows are outlined so they read as buttons. */
        private enum Kind {
            ACTION(30, true), MODEL(32, false), ROW(30, false);

            private final int height;
            private final boolean outlined;

            Kind(int height, boolean outlined) {
                this.height = height;
                this.outlined = outlined;
            }
        }

        private final Kind kind;
        private final String title;
        private final String glyph;
        private final Color accent;
        private final String shortcut;
        private boolean selected;

        private CardButton(Kind kind, String title, String tooltip, String glyph, Color accent, Runnable action,
                           String shortcut) {
            super(title);
            this.kind = kind;
            this.title = title;
            this.glyph = glyph;
            this.accent = accent;
            this.shortcut = shortcut;
            setToolTipText(tooltip);
            setFocusable(true);
            setRequestFocusEnabled(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            addActionListener(ignored -> action.run());
            installKeyboardNavigation();
        }

        /** Enter activates (Space already does); ↑/↓ move between rows. Only while this row has focus. */
        private void installKeyboardNavigation() {
            bind(KeyEvent.VK_ENTER, "codexActivate", this::doClick);
            bind(KeyEvent.VK_UP, "codexPrevious",
                () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().focusPreviousComponent(this));
            bind(KeyEvent.VK_DOWN, "codexNext",
                () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().focusNextComponent(this));
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    scrollRectToVisible(new Rectangle(0, 0, getWidth(), getHeight()));
                }
            });
        }

        private void bind(int keyCode, String name, Runnable runnable) {
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
            getActionMap().put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    runnable.run();
                }
            });
        }

        private void setHighlighted(boolean selected) {
            this.selected = selected;
            repaint();
        }

        // Sizes are computed on every call, so they follow IDE zoom changes.
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, JBUI.scale(kind.height));
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, JBUI.scale(kind.height));
        }

        private Color background() {
            ButtonModel model = getModel();
            if (selected) {
                float weight = model.isPressed() ? 0.30f : model.isRollover() ? 0.24f : 0.16f;
                return mix(UIUtil.getPanelBackground(), accent, weight);
            }
            if (model.isPressed()) {
                return JBUI.CurrentTheme.ActionButton.pressedBackground();
            }
            return model.isRollover() ? JBUI.CurrentTheme.ActionButton.hoverBackground() : null;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                UISettings.setupAntialiasing(g);
                int width = getWidth();
                int height = getHeight();
                int arc = JBUI.scale(8);

                Color background = background();
                if (background != null) {
                    g.setColor(background);
                    g.fillRoundRect(0, 0, width, height, arc, arc);
                }
                if (kind.outlined || isFocusOwner()) {
                    g.setColor(isFocusOwner() ? JBUI.CurrentTheme.Focus.focusColor() : JBColor.border());
                    g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
                }
                if (selected) {
                    int bar = JBUI.scale(3);
                    int inset = JBUI.scale(7);
                    g.setColor(accent);
                    g.fillRoundRect(0, inset, bar, height - 2 * inset, bar, bar);
                }

                int padding = JBUI.scale(8);
                int badge = JBUI.scale(18);
                int badgeY = (height - badge) / 2;
                g.setColor(isEnabled() ? accent : mix(accent, UIUtil.getPanelBackground(), 0.5f));
                g.fillRoundRect(padding, badgeY, badge, badge, JBUI.scale(6), JBUI.scale(6));
                g.setColor(Color.WHITE);
                g.setFont(JBFont.small().asBold());
                FontMetrics glyphMetrics = g.getFontMetrics();
                g.drawString(glyph, padding + (badge - glyphMetrics.stringWidth(glyph)) / 2,
                    badgeY + (badge - glyphMetrics.getHeight()) / 2 + glyphMetrics.getAscent());

                int textX = padding + badge + JBUI.scale(10);
                int right = width - JBUI.scale(10);
                if (shortcut != null) {
                    String keys = "⌃" + shortcut;
                    g.setColor(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
                    g.setFont(JBFont.small());
                    FontMetrics keyMetrics = g.getFontMetrics();
                    right -= keyMetrics.stringWidth(keys);
                    g.drawString(keys, right, (height - keyMetrics.getHeight()) / 2 + keyMetrics.getAscent());
                    right -= JBUI.scale(8);
                }

                g.setColor(isEnabled() ? UIUtil.getLabelForeground() : UIUtil.getLabelDisabledForeground());
                g.setFont(getFont());
                FontMetrics textMetrics = g.getFontMetrics();
                g.drawString(fit(title, textMetrics, right - textX), textX,
                    (height - textMetrics.getHeight()) / 2 + textMetrics.getAscent());
            } finally {
                g.dispose();
            }
        }

        /** {@code text}, cut with an ellipsis to fit {@code width}; the tooltip always has the full text. */
        private static String fit(String text, FontMetrics metrics, int width) {
            if (metrics.stringWidth(text) <= width) {
                return text;
            }
            int low = 0;
            int high = text.length();
            while (low < high) {
                int middle = (low + high + 1) / 2;
                if (metrics.stringWidth(text.substring(0, middle) + "…") <= width) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return text.substring(0, low) + "…";
        }
    }
}
