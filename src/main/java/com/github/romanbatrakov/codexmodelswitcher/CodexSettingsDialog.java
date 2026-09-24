package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import org.jspecify.annotations.NonNull;

import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings dialog: app-server address, the four model/effort/shortcut
 * presets, and the user-managed launch-profiles table. Edits are held in a
 * working copy and only committed to {@link CodexModelSwitcherSettings} once
 * validation passes and the user clicks OK.
 */
final class CodexSettingsDialog extends DialogWrapper {
    private static final String[] PROFILE_COLUMNS = {"Name", "Arguments"};

    private final CodexModelSwitcherSettings settings;
    private final JBTextField serverUrlField;
    private final PresetFields[] presetFields;
    private final DefaultTableModel profilesTableModel;
    private final JBTable profilesTable;
    private final JCheckBox activeChatOnlyCheckBox;

    CodexSettingsDialog(Project project) {
        super(project);
        settings = CodexModelSwitcherSettings.getInstance();
        serverUrlField = new JBTextField(settings.serverUrl());
        Set<String> modelSuggestions = new LinkedHashSet<>(
                List.of("gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
                        "gpt-5.6-terra", "gpt-5.6-sol", "gpt-5.6-luna", "gpt-5.5"));
        for (ModelPreset.PresetId presetId : ModelPreset.PresetId.values()) {
            modelSuggestions.add(settings.model(presetId));
        }
        presetFields = new PresetFields[ModelPreset.PresetId.values().length];
        for (ModelPreset.PresetId presetId : ModelPreset.PresetId.values()) {
            presetFields[presetId.ordinal()] = new PresetFields(presetId, modelSuggestions);
        }
        profilesTableModel = new DefaultTableModel(PROFILE_COLUMNS, 0);
        for (LaunchProfile profile : settings.launchProfiles()) {
            profilesTableModel.addRow(new Object[]{profile.name, profile.arguments});
        }
        profilesTable = new JBTable(profilesTableModel);
        activeChatOnlyCheckBox = new JCheckBox(
                "Apply a preset only to the active chat tab, instead of every open chat",
                settings.activeChatOnly());
        setTitle("Model Switcher for Codex CLI Settings");
        init();
    }

