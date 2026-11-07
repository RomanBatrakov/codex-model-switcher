package com.github.romanbatrakov.codexmodelswitcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Extracts the few records the usage widget needs from Codex session
 * transcript (JSONL) lines. The format is Codex's own and undocumented, so
 * every field is optional here: anything unexpected yields null instead of
 * an exception.
 */
final class CodexUsageParser {
    private CodexUsageParser() {
    }

    /** Token usage of one model response. */
    record UsageEvent(long timestampMillis, String threadId, long totalTokens, long cachedInputTokens) {
    }

    /** Parses a {@code token_usage_record} line, the one record Codex writes per model response. */
    static UsageEvent parseUsage(String line) {
        // Cheap pre-check: most lines are large tool outputs that are never worth parsing.
        if (!line.contains("\"token_usage_record\"")) {
            return null;
        }
        JsonObject record = parse(line);
        if (record == null || !"token_usage_record".equals(string(record, "type"))) {
            return null;
        }
        Long timestamp = timestamp(record);
        JsonObject payload = object(record, "payload");
        JsonObject usage = object(payload, "usage");
        String threadId = string(payload, "thread_id");
        if (timestamp == null || usage == null || threadId == null || threadId.isBlank()) {
            return null;
        }
        long total = number(usage, "total_tokens");
        if (total <= 0) {
            return null;
        }
        long cached = Math.min(total, Math.max(0, number(usage, "cached_input_tokens")));
        return new UsageEvent(timestamp, threadId, total, cached);
    }

    /** The working directory of a {@code session_meta} line, i.e. the project the session belongs to. */
    static String parseSessionCwd(String line) {
        if (!line.contains("\"session_meta\"")) {
            return null;
        }
        JsonObject record = parse(line);
        if (record == null || !"session_meta".equals(string(record, "type"))) {
            return null;
        }
        String cwd = string(object(record, "payload"), "cwd");
        return cwd == null || cwd.isBlank() ? null : cwd;
    }

    private static JsonObject parse(String line) {
        try {
            JsonElement element = JsonParser.parseString(line);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Long timestamp(JsonObject record) {
        String value = string(record, "timestamp");
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static JsonObject object(JsonObject value, String name) {
        return value != null && value.has(name) && value.get(name).isJsonObject() ? value.getAsJsonObject(name) : null;
    }

    private static String string(JsonObject value, String name) {
        if (value == null || !value.has(name) || !value.get(name).isJsonPrimitive()) {
            return null;
        }
        return value.get(name).getAsString();
    }

    private static long number(JsonObject value, String name) {
        if (value == null || !value.has(name) || !value.get(name).isJsonPrimitive()) {
            return 0;
        }
        try {
            return value.get(name).getAsLong();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
