package ru.roman.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CodexUtilsTest {

    @Test
    void testShellQuote() {
        assertEquals("'hello'", CodexUtils.shellQuote("hello"));
        assertEquals("''\"'\"'test'\"'\"''", CodexUtils.shellQuote("'test'"));
        assertEquals("''", CodexUtils.shellQuote(null));
    }

    @Test
    void testUserMessage() {
        Throwable error = new CompletionException(new IllegalArgumentException("Invalid input\nwith newlines\t"));
        assertEquals("Invalid input with newlines", CodexUtils.userMessage(error));

        Throwable emptyError = new RuntimeException();
        assertEquals("RuntimeException", CodexUtils.userMessage(emptyError));
    }

    @Test
    void testPluginVersion() {
        assertNotNull(CodexUtils.pluginVersion());
    }
}
