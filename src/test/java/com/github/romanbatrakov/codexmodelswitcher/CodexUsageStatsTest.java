package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexUsageStatsTest {
    private static final long BUCKET = CodexUsageStats.BUCKET_MILLIS;
    private static final long NOW = 1_000 * BUCKET;

    private static CodexUsageStats.Sample sample(long timestamp, String thread, long tokens, long cached, String cwd) {
        return new CodexUsageStats.Sample(timestamp, thread, tokens, cached, cwd);
    }

    @Test
    void currentRateIsPerAgentNotSummed() {
        List<CodexUsageStats.Sample> oneAgent = List.of(sample(NOW - 60_000, "a", 5_000, 0, "/p"));
        List<CodexUsageStats.Sample> twoAgents = List.of(
                sample(NOW - 60_000, "a", 5_000, 0, "/p"),
                sample(NOW - 120_000, "b", 5_000, 0, "/p"));

        CodexUsageStats.Usage one = CodexUsageStats.current(oneAgent, NOW, _ -> true);
        CodexUsageStats.Usage two = CodexUsageStats.current(twoAgents, NOW, _ -> true);

        assertEquals(1_000, one.tokensPerMinute(), 0.001);
        assertEquals(1_000, two.tokensPerMinute(), 0.001);
        assertEquals(1, one.agents());
        assertEquals(2, two.agents());
        assertEquals(10_000, two.totalTokens());
    }

    @Test
    void currentRateIgnoresOldSamplesAndSeparatesCache() {
        List<CodexUsageStats.Sample> samples = List.of(
                sample(NOW - 30_000, "a", 5_000, 4_000, "/p"),
                sample(NOW - BUCKET - 1, "a", 100_000, 0, "/p"));
        CodexUsageStats.Usage usage = CodexUsageStats.current(samples, NOW, _ -> true);
        assertEquals(1_000, usage.tokensPerMinute(), 0.001);
        assertEquals(200, usage.nonCachedTokensPerMinute(), 0.001);
        assertEquals(CodexUsageStats.Usage.NONE, CodexUsageStats.current(List.of(), NOW, _ -> true));
    }

    @Test
    void averageIgnoresIdleTimeAndAgentCount() {
        // Agent "a" busy in two separate 5-minute buckets hours apart; agent "b" joins in the second one.
        List<CodexUsageStats.Sample> samples = List.of(
                sample(NOW - 10 * BUCKET + 1_000, "a", 5_000, 0, "/p"),
                sample(NOW - BUCKET + 1_000, "a", 5_000, 0, "/p"),
                sample(NOW - BUCKET + 2_000, "b", 5_000, 0, "/p"));
        CodexUsageStats.Usage average =
                CodexUsageStats.average(samples, NOW, CodexUsageStats.DAY_MILLIS, _ -> true);
        // 15k tokens over 3 agent-buckets of 5 minutes each.
        assertEquals(1_000, average.tokensPerMinute(), 0.001);
        assertEquals(2, average.agents());
        assertEquals(15_000, average.totalTokens());
    }

    @Test
    void filtersByProject() {
        List<CodexUsageStats.Sample> samples = List.of(
                sample(NOW - 1_000, "a", 5_000, 0, "/work/core"),
                sample(NOW - 1_000, "b", 5_000, 0, "/work/other"));
        CodexUsageStats.Usage usage =
                CodexUsageStats.current(samples, NOW, cwd -> CodexUsageStats.belongsTo(cwd, "/work/core"));
        assertEquals(1, usage.agents());
        assertEquals(5_000, usage.totalTokens());

        assertTrue(CodexUsageStats.belongsTo("/work/core/module", "/work/core"));
        assertFalse(CodexUsageStats.belongsTo("/work/core-old", "/work/core"));
        assertFalse(CodexUsageStats.belongsTo(null, "/work/core"));
    }

    @Test
    void formatsNumbersAndLimits() {
        assertEquals("850", CodexUsageStats.formatTokens(850));
        assertEquals("4.2k", CodexUsageStats.formatTokens(4_200));
        assertEquals("1.3M", CodexUsageStats.formatTokens(1_300_000));
        assertEquals("5h", CodexUsageStats.windowLabel(300));
        assertEquals("wk", CodexUsageStats.windowLabel(10_080));
        assertEquals("1d", CodexUsageStats.windowLabel(1_440));

        assertTrue(CodexUsageStats.isCurrent(new CodexUsageParser.RateLimit(300, 9, NOW / 1000 + 60), NOW));
        assertFalse(CodexUsageStats.isCurrent(new CodexUsageParser.RateLimit(300, 9, NOW / 1000 - 60), NOW));
        assertFalse(CodexUsageStats.isCurrent(null, NOW));
    }
}
