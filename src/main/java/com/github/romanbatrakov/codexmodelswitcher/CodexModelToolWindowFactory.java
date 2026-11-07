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
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
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
        ToolWindowPanel panel = new ToolWindowPanel(project, toolWindow);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        // Activating the tool window focuses the panel itself, never one of its rows.
        content.setPreferredFocusableComponent(panel);
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

    private static Color mix(Color first, Color second, float secondWeight) {
        float firstWeight = 1f - secondWeight;
        return new Color(Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
            Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
            Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight));
    }

    /**
     * The actual tool window content: sections of {@link CardButton}s, rebuilt
     * whenever settings change and whenever the theme or IDE zoom changes
     * ({@link #updateUI()}), so every size and font is always current.
     *
     * <p>Keyboard navigation lives here rather than on the rows. The rows can't
     * take focus, so clicking one never moves focus to another; when the panel
     * itself is focused, ↑/↓ (and ←/→ between the two chat buttons) move a
     * cursor that stops at the ends, and Enter or Space activates it. Focus
     * from activating the tool window shows no cursor, so a stray Enter does
     * nothing until an arrow key or Tab picks a row.
     */
    private static final class ToolWindowPanel extends JBPanel<ToolWindowPanel> implements Disposable {
        /** New chat and Choose chat come first in {@link #rows} and share one line. */
        private static final int ACTION_COUNT = 2;

        private final Project project;
        private final ToolWindow toolWindow;
        private final Timer statusTimer;
        private final Map<ModelPreset.PresetId, CardButton> modelCards = new EnumMap<>(ModelPreset.PresetId.class);
        /** Every row in visual order, for keyboard navigation. */
        private final List<CardButton> rows = new ArrayList<>();
        /** How soon after a click on a row a focus gain counts as caused by that click. */
        private static final long CLICK_FOCUS_NANOS = 1_000_000_000L;

        private ModelPreset.PresetId lastSelectedPreset;
        private int cursor = -1;
        private long lastRowPressNanos = System.nanoTime() - 2 * CLICK_FOCUS_NANOS;

        private ToolWindowPanel(Project project, ToolWindow toolWindow) {
            super(new BorderLayout());
            this.project = project;
            this.toolWindow = toolWindow;
            setBorder(JBUI.Borders.empty(8));
            setFocusable(true);
            installKeyboardNavigation();
            statusTimer = new Timer(1000, ignored -> refreshState());
            statusTimer.setInitialDelay(0);
            rebuild();
            statusTimer.start();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            // Also called by the superclass constructor, before any field is set.
            if (project != null) {
                rebuild();
            }
        }

        private void rebuild() {
            removeAll();
            modelCards.clear();
            rows.clear();
            cursor = -1;
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
            scrollPane.setFocusable(false);
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

        private CardButton track(CardButton card) {
            rows.add(card);
            card.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    lastRowPressNanos = System.nanoTime();
                }
            });
            return card;
        }

        private void addCard(JPanel sections, CardButton card) {
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            sections.add(track(card));
            sections.add(Box.createVerticalStrut(JBUI.scale(2)));
        }

        /** New chat and Choose chat side by side, sharing the width equally. */
        private JComponent actionsRow() {
            JPanel row = new JPanel(new GridLayout(1, ACTION_COUNT, JBUI.scale(6), 0)) {
                @Override
                public Dimension getMaximumSize() {
                    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                }
            };
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(track(new CardButton(CardButton.Kind.ACTION, "New chat",
                "Start a new chat with the current preset", "+", CHAT_ACCENT,
                () -> CodexWorkflow.startNewChat(project), null)));
            row.add(track(new CardButton(CardButton.Kind.ACTION, "Choose chat", "Pick a saved chat and resume it",
                "□", PICKER_ACCENT, () -> CodexWorkflow.chooseAndOpenChat(project), null)));
            return row;
        }

        /** Settings below a thin line, pinned to the bottom of the tool window. */
        private JComponent settingsArea() {
            JPanel area = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            area.setOpaque(false);
            area.setBorder(JBUI.Borders.emptyTop(8));
            area.add(new JSeparator(), BorderLayout.NORTH);
            area.add(track(new CardButton(CardButton.Kind.ROW, "Settings", "Settings — presets and shortcuts", "⚙",
                PICKER_ACCENT, this::openSettings, null)), BorderLayout.CENTER);
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

        // Cards are never disabled: a model action performs its own connection
        // check and can start the local server when it is offline, so a card
        // stays usable after an IDE or plugin restart.
        private void refreshState() {
            ModelPreset.PresetId currentPreset = CodexWorkflow.lastPresetId();
            if (currentPreset == lastSelectedPreset) {
                return;
            }
            lastSelectedPreset = currentPreset;
            for (Map.Entry<ModelPreset.PresetId, CardButton> entry : modelCards.entrySet()) {
                entry.getValue().setHighlighted(entry.getKey() == currentPreset);
            }
        }

        private void installKeyboardNavigation() {
            bind(KeyEvent.VK_UP, "codexUp", () -> moveVertically(-1));
            bind(KeyEvent.VK_DOWN, "codexDown", () -> moveVertically(1));
            bind(KeyEvent.VK_LEFT, "codexLeft", () -> moveSideways(-1));
            bind(KeyEvent.VK_RIGHT, "codexRight", () -> moveSideways(1));
            bind(KeyEvent.VK_ENTER, "codexActivate", this::activateCursor);
            bind(KeyEvent.VK_SPACE, "codexActivate", this::activateCursor);
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    if (event.getCause().name().startsWith("TRAVERSAL")) {
                        // Tab into the panel picks the first row.
                        if (cursor < 0) {
                            setCursor(0);
                        }
                        return;
                    }
                    // Clicking a row made the IDE activate this tool window and
                    // focus it: hand focus straight back (to the terminal, say),
                    // so typing there continues. Opening the tool window from
                    // the keyboard keeps focus here, for the arrow keys.
                    // Not for an auto-hide tool window: losing focus would hide it mid-click.
                    Component opposite = event.getOppositeComponent();
                    if (opposite != null && !toolWindow.isAutoHide()
                            && System.nanoTime() - lastRowPressNanos < CLICK_FOCUS_NANOS) {
                        opposite.requestFocusInWindow();
                    }
                }

                @Override
                public void focusLost(FocusEvent event) {
                    setCursor(-1);
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

        /** Down from either chat button goes to the first model, up from the first model to New chat. */
        private void moveVertically(int direction) {
            int next;
            if (cursor < 0) {
                next = 0;
            } else if (cursor < ACTION_COUNT) {
                next = direction > 0 ? ACTION_COUNT : cursor;
            } else if (direction < 0 && cursor == ACTION_COUNT) {
                next = 0;
            } else {
                next = cursor + direction;
            }
            setCursor(Math.clamp(next, 0, rows.size() - 1));
        }

        private void moveSideways(int direction) {
            if (cursor >= 0 && cursor < ACTION_COUNT) {
                setCursor(Math.clamp(cursor + direction, 0, ACTION_COUNT - 1));
            }
        }

        private void activateCursor() {
            if (cursor >= 0) {
                rows.get(cursor).doClick();
            }
        }

        private void setCursor(int index) {
            if (cursor >= 0 && cursor < rows.size()) {
                rows.get(cursor).setKeyboardCursor(false);
            }
            cursor = index;
            if (index >= 0) {
                CardButton row = rows.get(index);
                row.setKeyboardCursor(true);
                row.scrollRectToVisible(new Rectangle(row.getSize()));
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
     * plus a bar on the left, and keeps both while hovered. The keyboard
     * cursor is drawn as a focus-colored outline.
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
        // Rows are recreated on every theme or zoom change, so these stay current.
        private final Font glyphFont = JBFont.small().asBold();
        private final Font shortcutFont = JBFont.small();
        private boolean selected;
        private boolean keyboardCursor;

        private CardButton(Kind kind, String title, String tooltip, String glyph, Color accent, Runnable action,
                           String shortcut) {
            super(title);
            this.kind = kind;
            this.title = title;
            this.glyph = glyph;
            this.accent = accent;
            this.shortcut = shortcut;
            setToolTipText(tooltip);
            setFocusable(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            addActionListener(ignored -> action.run());
        }

        private void setHighlighted(boolean selected) {
            this.selected = selected;
            repaint();
        }

        private void setKeyboardCursor(boolean keyboardCursor) {
            this.keyboardCursor = keyboardCursor;
            repaint();
        }

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
                return mix(ground(), accent, weight);
            }
            if (model.isPressed()) {
                return JBUI.CurrentTheme.ActionButton.pressedBackground();
            }
            return model.isRollover() ? JBUI.CurrentTheme.ActionButton.hoverBackground() : null;
        }

        /** The color actually painted behind this transparent row: its nearest opaque ancestor's. */
        private Color ground() {
            for (Component parent = getParent(); parent != null; parent = parent.getParent()) {
                if (parent.isOpaque() && parent.getBackground() != null) {
                    return parent.getBackground();
                }
            }
            return UIUtil.getPanelBackground();
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
                if (kind.outlined || keyboardCursor) {
                    g.setColor(keyboardCursor ? JBUI.CurrentTheme.Focus.focusColor() : JBColor.border());
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
                g.setColor(accent);
                g.fillRoundRect(padding, badgeY, badge, badge, JBUI.scale(6), JBUI.scale(6));
                g.setColor(Color.WHITE);
                g.setFont(glyphFont);
                FontMetrics glyphMetrics = g.getFontMetrics();
                g.drawString(glyph, padding + (badge - glyphMetrics.stringWidth(glyph)) / 2,
                    badgeY + (badge - glyphMetrics.getHeight()) / 2 + glyphMetrics.getAscent());

                int textX = padding + badge + JBUI.scale(10);
                int right = width - JBUI.scale(10);
                if (shortcut != null) {
                    String keys = "⌃" + shortcut;
                    g.setColor(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
                    g.setFont(shortcutFont);
                    FontMetrics keyMetrics = g.getFontMetrics();
                    right -= keyMetrics.stringWidth(keys);
                    g.drawString(keys, right, (height - keyMetrics.getHeight()) / 2 + keyMetrics.getAscent());
                    right -= JBUI.scale(8);
                }

                g.setColor(UIUtil.getLabelForeground());
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
