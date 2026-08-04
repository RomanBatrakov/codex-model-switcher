package ru.roman.codexmodelswitcher;

import java.util.List;
import java.util.Set;

record ModelPreset(PresetId id, String label, String model, String effort) {
    private static final int MAX_MODEL_LENGTH = 128;
    static final List<String> EFFORT_OPTIONS = List.of("minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> ALLOWED_EFFORTS = Set.copyOf(EFFORT_OPTIONS);

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
            return "не может быть пустой.";
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_MODEL_LENGTH) {
            return "слишком длинное имя (максимум " + MAX_MODEL_LENGTH + " символов).";
        }
        if (!normalized.matches("[A-Za-z0-9._:/~+@-]+")) {
            return "содержит недопустимые символы.";
        }
        return null;
    }

    static String validateEffort(String value) {
        if (value == null || value.isBlank()) {
            return "не может быть пустым.";
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return ALLOWED_EFFORTS.contains(normalized)
                ? null
                : "допустимы minimal, low, medium, high, xhigh или max.";
    }

    static boolean isValidModel(String value) {
        return validateModel(value) == null;
    }

    static boolean isValidEffort(String value) {
        return validateEffort(value) == null;
    }

    static String requireModel(String value) {
        String normalized = value == null ? "" : value.trim();
        String error = validateModel(normalized);
        if (error != null) {
            throw new IllegalArgumentException("Некорректное имя модели: " + error);
        }
        return normalized;
    }

    static String requireEffort(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        String error = validateEffort(normalized);
        if (error != null) {
            throw new IllegalArgumentException("Некорректный уровень reasoning: " + error);
        }
        return normalized;
    }
}
