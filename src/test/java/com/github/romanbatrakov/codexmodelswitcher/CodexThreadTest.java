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

    /** The same steps the chat picker runs: normalize the query once, match each chat's search text. */
    private static boolean search(CodexThread thread, String query) {
        return CodexThread.matches(thread.searchText(), CodexThread.normalizeQuery(query));
    }

    @Test
    void testSearch() {
        CodexThread thread = new CodexThread("123e4567-e89b-12d3-a456-426614174000", "Fix authentication issue", "User reported login bug", 0L);

        assertTrue(search(thread, "auth"));
        assertTrue(search(thread, "LOGIN"));
        assertTrue(search(thread, ""));
        assertTrue(search(thread, "  login bug "));
        assertFalse(search(thread, "database"));
        // A match must come from one field, never across the end of the name and the start of the preview.
        assertFalse(search(thread, "issue user"));
        CodexThread empty = new CodexThread("123e4567-e89b-12d3-a456-426614174000", null, null, 0L);
        assertTrue(search(empty, "  "));
        assertFalse(search(empty, "fix"));
        assertTrue(search(thread, null));
    }
}
