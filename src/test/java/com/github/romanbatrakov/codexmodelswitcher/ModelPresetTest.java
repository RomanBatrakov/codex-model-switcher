package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void testValidateEffortForUnknownModelAllowsEverything() {
        for (String effort : ModelPreset.EFFORT_OPTIONS) {
            assertNull(ModelPreset.validateEffort("some-custom-openrouter-model", effort));
        }
        assertTrue(ModelPreset.isValidEffort("some-custom-openrouter-model", "MEDIUM"));
        assertFalse(ModelPreset.isValidEffort("some-custom-openrouter-model", "extreme"));
        assertFalse(ModelPreset.isValidEffort("some-custom-openrouter-model", null));
    }

    @Test
    void testValidateEffortRestrictedPerKnownModel() {
        // Astra/Sol (both generations) and Terra support the full range, including "ultra".
        assertNull(ModelPreset.validateEffort("gpt-6-astra", "ultra"));
        assertNull(ModelPreset.validateEffort("gpt-6.1-sol", "ultra"));
        assertNull(ModelPreset.validateEffort("gpt-6-sol", "ultra"));
        assertNull(ModelPreset.validateEffort("gpt-5.6-sol", "ultra"));
        assertNull(ModelPreset.validateEffort("gpt-5.6-terra", "ultra"));

        // Luna (both generations) supports up to "max", but not "ultra".
        assertNull(ModelPreset.validateEffort("gpt-6-luna", "max"));
        assertFalse(ModelPreset.isValidEffort("gpt-6-luna", "ultra"));
        assertNull(ModelPreset.validateEffort("gpt-5.6-luna", "max"));
        assertFalse(ModelPreset.isValidEffort("gpt-5.6-luna", "ultra"));

        // gpt-5.5 stops at "xhigh".
        assertNull(ModelPreset.validateEffort("gpt-5.5", "xhigh"));
        assertFalse(ModelPreset.isValidEffort("gpt-5.5", "max"));
    }

    @Test
    void testEffortOptionsFor() {
        assertEquals(
                List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                ModelPreset.effortOptionsFor("gpt-6-astra"));
        assertEquals(
                List.of("low", "medium", "high", "xhigh", "max", "ultra"),
                ModelPreset.effortOptionsFor("gpt-6-sol"));
        assertEquals(
                List.of("low", "medium", "high", "xhigh", "max"),
                ModelPreset.effortOptionsFor("gpt-6-luna"));
        assertEquals(
                List.of("low", "medium", "high", "xhigh", "max"),
                ModelPreset.effortOptionsFor("gpt-5.6-luna"));
        assertEquals(
                List.of("low", "medium", "high", "xhigh"),
                ModelPreset.effortOptionsFor("gpt-5.5"));
        assertEquals(ModelPreset.EFFORT_OPTIONS, ModelPreset.effortOptionsFor("unknown-model"));
        assertEquals(ModelPreset.EFFORT_OPTIONS, ModelPreset.effortOptionsFor(null));
    }

    @Test
    void testRequireModelAndEffort() {
        assertEquals("gpt-5.6-sol", ModelPreset.requireModel(" gpt-5.6-sol "));
        assertEquals("xhigh", ModelPreset.requireEffort("gpt-5.6-sol", " XHIGH "));

        assertThrows(IllegalArgumentException.class, () -> ModelPreset.requireModel("bad model!"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelPreset.requireEffort("gpt-5.6-sol", "invalid_effort"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelPreset.requireEffort("gpt-5.5", "ultra"));
    }
}
