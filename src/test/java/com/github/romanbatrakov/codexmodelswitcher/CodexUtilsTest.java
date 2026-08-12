package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void testTokenizeArguments() {
        assertTrue(CodexUtils.tokenizeArguments(null).isEmpty());
        assertTrue(CodexUtils.tokenizeArguments("").isEmpty());
        assertTrue(CodexUtils.tokenizeArguments("   ").isEmpty());

        assertEquals(List.of("--profile", "or-deepseek-flash"),
                CodexUtils.tokenizeArguments("--profile or-deepseek-flash"));
        assertEquals(List.of("--profile", "or-deepseek-flash"),
                CodexUtils.tokenizeArguments("  --profile   or-deepseek-flash  "));
        assertEquals(List.of("--extra", "some value", "--flag"),
                CodexUtils.tokenizeArguments("--extra \"some value\" --flag"));
        assertEquals(List.of("--extra", "some value"),
                CodexUtils.tokenizeArguments("--extra 'some value'"));
    }
}
