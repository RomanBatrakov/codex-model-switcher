package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persisted plugin settings: the app-server address, the four model/effort/
 * shortcut presets, launch profiles, and small bits of last-used state used
 * to restore the UI across restarts.
 */
@Service(Service.Level.APP)
@State(name = "CodexModelSwitcher", storages = @Storage("codex-model-switcher.xml"))
public final class CodexModelSwitcherSettings implements PersistentStateComponent<CodexModelSwitcherSettings.Data> {
    private Data data = new Data();

    /** Returns the application-level singleton instance. */
    public static CodexModelSwitcherSettings getInstance() {
        return ApplicationManager.getApplication().getService(CodexModelSwitcherSettings.class);
    }

    @Override
    public @Nullable Data getState() {
        return data;
    }

    /** Loads previously persisted state, repairing any invalid/missing fields (see {@link #normalizeData()}). */
    @Override
    public void loadState(@NotNull Data state) {
        data = state;
        normalizeData();
    }

    /** The configured local app-server WebSocket address. */
    public String serverUrl() {
        return data.serverUrl;
    }

    /** Sets the app-server address, falling back to the default if the value is invalid. */
    public void setServerUrl(String serverUrl) {
        data.serverUrl = CodexServerEndpoint.normalizeOrDefault(serverUrl);
    }

    /** The configured model name for the given preset slot. */
    public String model(ModelPreset.PresetId presetId) {
        return switch (presetId) {
            case TERRA_MEDIUM -> data.terraMediumModel;
            case TERRA_XHIGH -> data.terraXHighModel;
            case SOL_XHIGH -> data.solXHighModel;
            case LUNA_MAX -> data.lunaMaxModel;
        };
    }

    /** The configured reasoning effort for the given preset slot. */
    public String effort(ModelPreset.PresetId presetId) {
        return switch (presetId) {
            case TERRA_MEDIUM -> data.terraMediumEffort;
            case TERRA_XHIGH -> data.terraXHighEffort;
            case SOL_XHIGH -> data.solXHighEffort;
            case LUNA_MAX -> data.lunaMaxEffort;
        };
    }

    /** The configured keyboard-shortcut digit for the given preset slot. */
    public String shortcut(ModelPreset.PresetId presetId) {
        return switch (presetId) {
            case TERRA_MEDIUM -> data.terraMediumShortcut;
            case TERRA_XHIGH -> data.terraXHighShortcut;
            case SOL_XHIGH -> data.solXHighShortcut;
            case LUNA_MAX -> data.lunaMaxShortcut;
        };
    }

    /**
     * Replaces one preset's model, effort, and shortcut together.
     *
     * @throws IllegalArgumentException if {@code model} or {@code effort} fails validation
     */
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

    /** The configured launch profiles, in display order; never null. */
    public List<LaunchProfile> launchProfiles() {
        return Collections.unmodifiableList(data.launchProfiles);
    }

    /** Replaces the whole launch-profile list, as committed from the settings dialog. */
    public void setLaunchProfiles(List<LaunchProfile> profiles) {
        data.launchProfiles = new ArrayList<>(profiles);
    }

    /** Whether a preset switch should apply only to the active chat tab instead of every open chat. */
    public boolean activeChatOnly() {
        return data.activeChatOnly;
    }

    public void setActiveChatOnly(boolean activeChatOnly) {
        data.activeChatOnly = activeChatOnly;
    }

    /** The id of the last-opened chat, used to preselect it in the chat picker. */
    public String lastThreadId() {
        return data.lastThreadId;
    }

    public void setLastThreadId(String lastThreadId) {
        data.lastThreadId = lastThreadId;
    }

    /** The preset last applied via {@code switchModel}, defaulting to Terra/Medium if unset or invalid. */
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
        if (data.launchProfiles == null) {
            data.launchProfiles = new ArrayList<>();
        }
        data.launchProfiles.removeIf(profile -> profile == null
                || !LaunchProfile.isValidName(profile.name)
                || !LaunchProfile.isValidArguments(profile.arguments));
    }

    /** Plain serializable state bean for IntelliJ's {@code PersistentStateComponent} XML persistence. */
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
        public String lastThreadId;
        public String lastPresetId = ModelPreset.PresetId.TERRA_MEDIUM.name();
        public boolean activeChatOnly = false;
        public List<LaunchProfile> launchProfiles = new ArrayList<>();
    }
}
