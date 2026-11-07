package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodexUsageParserTest {
    private static final String USAGE = """
            {"timestamp":"2026-11-07T17:17:57.155Z","ordinal":18,"type":"token_usage_record","payload":{\
            "thread_id":"123e4567-e89b-12d3-a456-426614174000","usage":{"input_tokens":18090,\
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
        assertEquals("123e4567-e89b-12d3-a456-426614174000", event.threadId());
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
    void parsesSessionCwd() {
        String meta = "{\"timestamp\":\"2026-11-07T17:04:44Z\",\"type\":\"session_meta\","
                + "\"payload\":{\"id\":\"x\",\"cwd\":\"/Users/me/IdeaProjects/core\"}}";
        assertEquals("/Users/me/IdeaProjects/core", CodexUsageParser.parseSessionCwd(meta));
        assertNull(CodexUsageParser.parseSessionCwd(USAGE));
    }
}
