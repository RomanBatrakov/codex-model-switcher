package ru.roman.codexmodelswitcher;

record ModelPreset(PresetId id, String label, String model, String effort) {
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
}