    @Override
    protected @NonNull JComponent createCenterPanel() {
        JPanel panel = new JBPanel<>(new BorderLayout(0, 12));
        panel.setPreferredSize(new Dimension(720, 480));

        JPanel connectionPanel = new JBPanel<>(new GridLayout(2, 1, 0, 4));
        connectionPanel.add(new JBLabel("Preferred app-server address (each project uses its own port,"
                + " starting from this one, if it's taken):"));
        connectionPanel.add(serverUrlField);
        panel.add(connectionPanel, BorderLayout.NORTH);

        JPanel presetsPanel = new JBPanel<>(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 0;
        addHeader(presetsPanel, constraints, 0, "Preset");
        addHeader(presetsPanel, constraints, 1, "Model");
        addHeader(presetsPanel, constraints, 2, "Reasoning");
        addHeader(presetsPanel, constraints, 3, "Ctrl +");
        for (PresetFields fields : presetFields) {
            int row = fields.presetId.ordinal() + 1;
            addCell(presetsPanel, constraints, 0, row, new JBLabel(fields.presetId.label()));
            addCell(presetsPanel, constraints, 1, row, fields.modelField);
            addCell(presetsPanel, constraints, 2, row, fields.effortField);
            addCell(presetsPanel, constraints, 3, row, fields.shortcutField);
        }
        GridBagConstraints checkBoxConstraints = new GridBagConstraints();
        checkBoxConstraints.gridx = 0;
        checkBoxConstraints.gridy = presetFields.length + 1;
        checkBoxConstraints.gridwidth = 4;
        checkBoxConstraints.anchor = GridBagConstraints.WEST;
        checkBoxConstraints.insets = new Insets(8, 4, 3, 4);
        presetsPanel.add(activeChatOnlyCheckBox, checkBoxConstraints);

        JPanel profilesSection = new JBPanel<>(new BorderLayout(0, 4));
        profilesSection.add(new JBLabel("Launch profiles:"), BorderLayout.NORTH);
        JPanel profilesPanel = ToolbarDecorator.createDecorator(profilesTable)
                .setAddAction(_ -> profilesTableModel.addRow(new Object[]{"", ""}))
                .setRemoveAction(_ -> {
                    if (profilesTable.isEditing()) {
                        profilesTable.getCellEditor().stopCellEditing();
                    }
                    int row = profilesTable.getSelectedRow();
                    if (row >= 0) {
                        profilesTableModel.removeRow(row);
                    }
                })
                .disableUpDownActions()
                .createPanel();
        profilesPanel.setPreferredSize(new Dimension(0, 140));
        profilesSection.add(profilesPanel, BorderLayout.CENTER);

        JPanel centerPanel = new JBPanel<>();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        presetsPanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        profilesSection.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        centerPanel.add(presetsPanel);
        centerPanel.add(javax.swing.Box.createVerticalStrut(12));
        centerPanel.add(profilesSection);
        panel.add(centerPanel, BorderLayout.CENTER);

        JPanel footer = new JBPanel<>(new BorderLayout());
        JButton resetButton = new JButton("Reset presets");
        resetButton.addActionListener(ignored -> resetFields());
        footer.add(resetButton, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    protected void doOKAction() {
        stopProfilesEditing();
        String validationError = validateFields();
        if (validationError != null) {
            setErrorText(validationError);
            return;
        }
        settings.setServerUrl(serverUrlField.getText().trim());
        for (PresetFields fields : presetFields) {
            settings.updatePreset(
                    fields.presetId,
                    fields.model(),
                    fields.effort(),
                    fields.shortcutField.getText().trim());
        }
        settings.setLaunchProfiles(nonBlankProfileRows());
        settings.setActiveChatOnly(activeChatOnlyCheckBox.isSelected());
        super.doOKAction();
    }

    private void stopProfilesEditing() {
        if (profilesTable.isEditing()) {
            profilesTable.getCellEditor().stopCellEditing();
        }
    }

    private List<LaunchProfile> nonBlankProfileRows() {
        List<LaunchProfile> profiles = new ArrayList<>();
        for (int row = 0; row < profilesTableModel.getRowCount(); row++) {
            String name = String.valueOf(profilesTableModel.getValueAt(row, 0)).trim();
            String arguments = String.valueOf(profilesTableModel.getValueAt(row, 1)).trim();
            if (name.isEmpty() && arguments.isEmpty()) {
                continue;
            }
            LaunchProfile profile = new LaunchProfile();
            profile.name = name;
            profile.arguments = arguments;
            profiles.add(profile);
        }
        return profiles;
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
        String serverUrl = serverUrlField.getText().trim();
        if (!CodexServerEndpoint.isValid(serverUrl)) {
            try {
                CodexServerEndpoint.requireValid(serverUrl);
            } catch (IllegalArgumentException error) {
                return error.getMessage();
            }
            return "The app-server address is invalid.";
        }
        Set<String> shortcuts = new HashSet<>();
        for (PresetFields fields : presetFields) {
            String model = fields.model();
            String modelError = ModelPreset.validateModel(model);
            if (modelError != null) {
                return "Model for \"" + fields.presetId.label() + "\" " + modelError;
            }
            String effort = fields.effort();
            String effortError = ModelPreset.validateEffort(model, effort);
            if (effortError != null) {
                return "Reasoning effort for \"" + fields.presetId.label() + "\" " + effortError;
            }
            String shortcut = fields.shortcutField.getText().trim();
            if (!shortcut.matches("[1-9]")) {
                return "Shortcut for \"" + fields.presetId.label() + "\" must be a single digit from 1 to 9.";
            }
            if (!shortcuts.add(shortcut)) {
                return "Presets cannot share the same shortcut digit.";
            }
        }
        Set<String> profileNames = new HashSet<>();
        for (LaunchProfile profile : nonBlankProfileRows()) {
            String nameError = LaunchProfile.validateName(profile.name);
            if (nameError != null) {
                return "Launch profile name " + nameError;
            }
            String argumentsError = LaunchProfile.validateArguments(profile.arguments);
            if (argumentsError != null) {
                return "Launch profile \"" + profile.name + "\" arguments " + argumentsError;
            }
            if (!profileNames.add(profile.name)) {
                return "Launch profile names must be unique.";
            }
        }
        return null;
    }

    private void resetFields() {
        CodexModelSwitcherSettings.Data defaults = new CodexModelSwitcherSettings.Data();
        serverUrlField.setText(defaults.serverUrl);
        for (PresetFields fields : presetFields) {
            fields.modelField.getEditor().setItem(defaultsFor(fields.presetId, true));
            fields.refreshEffortOptions();
            fields.effortField.setSelectedItem(defaultsFor(fields.presetId, false));
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

    /** The editable UI fields for one preset row: model combo, effort combo, and shortcut digit. */
    private static final class PresetFields {
        private final ModelPreset.PresetId presetId;
        private final JComboBox<String> modelField;
        private final JComboBox<String> effortField;
        private final JBTextField shortcutField;

        private PresetFields(ModelPreset.PresetId presetId, Set<String> modelSuggestions) {
            this.presetId = presetId;
            CodexModelSwitcherSettings settings = CodexModelSwitcherSettings.getInstance();
            modelField = new JComboBox<>(modelSuggestions.toArray(new String[0]));
            modelField.setEditable(true);
            modelField.setSelectedItem(settings.model(presetId));
            effortField = new JComboBox<>(ModelPreset.effortOptionsFor(settings.model(presetId)).toArray(new String[0]));
            effortField.setSelectedItem(settings.effort(presetId));
            shortcutField = new JBTextField(settings.shortcut(presetId));

            // Different models support different reasoning levels (e.g. "ultra"
            // is Astra/Sol/Terra-only) -- keep the Reasoning dropdown limited to
            // what the currently typed model actually supports, reacting to both
            // picking a suggestion and free-form typing.
            modelField.addActionListener(ignored -> refreshEffortOptions());
            JTextField editorComponent = (JTextField) modelField.getEditor().getEditorComponent();
            editorComponent.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent event) {
                    refreshEffortOptions();
                }

                @Override
                public void removeUpdate(DocumentEvent event) {
                    refreshEffortOptions();
                }

                @Override
                public void changedUpdate(DocumentEvent event) {
                    refreshEffortOptions();
                }
            });
        }

        private String model() {
            return String.valueOf(modelField.getEditor().getItem()).trim();
        }

        private String effort() {
            return String.valueOf(effortField.getSelectedItem());
        }

        /** Repopulates the Reasoning dropdown for the currently typed model, keeping the current value if still valid. */
        private void refreshEffortOptions() {
            List<String> options = ModelPreset.effortOptionsFor(model());
            String current = effort();
            effortField.setModel(new DefaultComboBoxModel<>(options.toArray(new String[0])));
            effortField.setSelectedItem(options.contains(current) ? current : options.get(0));
        }
    }
}
