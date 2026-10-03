package com.github.romanbatrakov.codexmodelswitcher;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One configurable model/reasoning-effort preset (Terra/Sol/Luna slots), plus
 * validation for the free-text model name and its model-specific set of
 * reasoning effort values.
 */
record ModelPreset(PresetId id, String label, String model, String effort) {
    private static final int MAX_MODEL_LENGTH = 128;

    /** Every reasoning effort value recognized by the plugin, across all models. */
    static final List<String> EFFORT_OPTIONS = List.of("minimal", "low", "medium", "high", "xhigh", "max", "ultra");

    /**
     * Known models' own supported effort levels -- not every model supports
     * every level (for example "ultra" is Astra/Sol/Terra-only). A model not
     * listed here (a custom name, or one reached through a launch profile
     * such as an OpenRouter model) falls back to the full {@link
     * #EFFORT_OPTIONS} list, since the plugin has no way to know what it
     * actually supports.
     */
    private static final Map<String, List<String>> MODEL_EFFORT_OPTIONS = Map.of(
            "gpt-6.1-sol", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
            "gpt-6-astra", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
            "gpt-6-sol", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
            "gpt-6-luna", List.of("low", "medium", "high", "xhigh", "max"),
            "gpt-5.6-terra", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
            "gpt-5.6-sol", List.of("low", "medium", "high", "xhigh", "max", "ultra"),
            "gpt-5.6-luna", List.of("low", "medium", "high", "xhigh", "max"),
            "gpt-5.5", List.of("low", "medium", "high", "xhigh"));

    enum PresetId {
        TERRA_MEDIUM("Terra / Medium"),
        TERRA_XHIGH("Terra / XHigh"),
        SOL_XHIGH("Sol / XHigh"),
        LUNA_MAX("Luna / Max");

        private final String label;

        PresetId(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    static ModelPreset forId(PresetId id) {
        CodexModelSwitcherSettings settings = CodexModelSwitcherSettings.getInstance();
        return new ModelPreset(id, id.label, settings.model(id), settings.effort(id));
    }

    static String validateModel(String value) {
        if (value == null || value.isBlank()) {
            return "must not be empty.";
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_MODEL_LENGTH) {
            return "is too long (maximum " + MAX_MODEL_LENGTH + " characters).";
        }
        if (!normalized.matches("[A-Za-z0-9._:/~+@-]+")) {
            return "contains invalid characters.";
        }
        return null;
    }

    /** The effort levels {@code model} itself supports, or every known level if the model is unrecognized. */
    static List<String> effortOptionsFor(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return MODEL_EFFORT_OPTIONS.getOrDefault(normalized, EFFORT_OPTIONS);
    }

    static String validateEffort(String model, String value) {
        if (value == null || value.isBlank()) {
            return "must not be empty.";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        List<String> allowed = effortOptionsFor(model);
        return allowed.contains(normalized)
                ? null
                : "must be one of " + String.join(", ", allowed)
                        + (model == null || model.isBlank() ? "." : " for " + model.trim() + ".");
    }

    static boolean isValidModel(String value) {
        return validateModel(value) == null;
    }

    static boolean isValidEffort(String model, String value) {
        return validateEffort(model, value) == null;
    }

    static String requireModel(String value) {
        String normalized = value == null ? "" : value.trim();
        String error = validateModel(normalized);
        if (error != null) {
            throw new IllegalArgumentException("Invalid model name: " + error);
        }
        return normalized;
    }

    static String requireEffort(String model, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        String error = validateEffort(model, normalized);
        if (error != null) {
            throw new IllegalArgumentException("Invalid reasoning effort: " + error);
        }
        return normalized;
    }
}
