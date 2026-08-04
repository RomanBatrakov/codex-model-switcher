package ru.roman.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexServerEndpointTest {

    @Test
    void testValidEndpoints() {
        assertTrue(CodexServerEndpoint.isValid("ws://127.0.0.1:4500"));
        assertTrue(CodexServerEndpoint.isValid("ws://localhost:4500"));
        assertTrue(CodexServerEndpoint.isValid("wss://127.0.0.1:8080"));

        URI uri = CodexServerEndpoint.requireValid("ws://127.0.0.1:4500");
        assertEquals("ws", uri.getScheme());
        assertEquals("127.0.0.1", uri.getHost());
        assertEquals(4500, uri.getPort());
    }

    @Test
    void testInvalidEndpoints() {
        assertFalse(CodexServerEndpoint.isValid("http://127.0.0.1:4500"));
        assertFalse(CodexServerEndpoint.isValid("ws://example.com:4500"));
        assertFalse(CodexServerEndpoint.isValid("ws://192.168.1.1:4500"));
        assertFalse(CodexServerEndpoint.isValid("ws://127.0.0.1:4500/path"));
        assertFalse(CodexServerEndpoint.isValid("ws://user:pass@127.0.0.1:4500"));
        assertFalse(CodexServerEndpoint.isValid("ws://127.0.0.1:70000"));
    }

    @Test
    void testNormalizeOrDefault() {
        assertEquals("ws://127.0.0.1:4500", CodexServerEndpoint.normalizeOrDefault(null));
        assertEquals("ws://127.0.0.1:4500", CodexServerEndpoint.normalizeOrDefault("invalid"));
        assertEquals("ws://localhost:4500", CodexServerEndpoint.normalizeOrDefault("ws://localhost:4500"));
    }

    @Test
    void testRequireValidExceptions() {
        assertThrows(IllegalArgumentException.class, () -> CodexServerEndpoint.requireValid(null));
        assertThrows(IllegalArgumentException.class, () -> CodexServerEndpoint.requireValid("http://127.0.0.1:4500"));
        assertThrows(IllegalArgumentException.class, () -> CodexServerEndpoint.requireValid("ws://8.8.8.8:4500"));
    }
}
