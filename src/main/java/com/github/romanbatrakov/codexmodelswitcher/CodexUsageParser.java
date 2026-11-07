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

    /** One rate-limit window, e.g. the 5-hour or the weekly limit. */
    record RateLimit(int windowMinutes, double usedPercent, long resetsAtEpochSeconds) {
    }

    /** The primary and secondary limits as last reported by Codex; either may be null. */
    record RateLimits(long timestampMillis, RateLimit primary, RateLimit secondary) {
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

    /** Parses the {@code rate_limits} part of a {@code token_count} event. */
    static RateLimits parseRateLimits(String line) {
        if (!line.contains("\"token_count\"") || !line.contains("\"rate_limits\"")) {
            return null;
        }
        JsonObject record = parse(line);
        JsonObject payload = object(record, "payload");
        if (payload == null || !"token_count".equals(string(payload, "type"))) {
            return null;
        }
        Long timestamp = timestamp(record);
        JsonObject limits = object(payload, "rate_limits");
        if (timestamp == null || limits == null) {
            return null;
        }
        RateLimit primary = rateLimit(object(limits, "primary"));
        RateLimit secondary = rateLimit(object(limits, "secondary"));
        return primary == null && secondary == null ? null : new RateLimits(timestamp, primary, secondary);
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

    private static RateLimit rateLimit(JsonObject value) {
        if (value == null || !value.has("used_percent")) {
            return null;
        }
        int window = (int) number(value, "window_minutes");
        double used;
        try {
            used = value.get("used_percent").getAsDouble();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (window <= 0 || used < 0) {
            return null;
        }
        return new RateLimit(window, Math.min(used, 100), number(value, "resets_at"));
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
