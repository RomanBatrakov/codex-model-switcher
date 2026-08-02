package ru.roman.codexmodelswitcher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.HashSet;
import java.util.Set;

final class CodexSettingsDialog extends DialogWrapper {
    private final CodexModelSwitcherSettings settings;
    private final JBTextField serverUrlField;
    private final JBCheckBox compactModeCheckBox;
    private final PresetFields[] presetFields;

    CodexSettingsDialog(Project project) {
        super(project);
        settings = CodexModelSwitcherSettings.getInstance();
        serverUrlField = new JBTextField(settings.serverUrl());
        compactModeCheckBox = new JBCheckBox("Компактный режим", settings.compactMode());
        presetFields = new PresetFields[ModelPreset.PresetId.values().length];
        for (ModelPreset.PresetId presetId : ModelPreset.PresetId.values()) {
            presetFields[presetId.ordinal()] = new PresetFields(presetId);
        }
        setTitle("Настройки Codex Model Switcher");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JBPanel<>(new BorderLayout(0, 12));
        panel.setPreferredSize(new Dimension(720, 340));

        JPanel connectionPanel = new JBPanel<>(new GridLayout(2, 1, 0, 4));
        connectionPanel.add(new JBLabel("Адрес app-server:"));
        connectionPanel.add(serverUrlField);
        panel.add(connectionPanel, BorderLayout.NORTH);

        JPanel presetsPanel = new JBPanel<>(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 0;
        addHeader(presetsPanel, constraints, 0, "Пресет");
        addHeader(presetsPanel, constraints, 1, "Модель");
        addHeader(presetsPanel, constraints, 2, "Reasoning");
        addHeader(presetsPanel, constraints, 3, "Ctrl +");
        for (PresetFields fields : presetFields) {
            int row = fields.presetId.ordinal() + 1;
            addCell(presetsPanel, constraints, 0, row, new JBLabel(fields.presetId.label()));
            addCell(presetsPanel, constraints, 1, row, fields.modelField);
            addCell(presetsPanel, constraints, 2, row, fields.effortField);
            addCell(presetsPanel, constraints, 3, row, fields.shortcutField);
        }
        constraints.weighty = 1;
        addCell(presetsPanel, constraints, 0, presetFields.length + 1, new JBLabel(""));
        panel.add(presetsPanel, BorderLayout.CENTER);

        JPanel footer = new JBPanel<>(new BorderLayout());
        footer.add(compactModeCheckBox, BorderLayout.WEST);
        JButton resetButton = new JButton("Сбросить пресеты");
        resetButton.addActionListener(ignored -> resetFields());
        footer.add(resetButton, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    protected void doOKAction() {
        String validationError = validateFields();
        if (validationError != null) {
            setErrorText(validationError);
            return;
        }
        settings.setServerUrl(serverUrlField.getText().trim());
        settings.setCompactMode(compactModeCheckBox.isSelected());
        for (PresetFields fields : presetFields) {
            settings.updatePreset(
                    fields.presetId,
                    fields.modelField.getText().trim(),
                    fields.effortField.getText().trim(),
                    fields.shortcutField.getText().trim());
        }
        super.doOKAction();
    }

    private void addHeader(JPanel panel, GridBagConstraints constraints, int column, String text) {
        addCell(panel, constraints, column, 0, new JBLabel(text));
    }

    private void addCell(JPanel panel, GridBagConstraints constraints, int column, int row, JComponent component) {
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.weightx = column == 0 ? 0 : 1;
        panel.add(component, constraints);
    }

    private String validateFields() {
        if (serverUrlField.getText().trim().isEmpty()
                || !(serverUrlField.getText().trim().startsWith("ws://")
                || serverUrlField.getText().trim().startsWith("wss://"))) {
            return "Адрес должен начинаться с ws:// или wss://.";
        }
        Set<String> shortcuts = new HashSet<>();
        for (PresetFields fields : presetFields) {
            if (fields.modelField.getText().trim().isEmpty()) {
                return "Модель для «" + fields.presetId.label() + "» не может быть пустой.";
            }
            if (fields.effortField.getText().trim().isEmpty()) {
                return "Уровень reasoning для «" + fields.presetId.label() + "» не может быть пустым.";
            }
            String shortcut = fields.shortcutField.getText().trim();
            if (!shortcut.matches("[1-9]")) {
                return "Сочетание для «" + fields.presetId.label() + "» должно быть одной цифрой от 1 до 9.";
            }
            if (!shortcuts.add(shortcut)) {
                return "У разных пресетов не могут повторяться цифры сочетаний.";
            }
        }
        return null;
    }

    private void resetFields() {
        CodexModelSwitcherSettings.Data defaults = new CodexModelSwitcherSettings.Data();
        serverUrlField.setText(defaults.serverUrl);
        compactModeCheckBox.setSelected(defaults.compactMode);
        for (PresetFields fields : presetFields) {
            fields.modelField.setText(defaultsFor(fields.presetId, true));
            fields.effortField.setText(defaultsFor(fields.presetId, false));
            fields.shortcutField.setText(defaultsShortcut(fields.presetId));
        }
    }

    private static String defaultsFor(ModelPreset.PresetId presetId, boolean model) {
        return switch (presetId) {
            case TERRA_MEDIUM -> model ? "gpt-5.6-terra" : "medium";
            case TERRA_XHIGH -> model ? "gpt-5.6-terra" : "xhigh";
            case SOL_XHIGH -> model ? "gpt-5.6-sol" : "xhigh";
            case LUNA_MAX -> model ? "gpt-5.6-luna" : "max";
        };
    }

    private static String defaultsShortcut(ModelPreset.PresetId presetId) {
        return String.valueOf(presetId.ordinal() + 1);
    }

    private static final class PresetFields {
        private final ModelPreset.PresetId presetId;
        private final JBTextField modelField;
        private final JBTextField effortField;
        private final JBTextField shortcutField;

        private PresetFields(ModelPreset.PresetId presetId) {
            this.presetId = presetId;
            CodexModelSwitcherSettings settings = CodexModelSwitcherSettings.getInstance();
            modelField = new JBTextField(settings.model(presetId));
            effortField = new JBTextField(settings.effort(presetId));
            shortcutField = new JBTextField(settings.shortcut(presetId));
        }
    }
}
