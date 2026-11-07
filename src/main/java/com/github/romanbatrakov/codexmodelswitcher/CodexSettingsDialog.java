package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBFont;
import com.intellij.util.system.OS;
import com.intellij.util.ui.JBUI;
import org.jspecify.annotations.NonNull;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
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
        Set<String> modelSuggestions = new LinkedHashSet<>(ModelPreset.knownModels());
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
        activeChatOnlyCheckBox = new JBCheckBox("Apply presets only to the active chat", settings.activeChatOnly());
        activeChatOnlyCheckBox.setToolTipText(
                "Apply a preset only to the active chat tab, instead of every open chat");
        setTitle("Model Switcher for Codex CLI Settings");
        init();
    }

    /**
     * Three sections: MODEL PRESETS, LAUNCH PROFILES and CONNECTION. The
     * profiles table sits in the middle, so it is the part that grows when the
     * dialog is resized.
     */
    @Override
    protected @NonNull JComponent createCenterPanel() {
        JPanel panel = new JBPanel<>(new BorderLayout());
        panel.add(presetsSection(), BorderLayout.NORTH);
        panel.add(profilesSection(), BorderLayout.CENTER);
        panel.add(connectionSection(), BorderLayout.SOUTH);
        return panel;
    }

    /** Preset | Model | Reasoning | Shortcut, then the active-chat option and Reset presets. */
    private JComponent presetsSection() {
        JPanel grid = new JBPanel<>(new GridBagLayout());
        addCell(grid, caption("Preset"), 0, 0);
        addCell(grid, caption("Model"), 1, 0);
        addCell(grid, caption("Reasoning"), 2, 0);
        addCell(grid, caption("Shortcut"), 3, 0);
        // The shortcut is Ctrl + digit on every platform; only the label differs.
        String modifier = OS.CURRENT == OS.macOS ? "⌃" : "Ctrl+";
        for (PresetFields fields : presetFields) {
            int row = fields.presetId.ordinal() + 1;
            addCell(grid, new JBLabel(fields.presetId.label()), 0, row);
            addCell(grid, fields.modelField, 1, row);
            addCell(grid, fields.effortField, 2, row);
            JPanel shortcut = new JBPanel<>(new BorderLayout(JBUI.scale(4), 0));
            shortcut.add(new JBLabel(modifier), BorderLayout.WEST);
            shortcut.add(fields.shortcutField, BorderLayout.CENTER);
            addCell(grid, shortcut, 3, row);
        }
        int row = presetFields.length + 1;
        grid.add(activeChatOnlyCheckBox, wideCell(row++, 8));
        ActionLink resetLink = new ActionLink("Reset presets");
        resetLink.addActionListener(ignored -> resetFields());
        resetLink.setIcon(AllIcons.Actions.Rollback);
        grid.add(resetLink, wideCell(row, 6));
        return section("MODEL PRESETS", true, grid);
    }

    private JComponent profilesSection() {
        // Tall enough for at least four rows, at most six; more rows scroll.
        int visibleRows = Math.clamp(profilesTableModel.getRowCount(), 4, 6);
        profilesTable.setPreferredScrollableViewportSize(
                new Dimension(JBUI.scale(540), profilesTable.getRowHeight() * visibleRows));
        profilesTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(170));
        profilesTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(370));
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
        return section("LAUNCH PROFILES", false, profilesPanel);
    }

    private JComponent connectionSection() {
        String hint = "Preferred address of the local Codex app server. Each project runs its own server: "
                + "on this port, or on the next free one if this port is taken.";
        JBLabel label = new JBLabel("App-server URL");
        label.setToolTipText(hint);
        serverUrlField.setToolTipText(hint);
        JPanel content = new JBPanel<>(new BorderLayout(0, JBUI.scale(4)));
        content.add(label, BorderLayout.NORTH);
        content.add(serverUrlField, BorderLayout.CENTER);
        return section("CONNECTION", false, content);
    }

    /** A muted small title over a thin line, the same style as the Codex Usage popup. */
    private static JComponent section(String title, boolean first, JComponent content) {
        JBLabel label = new JBLabel(title);
        label.setFont(JBFont.small().asBold());
        label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        JPanel header = new JBPanel<>(new BorderLayout(0, JBUI.scale(4)));
        header.setBorder(JBUI.Borders.empty(first ? 0 : 16, 0, 8, 0));
        header.add(label, BorderLayout.NORTH);
        header.add(new JSeparator(), BorderLayout.CENTER);

        JPanel section = new JBPanel<>(new BorderLayout());
        section.add(header, BorderLayout.NORTH);
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    private static JBLabel caption(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(JBFont.small());
        label.setForeground(JBUI.CurrentTheme.ContextHelp.FOREGROUND);
        return label;
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

    /**
     * One cell of the presets grid. Only the Model column takes extra width;
     * Reasoning and Shortcut stay as narrow as their widest value, and every
     * cell in a column fills it, so the fields line up.
     */
    private static void addCell(JPanel grid, JComponent component, int column, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.weightx = column == 1 ? 1 : 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = JBUI.insets(row == 0 ? 0 : 2, 0, 2, column == 3 ? 0 : 10);
        grid.add(component, constraints);
    }

    /** A grid row spanning all four columns, left-aligned. */
    private static GridBagConstraints wideCell(int row, int top) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 4;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = JBUI.insets(top, 0, 0, 0);
        return constraints;
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

    /** Restores the four presets' defaults; the app-server address in CONNECTION is left alone. */
    private void resetFields() {
        CodexModelSwitcherSettings.Data defaults = new CodexModelSwitcherSettings.Data();
        for (PresetFields fields : presetFields) {
            fields.modelField.getEditor().setItem(defaults.model(fields.presetId));
            fields.refreshEffortOptions();
            fields.effortField.setSelectedItem(defaults.effort(fields.presetId));
            fields.shortcutField.setText(defaults.shortcut(fields.presetId));
        }
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
            shortcutField = new JBTextField(settings.shortcut(presetId), 2);

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
