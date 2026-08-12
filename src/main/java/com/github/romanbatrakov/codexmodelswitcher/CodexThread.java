package com.github.romanbatrakov.codexmodelswitcher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A saved Codex chat, as read from a local session transcript or reported by
 * the app server. {@code name}/{@code preview} may both be null when a
 * session file has no readable first message.
 */
record CodexThread(String id, String name, String preview, long updatedAt) {
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());

    String displayName() {
        String headline = name == null || name.isBlank() ? preview : name;
        if (headline == null || headline.isBlank()) {
            headline = "Untitled chat";
        }
        headline = headline.replaceAll("\\s+", " ").strip();
        if (headline.length() > 82) {
            headline = headline.substring(0, 79) + "...";
        }
        String timestamp = updatedAt == 0L ? "no date" : TIME_FORMAT.format(Instant.ofEpochSecond(updatedAt));
        return headline + "  —  " + timestamp;
    }

    boolean matchesSearch(String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT).strip();
        return normalizedQuery.isEmpty()
                || matches(name, normalizedQuery)
                || matches(preview, normalizedQuery);
    }

    String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    static boolean isValidId(String value) {
        return value != null && value.matches(UUID_PATTERN);
    }

    private static boolean matches(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }
}
