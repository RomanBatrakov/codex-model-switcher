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
        String normalizedQuery = normalizeQuery(query);
        return normalizedQuery.isEmpty() || searchText().contains(normalizedQuery);
    }

    /** The search query in the form {@link #searchText()} is matched against. */
    static String normalizeQuery(String query) {
        return query == null ? "" : query.toLowerCase(Locale.ROOT).strip();
    }

    /** Lower-cased name and preview, for repeated matching against {@link #normalizeQuery(String) queries}. */
    String searchText() {
        // The search field is single-line, so a query never contains the separator
        // and a match can never span the end of the name and the start of the preview.
        return (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "\n"
                + (preview == null ? "" : preview.toLowerCase(Locale.ROOT));
    }

    String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    static boolean isValidId(String value) {
        return value != null && value.matches(UUID_PATTERN);
    }
}
