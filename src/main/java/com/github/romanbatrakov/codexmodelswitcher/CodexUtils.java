package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.ide.plugins.cl.PluginAwareClassLoader;
import com.intellij.openapi.extensions.PluginDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small shared helpers: shell quoting, the trusted Codex executable lookup, and error formatting. */
final class CodexUtils {
    private static final List<Path> CODEX_LOCATIONS = List.of(
            Path.of("/opt/homebrew/bin/codex"),
            Path.of("/usr/local/bin/codex")
    );
    private static final Pattern ARGUMENT_TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");

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

    /**
     * Splits a launch-profile arguments string into individual tokens, so
     * each one can be shell-quoted separately instead of collapsing a
     * multi-flag string like "--profile or-deepseek-flash" into one bogus
     * argv item. Intentionally minimal: whitespace-separated tokens, plus
     * "..."/'...' for a single token that needs to contain spaces. No nested
     * quotes, escapes, or shell expansion -- every token is re-quoted via
     * shellQuote before it reaches a command line, so nothing shell-like can
     * leak through regardless of what the tokenizer accepts here.
     */
    static List<String> tokenizeArguments(String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null) {
            return tokens;
        }
        Matcher matcher = ARGUMENT_TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(matcher.group(2));
            } else {
                tokens.add(matcher.group(3));
            }
        }
        return tokens;
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
        ClassLoader loader = CodexUtils.class.getClassLoader();
        if (loader instanceof PluginAwareClassLoader pluginClassLoader) {
            PluginDescriptor plugin = pluginClassLoader.getPluginDescriptor();
            if (plugin.getVersion() != null) {
                return plugin.getVersion();
            }
        }
        return "1.1.0";
    }
}
