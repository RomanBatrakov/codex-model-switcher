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
        data.serverUrl = serverUrl;
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
        switch (presetId) {
            case TERRA_MEDIUM -> {
                data.terraMediumModel = model;
                data.terraMediumEffort = effort;
                data.terraMediumShortcut = shortcut;
            }
            case TERRA_XHIGH -> {
                data.terraXHighModel = model;
                data.terraXHighEffort = effort;
                data.terraXHighShortcut = shortcut;
            }
            case SOL_XHIGH -> {
                data.solXHighModel = model;
                data.solXHighEffort = effort;
                data.solXHighShortcut = shortcut;
            }
            case LUNA_MAX -> {
                data.lunaMaxModel = model;
                data.lunaMaxEffort = effort;
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
        if (data.serverUrl == null || data.serverUrl.isBlank()) {
            data.serverUrl = Data.DEFAULT_SERVER_URL;
        }
        if (data.terraMediumModel == null || data.terraMediumModel.isBlank()) {
            data.terraMediumModel = "gpt-5.6-terra";
        }
        if (data.terraMediumEffort == null || data.terraMediumEffort.isBlank()) {
            data.terraMediumEffort = "medium";
        }
        if (data.terraMediumShortcut == null || data.terraMediumShortcut.isBlank()) {
            data.terraMediumShortcut = "1";
        }
        if (data.terraXHighModel == null || data.terraXHighModel.isBlank()) {
            data.terraXHighModel = "gpt-5.6-terra";
        }
        if (data.terraXHighEffort == null || data.terraXHighEffort.isBlank()) {
            data.terraXHighEffort = "xhigh";
        }
        if (data.terraXHighShortcut == null || data.terraXHighShortcut.isBlank()) {
            data.terraXHighShortcut = "2";
        }
        if (data.solXHighModel == null || data.solXHighModel.isBlank()) {
            data.solXHighModel = "gpt-5.6-sol";
        }
        if (data.solXHighEffort == null || data.solXHighEffort.isBlank()) {
            data.solXHighEffort = "xhigh";
        }
        if (data.solXHighShortcut == null || data.solXHighShortcut.isBlank()) {
            data.solXHighShortcut = "3";
        }
        if (data.lunaMaxModel == null || data.lunaMaxModel.isBlank()) {
            data.lunaMaxModel = "gpt-5.6-luna";
        }
        if (data.lunaMaxEffort == null || data.lunaMaxEffort.isBlank()) {
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
        static final String DEFAULT_SERVER_URL = "ws://127.0.0.1:4500";

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
