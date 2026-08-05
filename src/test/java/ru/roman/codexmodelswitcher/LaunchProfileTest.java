package ru.roman.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchProfileTest {

    @Test
    void testValidateName() {
        assertNull(LaunchProfile.validateName("or-deepseek-flash"));
        assertTrue(LaunchProfile.isValidName("Custom Profile"));

        assertFalse(LaunchProfile.isValidName(null));
        assertFalse(LaunchProfile.isValidName(""));
        assertFalse(LaunchProfile.isValidName(" "));
        assertFalse(LaunchProfile.isValidName("x".repeat(LaunchProfile.MAX_NAME_LENGTH + 1)));
    }

    @Test
    void testValidateArguments() {
        assertNull(LaunchProfile.validateArguments("--profile or-deepseek-flash"));
        assertNull(LaunchProfile.validateArguments("--extra \"some value\" --flag"));
        assertTrue(LaunchProfile.isValidArguments("--profile or-deepseek-flash"));

        assertFalse(LaunchProfile.isValidArguments(null));
        assertFalse(LaunchProfile.isValidArguments(""));
        assertFalse(LaunchProfile.isValidArguments("--profile $(rm -rf /)"));
        assertFalse(LaunchProfile.isValidArguments("--profile foo;bar"));
        assertFalse(LaunchProfile.isValidArguments("x".repeat(LaunchProfile.MAX_ARGUMENTS_LENGTH + 1)));
    }

    @Test
    void testRequireNameAndArguments() {
        assertEquals("or-deepseek-flash", LaunchProfile.requireName(" or-deepseek-flash "));
        assertEquals("--profile or-deepseek-flash", LaunchProfile.requireArguments(" --profile or-deepseek-flash "));

        assertThrows(IllegalArgumentException.class, () -> LaunchProfile.requireName(""));
        assertThrows(IllegalArgumentException.class, () -> LaunchProfile.requireArguments("bad;arg"));
    }
}
