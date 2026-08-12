package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPresetTest {

    @Test
    void testValidateModel() {
        assertNull(ModelPreset.validateModel("gpt-5.6-terra"));
        assertNull(ModelPreset.validateModel("gpt-4o_v1"));
        assertTrue(ModelPreset.isValidModel("gpt-5.6-terra"));

        assertFalse(ModelPreset.isValidModel(null));
        assertFalse(ModelPreset.isValidModel(""));
        assertFalse(ModelPreset.isValidModel("model with spaces"));
        assertFalse(ModelPreset.isValidModel("model;injection"));
    }

    @Test
    void testValidateEffort() {
        assertNull(ModelPreset.validateEffort("minimal"));
        assertNull(ModelPreset.validateEffort("low"));
        assertNull(ModelPreset.validateEffort("medium"));
        assertNull(ModelPreset.validateEffort("high"));
        assertNull(ModelPreset.validateEffort("xhigh"));
        assertNull(ModelPreset.validateEffort("max"));

        assertTrue(ModelPreset.isValidEffort("MEDIUM"));
        assertFalse(ModelPreset.isValidEffort("extreme"));
        assertFalse(ModelPreset.isValidEffort(null));
    }

    @Test
    void testRequireModelAndEffort() {
        assertEquals("gpt-5.6-sol", ModelPreset.requireModel(" gpt-5.6-sol "));
        assertEquals("xhigh", ModelPreset.requireEffort(" XHIGH "));

        assertThrows(IllegalArgumentException.class, () -> ModelPreset.requireModel("bad model!"));
        assertThrows(IllegalArgumentException.class, () -> ModelPreset.requireEffort("invalid_effort"));
    }
}
