package ru.roman.codexmodelswitcher;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.APP)
@State(name = "CodexModelSwitcher", storages = @Storage("codex-model-switcher.xml"))
public final class CodexModelSwitcherSettings implements PersistentStateComponent<CodexModelSwitcherSettings.Data> {
    private Data data = new Data();

    public static CodexModelSwitcherSettings getInstance() {
        return ApplicationManager.getApplication().getService(CodexModelSwitcherSettings.class);
    }

    @Override
    public @Nullable Data getState() {
        return data;
    }

    @Override
    public void loadState(@NotNull Data state) {
        data = state;
        normalizeData();
    }

    public String serverUrl() {
        return data.serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        data.serverUrl = CodexServerEndpoint.normalizeOrDefault(serverUrl);
    }

    public String model(ModelPreset.PresetId presetId) {
        return switch (presetId) {
            case TERRA_MEDIUM -> data.terraMediumModel;
            case TERRA_XHIGH -> data.terraXHighModel;
            case SOL_XHIGH -> data.solXHighModel;
            case LUNA_MAX -> data.lunaMaxModel;
        };
    }

    public String effort(ModelPreset.PresetId presetId) {
        return switch (presetId) {
            case TERRA_MEDIUM -> data.terraMediumEffort;
            case TERRA_XHIGH -> data.terraXHighEffort;
            case SOL_XHIGH -> data.solXHighEffort;
            case LUNA_MAX -> data.lunaMaxEffort;
        };
    }

    public String shortcut(ModelPreset.PresetId presetId) {
        return switch (presetId) {
            case TERRA_MEDIUM -> data.terraMediumShortcut;
            case TERRA_XHIGH -> data.terraXHighShortcut;
            case SOL_XHIGH -> data.solXHighShortcut;
            case LUNA_MAX -> data.lunaMaxShortcut;
        };
    }

    public void updatePreset(ModelPreset.PresetId presetId, String model, String effort, String shortcut) {
        String normalizedModel = ModelPreset.requireModel(model);
        String normalizedEffort = ModelPreset.requireEffort(effort);
        switch (presetId) {
            case TERRA_MEDIUM -> {
                data.terraMediumModel = normalizedModel;
                data.terraMediumEffort = normalizedEffort;
                data.terraMediumShortcut = shortcut;
            }
            case TERRA_XHIGH -> {
                data.terraXHighModel = normalizedModel;
                data.terraXHighEffort = normalizedEffort;
                data.terraXHighShortcut = shortcut;
            }
            case SOL_XHIGH -> {
                data.solXHighModel = normalizedModel;
                data.solXHighEffort = normalizedEffort;
                data.solXHighShortcut = shortcut;
            }
            case LUNA_MAX -> {
                data.lunaMaxModel = normalizedModel;
                data.lunaMaxEffort = normalizedEffort;
                data.lunaMaxShortcut = shortcut;
            }
        }
    }

    public boolean compactMode() {
        return data.compactMode;
    }

    public void setCompactMode(boolean compactMode) {
        data.compactMode = compactMode;
    }

    public String lastThreadId() {
        return data.lastThreadId;
    }

    public void setLastThreadId(String lastThreadId) {
        data.lastThreadId = lastThreadId;
    }

    public ModelPreset.PresetId lastPresetId() {
        try {
            return ModelPreset.PresetId.valueOf(data.lastPresetId);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return ModelPreset.PresetId.TERRA_MEDIUM;
        }
    }

    public void setLastPresetId(ModelPreset.PresetId lastPresetId) {
        data.lastPresetId = lastPresetId.name();
    }

    private void normalizeData() {
        if (!CodexServerEndpoint.isValid(data.serverUrl)) {
            data.serverUrl = Data.DEFAULT_SERVER_URL;
        }
        if (!ModelPreset.isValidModel(data.terraMediumModel)) {
            data.terraMediumModel = "gpt-5.6-terra";
        }
        if (!ModelPreset.isValidEffort(data.terraMediumEffort)) {
            data.terraMediumEffort = "medium";
        }
        if (data.terraMediumShortcut == null || data.terraMediumShortcut.isBlank()) {
            data.terraMediumShortcut = "1";
        }
        if (!ModelPreset.isValidModel(data.terraXHighModel)) {
            data.terraXHighModel = "gpt-5.6-terra";
        }
        if (!ModelPreset.isValidEffort(data.terraXHighEffort)) {
            data.terraXHighEffort = "xhigh";
        }
        if (data.terraXHighShortcut == null || data.terraXHighShortcut.isBlank()) {
            data.terraXHighShortcut = "2";
        }
        if (!ModelPreset.isValidModel(data.solXHighModel)) {
            data.solXHighModel = "gpt-5.6-sol";
        }
        if (!ModelPreset.isValidEffort(data.solXHighEffort)) {
            data.solXHighEffort = "xhigh";
        }
        if (data.solXHighShortcut == null || data.solXHighShortcut.isBlank()) {
            data.solXHighShortcut = "3";
        }
        if (!ModelPreset.isValidModel(data.lunaMaxModel)) {
            data.lunaMaxModel = "gpt-5.6-luna";
        }
        if (!ModelPreset.isValidEffort(data.lunaMaxEffort)) {
            data.lunaMaxEffort = "max";
        }
        if (data.lunaMaxShortcut == null || data.lunaMaxShortcut.isBlank()) {
            data.lunaMaxShortcut = "4";
        }
        if (data.lastPresetId == null || data.lastPresetId.isBlank()) {
            data.lastPresetId = ModelPreset.PresetId.TERRA_MEDIUM.name();
        }
    }

    public static final class Data {
        static final String DEFAULT_SERVER_URL = CodexServerEndpoint.DEFAULT_URL;

        public String serverUrl = DEFAULT_SERVER_URL;
        public String terraMediumModel = "gpt-5.6-terra";
        public String terraMediumEffort = "medium";
        public String terraMediumShortcut = "1";
        public String terraXHighModel = "gpt-5.6-terra";
        public String terraXHighEffort = "xhigh";
        public String terraXHighShortcut = "2";
        public String solXHighModel = "gpt-5.6-sol";
        public String solXHighEffort = "xhigh";
        public String solXHighShortcut = "3";
        public String lunaMaxModel = "gpt-5.6-luna";
        public String lunaMaxEffort = "max";
        public String lunaMaxShortcut = "4";
        public boolean compactMode;
        public String lastThreadId;
        public String lastPresetId = ModelPreset.PresetId.TERRA_MEDIUM.name();
    }
}
