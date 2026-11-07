package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodexUsageParserTest {
    private static final String USAGE = """
            {"timestamp":"2026-11-07T17:17:57.155Z","ordinal":18,"type":"token_usage_record","payload":{\
            "thread_id":"01a1b6f8-3eb5-7c71-9bec-20b14eb1215b","usage":{"input_tokens":18090,\
            "cached_input_tokens":13184,"output_tokens":189,"total_tokens":18279}}}""";

    private static final String TOKEN_COUNT = """
            {"timestamp":"2026-11-07T17:17:59.377Z","type":"event_msg","payload":{"type":"token_count",\
            "info":{},"rate_limits":{"primary":{"used_percent":9.0,"window_minutes":300,"resets_at":1794088585},\
            "secondary":{"used_percent":36.0,"window_minutes":10080,"resets_at":1794501473}}}}""";

    @Test
    void parsesUsageRecord() {
        CodexUsageParser.UsageEvent event = CodexUsageParser.parseUsage(USAGE);
        assertNotNull(event);
        assertEquals(Instant.parse("2026-11-07T17:17:57.155Z").toEpochMilli(), event.timestampMillis());
        assertEquals("01a1b6f8-3eb5-7c71-9bec-20b14eb1215b", event.threadId());
        assertEquals(18279, event.totalTokens());
        assertEquals(13184, event.cachedInputTokens());
    }

    @Test
    void ignoresOtherAndBrokenLines() {
        assertNull(CodexUsageParser.parseUsage(TOKEN_COUNT));
        assertNull(CodexUsageParser.parseUsage("{\"type\":\"token_usage_record\""));
        assertNull(CodexUsageParser.parseUsage("not json \"token_usage_record\""));
        assertNull(CodexUsageParser.parseUsage(
                "{\"timestamp\":\"2026-11-07T17:17:57Z\",\"type\":\"token_usage_record\",\"payload\":{}}"));
    }

    @Test
    void parsesRateLimits() {
        CodexUsageParser.RateLimits limits = CodexUsageParser.parseRateLimits(TOKEN_COUNT);
        assertNotNull(limits);
        assertEquals(300, limits.primary().windowMinutes());
        assertEquals(9.0, limits.primary().usedPercent());
        assertEquals(10080, limits.secondary().windowMinutes());
        assertEquals(36.0, limits.secondary().usedPercent());
        assertEquals(1794501473L, limits.secondary().resetsAtEpochSeconds());
        assertNull(CodexUsageParser.parseRateLimits(USAGE));
    }

    @Test
    void parsesSessionCwd() {
        String meta = "{\"timestamp\":\"2026-11-07T17:04:44Z\",\"type\":\"session_meta\","
                + "\"payload\":{\"id\":\"x\",\"cwd\":\"/Users/me/IdeaProjects/core\"}}";
        assertEquals("/Users/me/IdeaProjects/core", CodexUsageParser.parseSessionCwd(meta));
        assertNull(CodexUsageParser.parseSessionCwd(USAGE));
    }
}
