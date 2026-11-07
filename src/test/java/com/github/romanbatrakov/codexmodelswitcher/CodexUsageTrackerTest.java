package com.github.romanbatrakov.codexmodelswitcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexUsageTrackerTest {
    private static final String USAGE = """
            {"timestamp":"2026-11-07T17:17:57.155Z","type":"token_usage_record","payload":{\
            "thread_id":"123e4567-e89b-12d3-a456-426614174000","usage":{"cached_input_tokens":100,\
            "total_tokens":1000}}}""";

    @Test
    void readsOnlyCompleteRelevantLines(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("session.jsonl");
        // A session_meta line larger than 64 KB is still read: its type is near the start.
        String meta = "{\"timestamp\":\"2026-11-07T17:04:44Z\",\"type\":\"session_meta\",\"payload\":{"
                + "\"cwd\":\"/work/core\",\"instructions\":\"" + "x".repeat(100_000) + "\"}}";
        // A 300 KB tool output is skipped without being parsed.
        String toolOutput = "{\"timestamp\":\"2026-11-07T17:05:00Z\",\"type\":\"response_item\",\"payload\":"
                + "{\"output\":\"" + "y".repeat(300_000) + "\"}}";
        String partial = USAGE.substring(0, 40);
        Files.writeString(file, meta + "\n" + toolOutput + "\n" + USAGE + "\n" + partial, StandardCharsets.UTF_8);

        CodexUsageTracker.FileState state = new CodexUsageTracker.FileState();
        CodexUsageTracker.readAppended(file, state);
        assertEquals("/work/core", state.cwd);
        assertEquals(1, state.events.size());
        assertEquals(1000, state.events.getFirst().totalTokens());
        // The unfinished last line is left for the next read.
        assertEquals(Files.size(file) - partial.length(), state.offset);

        Files.writeString(file, USAGE.substring(40) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        CodexUsageTracker.readAppended(file, state);
        assertEquals(2, state.events.size());
        assertEquals(Files.size(file), state.offset);
    }
}
