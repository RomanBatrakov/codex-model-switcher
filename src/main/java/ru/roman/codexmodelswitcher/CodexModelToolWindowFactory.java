package ru.roman.codexmodelswitcher;

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
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class CodexModelToolWindowFactory implements ToolWindowFactory {
    private static final Color CHAT_ACCENT = new JBColor(new Color(0x356CCB), new Color(0x5B8DEF));
    private static final Color PICKER_ACCENT = new JBColor(new Color(0x7652B8), new Color(0xA77BDF));
    private static final Color TERRA_ACCENT = new JBColor(new Color(0x4A9B5F), new Color(0x72CF78));
    private static final Color TERRA_XHIGH_ACCENT = new JBColor(new Color(0x8050B5), new Color(0xB184E8));
    private static final Color SOL_ACCENT = new JBColor(new Color(0xC47B24), new Color(0xF2AE4B));
    private static final Color LUNA_ACCENT = new JBColor(new Color(0x3978C5), new Color(0x70A8F0));

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ToolWindowPanel panel = new ToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(content, panel);
    }

    private static void addSection(JPanel sections, String title) {
        if (sections.getComponentCount() > 0) {
            sections.add(Box.createVerticalStrut(5));
        }
        JBLabel label = new JBLabel(title);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(JBUI.Borders.empty(2, 4, 2, 4));
        label.setForeground(uiColor("Label.disabledForeground", new JBColor(0x6B6B6B, 0xA6A6A6)));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 10f));
        sections.add(label);
    }

    private static void addCard(JPanel sections, CardButton card) {
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        sections.add(card);
        sections.add(Box.createVerticalStrut(2));
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = javax.swing.UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private static Color mix(Color first, Color second, float secondWeight) {
        float firstWeight = 1f - secondWeight;
        return new Color(Math.round(first.getRed() * firstWeight + second.getRed() * secondWeight),
            Math.round(first.getGreen() * firstWeight + second.getGreen() * secondWeight),
            Math.round(first.getBlue() * firstWeight + second.getBlue() * secondWeight));
    }

    private static final class ToolWindowPanel extends JBPanel<ToolWindowPanel> implements Disposable {
        private final Project project;
        private final Timer statusTimer;
        private final Map<ModelPreset.PresetId, CardButton> modelCards = new EnumMap<>(ModelPreset.PresetId.class);
        private ModelPreset.PresetId lastSelectedPreset;

        private ToolWindowPanel(Project project) {
            super(new BorderLayout());
            this.project = project;
            setBorder(JBUI.Borders.empty(8));
            setOpaque(true);
            setBackground(uiColor("Panel.background", new JBColor(0xF2F2F2, 0x2B2B2B)));
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
            sections.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
            sections.setOpaque(false);

            addSection(sections, "ACTIONS");
            addCard(sections,
                new CardButton("New chat", null, "+", CHAT_ACCENT, () -> CodexWorkflow.startNewChat(project), null));
            addCard(sections,
                new CardButton("Choose chat", null, "□", PICKER_ACCENT, () -> CodexWorkflow.chooseAndOpenChat(project),
                    null));

            addSection(sections, "MODELS");
            addModelCard(sections, ModelPreset.PresetId.TERRA_MEDIUM, "▲", TERRA_ACCENT);
            addModelCard(sections, ModelPreset.PresetId.TERRA_XHIGH, "▲", TERRA_XHIGH_ACCENT);
            addModelCard(sections, ModelPreset.PresetId.SOL_XHIGH, "●", SOL_ACCENT);
            addModelCard(sections, ModelPreset.PresetId.LUNA_MAX, "◆", LUNA_ACCENT);

            List<LaunchProfile> profiles = CodexModelSwitcherSettings.getInstance().launchProfiles();
            if (!profiles.isEmpty()) {
                addSection(sections, "PROFILES");
                for (LaunchProfile profile : profiles) {
                    addCard(sections,
                        new CardButton(profile.name, null, "»", PICKER_ACCENT,
                            () -> CodexWorkflow.launchProfile(project, profile), null));
                }
            }

            addSection(sections, "OPTIONS");
            addCard(sections,
                new CardButton("Settings", "presets and shortcuts", "⚙", PICKER_ACCENT, this::openSettings, null));

            JBScrollPane scrollPane = new JBScrollPane(sections);
            scrollPane.setBorder(null);
            scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            scrollPane.setOpaque(false);
            scrollPane.getViewport().setOpaque(false);
            add(scrollPane, BorderLayout.CENTER);
            refreshState();
            revalidate();
            repaint();
        }

        private void addModelCard(JPanel sections, ModelPreset.PresetId presetId, String glyph, Color accent) {
            ModelPreset preset = ModelPreset.forId(presetId);
            CardButton card = new CardButton(preset.model() + " · " + preset.effort(), preset.label(), glyph, accent,
                () -> CodexWorkflow.switchModel(project, ModelPreset.forId(presetId)),
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

        @Override
        public void dispose() {
            statusTimer.stop();
        }
    }

    private static final class CardButton extends JButton {
        private final String title;
        private String subtitle;
        private final String glyph;
        private final Color accent;
        private final String shortcut;
        private boolean selected;

        private CardButton(String title, String subtitle, String glyph, Color accent, Runnable action, String shortcut) {
            super(title);
            this.title = title;
            this.subtitle = subtitle;
            this.glyph = glyph;
            this.accent = accent;
            this.shortcut = shortcut;
            setToolTipText(subtitle == null ? title : title + " — " + subtitle);
            setFocusable(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            int height = subtitle == null ? 34 : 40;
            setPreferredSize(new Dimension(0, height));
            setMinimumSize(new Dimension(0, height));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            addActionListener(ignored -> action.run());
        }

        private void setSubtitle(String subtitle) {
            this.subtitle = subtitle;
            setToolTipText(title + " — " + subtitle);
            repaint();
        }

        private void setHighlighted(boolean selected) {
            this.selected = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color normalBackground = uiColor("Button.background", new JBColor(0xE8E8E8, 0x3C3F41));
            Color background;
            if (!isEnabled()) {
                background = uiColor("Button.disabledBackground",
                    mix(normalBackground, uiColor("Panel.background", normalBackground), 0.45f));
            } else if (getModel().isPressed()) {
                background = mix(normalBackground, accent, 0.25f);
            } else if (selected) {
                background = mix(normalBackground, accent, 0.20f);
            } else if (getModel().isRollover()) {
                background = mix(normalBackground, accent, 0.14f);
            } else {
                background = normalBackground;
            }
            g.setColor(background);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            if (selected && isEnabled()) {
                g.setColor(accent);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            }

            int iconY = (getHeight() - 18) / 2;
            g.setColor(isEnabled() ? accent : mix(accent, background, 0.5f));
            g.fillRoundRect(10, iconY, 18, 18, 6, 6);
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            int glyphWidth = g.getFontMetrics().stringWidth(glyph);
            g.drawString(glyph, 19 - glyphWidth / 2, iconY + 13);

            Color foreground = isEnabled() ? uiColor("Button.foreground", new JBColor(0x303030, 0xE8E8E8)) :
                uiColor("Button.disabledForeground", new JBColor(0x8A8A8A, 0x777777));
            g.setColor(foreground);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            int textX = 40;
            if (subtitle == null) {
                g.drawString(title, textX, getHeight() / 2 + 5);
            } else {
                g.drawString(title, textX, getHeight() / 2 - 1);
                g.setColor(uiColor("Label.disabledForeground", mix(foreground, background, 0.35f)));
                g.setFont(getFont().deriveFont(Font.PLAIN, 10f));
                g.drawString(subtitle, textX, getHeight() / 2 + 14);
            }

            if (shortcut != null) {
                g.setColor(uiColor("Label.disabledForeground", mix(foreground, background, 0.35f)));
                g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                int shortcutWidth = g.getFontMetrics().stringWidth("⌃" + shortcut);
                g.drawString("⌃" + shortcut, getWidth() - shortcutWidth - 12, getHeight() / 2 + 4);
            }
            g.dispose();
        }
    }
}
