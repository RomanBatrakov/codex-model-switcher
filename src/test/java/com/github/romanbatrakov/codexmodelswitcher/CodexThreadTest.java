package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexThreadTest {

    @Test
    void testIsValidId() {
        assertTrue(CodexThread.isValidId("123e4567-e89b-12d3-a456-426614174000"));
        assertTrue(CodexThread.isValidId("00000000-0000-0000-0000-000000000000"));

        assertFalse(CodexThread.isValidId("invalid-uuid"));
        assertFalse(CodexThread.isValidId("123e4567-e89b-12d3-a456"));
        assertFalse(CodexThread.isValidId(null));
    }

    @Test
    void testShortId() {
        CodexThread thread = new CodexThread("123e4567-e89b-12d3-a456-426614174000", "Test", "Preview", 0L);
        assertEquals("123e4567", thread.shortId());
    }

    @Test
    void testMatchesSearch() {
        CodexThread thread = new CodexThread("123e4567-e89b-12d3-a456-426614174000", "Fix authentication issue", "User reported login bug", 0L);

        assertTrue(thread.matchesSearch("auth"));
        assertTrue(thread.matchesSearch("LOGIN"));
        assertTrue(thread.matchesSearch(""));
        assertFalse(thread.matchesSearch("database"));
    }
}
