package com.github.romanbatrakov.codexmodelswitcher;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Collects Codex token usage from the local session transcripts under
 * {@code ~/.codex/sessions/YYYY/MM/DD/*.jsonl} (or {@code $CODEX_HOME}).
 * Local files only -- no network, no app-server connection.
 *
 * <p>The first refresh reads the last {@link #HISTORY_DAYS} days once;
 * every later refresh only reads the bytes appended since, so the 30-second
 * tick stays cheap even with hundreds of megabytes of transcripts.
 */
@Service(Service.Level.APP)
public final class CodexUsageTracker implements Disposable {
    static final int HISTORY_DAYS = 31;
    private static final long REFRESH_SECONDS = 30;
    private static final int MAX_FILES_PER_DAY = 1_000;
    private static final int MAX_LINE_BYTES = 4 * 1024 * 1024;
    // Lines this large are tool outputs and the like; the records we want are
    // small and name their type near the very start, so only peek at a prefix.
    private static final int SMALL_LINE_BYTES = 64 * 1024;
    private static final int TYPE_PREFIX_BYTES = 512;
    private static final List<String> TYPE_MARKERS = List.of("\"token_usage_record\"", "\"session_meta\"");

    // Guards `files` only. Deliberately separate from `this` (listener/schedule
    // bookkeeping): the first refresh can take seconds, and widgets register
    // listeners from the UI thread -- sharing one lock would freeze the IDE.
    private final Object refreshLock = new Object();
    private final Map<Path, FileState> files = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private ScheduledFuture<?> refreshTask;

    /** An immutable view of everything collected so far. */
    record Snapshot(List<CodexUsageStats.Sample> samples, boolean hasSessions) {
        static final Snapshot EMPTY = new Snapshot(List.of(), false);
    }

    /** Returns the application-level singleton instance. */
    public static CodexUsageTracker getInstance() {
        return ApplicationManager.getApplication().getService(CodexUsageTracker.class);
    }

    Snapshot snapshot() {
        return snapshot;
    }

    /** Registers a callback run (on a background thread) after every refresh; starts refreshing on the first one. */
    synchronized void addListener(Runnable listener) {
        listeners.add(listener);
        if (refreshTask == null) {
            refreshTask = AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay(this::refreshSafely, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Unregisters a callback; stops refreshing once nobody is listening. */
    synchronized void removeListener(Runnable listener) {
        listeners.remove(listener);
        if (listeners.isEmpty() && refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException ignored) {
            // An exception would silently cancel the fixed-delay schedule; the
            // widget would freeze on stale numbers. Skip this tick instead.
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private void refresh() {
        synchronized (refreshLock) {
            refreshLocked();
        }
    }

    private void refreshLocked() {
        Path root = CodexAppServerClient.codexSessionsDirectory();
        long cutoff = System.currentTimeMillis() - HISTORY_DAYS * CodexUsageStats.DAY_MILLIS;
        Set<Path> current = new HashSet<>();
        // A day of slack on both ends: directory dates are local time while
        // record timestamps are UTC, and a long session keeps appending to the
        // file in the directory of the day it started.
        LocalDate today = LocalDate.now();
        for (LocalDate date = today.minusDays(HISTORY_DAYS); !date.isAfter(today.plusDays(1)); date = date.plusDays(1)) {
            Path directory = root.resolve(String.format(Locale.ROOT, "%04d/%02d/%02d",
                    date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var paths = Files.list(directory)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                     .limit(MAX_FILES_PER_DAY)
                     .forEach(current::add);
            } catch (IOException | RuntimeException ignored) {
                // Unreadable directory: count it as empty for this tick.
            }
        }
        files.keySet().retainAll(current);
        List<CodexUsageStats.Sample> samples = new ArrayList<>();
        for (Path path : current) {
            FileState state = files.computeIfAbsent(path, _ -> new FileState());
            try {
                readAppended(path, state);
            } catch (IOException ignored) {
                // The file may be mid-rotation or briefly locked; retry next tick.
            }
            state.events.removeIf(event -> event.timestampMillis() < cutoff);
            for (CodexUsageParser.UsageEvent event : state.events) {
                samples.add(new CodexUsageStats.Sample(event.timestampMillis(), event.threadId(),
                        event.totalTokens(), event.cachedInputTokens(), state.cwd));
            }
        }
        snapshot = new Snapshot(List.copyOf(samples), !current.isEmpty());
    }

    /** Reads only the complete lines appended since the last call, remembering the byte offset. */
    private static void readAppended(Path path, FileState state) throws IOException {
        long size = Files.size(path);
        if (size < state.offset) {
            state.reset();
        }
        if (size == state.offset) {
            return;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(state.offset);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            LineBuffer line = new LineBuffer();
            boolean skipping = false;
            long position = state.offset;
            long committed = state.offset;
            while (channel.read(buffer) > 0) {
                byte[] bytes = buffer.array();
                int length = buffer.position();
                int start = 0;
                for (int index = 0; index < length; index++) {
                    if (bytes[index] != '\n') {
                        continue;
                    }
                    if (!skipping) {
                        line.write(bytes, start, index - start);
                        processLine(line, state);
                    }
                    line.reset();
                    skipping = false;
                    start = index + 1;
                    committed = position + index + 1;
                }
                if (!skipping) {
                    line.write(bytes, start, length - start);
                    if (line.size() > MAX_LINE_BYTES) {
                        // Never hold a runaway line in memory; drop it up to its newline.
                        line.reset();
                        skipping = true;
                    }
                }
                position += length;
                buffer.clear();
            }
            // A trailing line without '\n' is still being written; read it next time.
            state.offset = committed;
        }
    }

    private static void processLine(LineBuffer bytes, FileState state) {
        if (bytes.size() > SMALL_LINE_BYTES) {
            String head = new String(bytes.buffer(), 0, TYPE_PREFIX_BYTES, StandardCharsets.UTF_8);
            if (TYPE_MARKERS.stream().noneMatch(head::contains)) {
                return;
            }
        }
        String line = bytes.toString(StandardCharsets.UTF_8);
        CodexUsageParser.UsageEvent usage = CodexUsageParser.parseUsage(line);
        if (usage != null) {
            state.events.add(usage);
            return;
        }
        String cwd = CodexUsageParser.parseSessionCwd(line);
        if (cwd != null) {
            state.cwd = cwd;
        }
    }

    /** Stops the refresh schedule when the plugin is unloaded or the IDE shuts down. */
    @Override
    public synchronized void dispose() {
        listeners.clear();
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }

    /** A line accumulator whose prefix can be inspected without copying the whole line. */
    private static final class LineBuffer extends ByteArrayOutputStream {
        private byte[] buffer() {
            return buf;
        }
    }

    /** What has been read from one transcript file so far. */
    private static final class FileState {
        private long offset;
        private String cwd;
        private final List<CodexUsageParser.UsageEvent> events = new ArrayList<>();

        private void reset() {
            offset = 0;
            cwd = null;
            events.clear();
        }
    }
}
