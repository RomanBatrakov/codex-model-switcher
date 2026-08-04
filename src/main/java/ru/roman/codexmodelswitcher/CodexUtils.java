package ru.roman.codexmodelswitcher;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.extensions.PluginDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

final class CodexUtils {
    private static final List<Path> CODEX_LOCATIONS = List.of(
            Path.of("/opt/homebrew/bin/codex"),
            Path.of("/usr/local/bin/codex")
    );

    private CodexUtils() {
    }

    static Path codexExecutable() throws IOException {
        return CODEX_LOCATIONS.stream()
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "Could not find a trusted Codex executable at /opt/homebrew/bin/codex or /usr/local/bin/codex."));
    }

    static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static String userMessage(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500) + "…";
    }

    static String pluginVersion() {
        PluginId pluginId = PluginId.getId("ru.roman.codexmodelswitcher");
        PluginDescriptor plugin = PluginManagerCore.getPlugin(pluginId);
        return plugin != null && plugin.getVersion() != null ? plugin.getVersion() : "0.8.0";
    }
}
