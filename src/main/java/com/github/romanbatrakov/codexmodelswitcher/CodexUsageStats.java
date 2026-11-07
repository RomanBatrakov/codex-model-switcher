package com.github.romanbatrakov.codexmodelswitcher;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Token-usage arithmetic for the status bar widget.
 *
 * <p>Rates are per agent, not summed: time is split into 5-minute buckets,
 * and each bucket counts 5 "agent-minutes" for every distinct thread that
 * produced a response in it. Dividing tokens by agent-minutes makes two busy
 * agents read the same as one, and idle time without any agent doesn't drag
 * the averages down. The live rate is the same formula over the last bucket-
 * sized window, so "now" and the 24h/7d/30d averages are directly comparable.
 */
final class CodexUsageStats {
    static final long BUCKET_MILLIS = 5 * 60_000L;
    static final long DAY_MILLIS = 24 * 60 * 60_000L;

    private CodexUsageStats() {
    }

    /** One model response's usage, tagged with the working directory of the session it belongs to. */
    record Sample(long timestampMillis, String threadId, long totalTokens, long cachedInputTokens, String cwd) {
    }

    /** Per-agent rates over some time span, plus raw totals. */
    record Usage(double tokensPerMinute, double nonCachedTokensPerMinute, int agents, long totalTokens,
                 long nonCachedTokens) {
        static final Usage NONE = new Usage(0, 0, 0, 0, 0);
    }

    /** Usage over the last {@link #BUCKET_MILLIS} -- what is happening right now. */
    static Usage current(List<Sample> samples, long now, Predicate<String> cwdFilter) {
        long from = now - BUCKET_MILLIS;
        long total = 0;
        long cached = 0;
        Set<String> agents = new HashSet<>();
        for (Sample sample : samples) {
            if (sample.timestampMillis() > from && sample.timestampMillis() <= now && cwdFilter.test(sample.cwd())) {
                total += sample.totalTokens();
                cached += sample.cachedInputTokens();
                agents.add(sample.threadId());
            }
        }
        if (agents.isEmpty()) {
            return Usage.NONE;
        }
        double agentMinutes = agents.size() * (BUCKET_MILLIS / 60_000.0);
        return new Usage(total / agentMinutes, (total - cached) / agentMinutes, agents.size(), total, total - cached);
    }

    /** Average per-agent usage while agents were active, over the last {@code periodMillis}. */
    static Usage average(List<Sample> samples, long now, long periodMillis, Predicate<String> cwdFilter) {
        return averages(samples, now, new long[]{periodMillis}, cwdFilter)[0];
    }

    /**
     * {@link #average} for several periods at once, in a single pass over the
     * samples -- each result is exactly what {@code average} returns for that
     * period alone.
     */
    static Usage[] averages(List<Sample> samples, long now, long[] periodsMillis, Predicate<String> cwdFilter) {
        int periods = periodsMillis.length;
        long[] from = new long[periods];
        long earliest = now;
        long[] total = new long[periods];
        long[] cached = new long[periods];
        List<Set<String>> agents = new ArrayList<>(periods);
        List<Map<Long, Set<String>>> agentsByBucket = new ArrayList<>(periods);
        for (int i = 0; i < periods; i++) {
            from[i] = now - periodsMillis[i];
            earliest = Math.min(earliest, from[i]);
            agents.add(new HashSet<>());
            agentsByBucket.add(new HashMap<>());
        }
        for (Sample sample : samples) {
            long timestamp = sample.timestampMillis();
            if (timestamp <= earliest || timestamp > now || !cwdFilter.test(sample.cwd())) {
                continue;
            }
            long bucket = Math.floorDiv(timestamp, BUCKET_MILLIS);
            for (int i = 0; i < periods; i++) {
                if (timestamp > from[i]) {
                    total[i] += sample.totalTokens();
                    cached[i] += sample.cachedInputTokens();
                    agents.get(i).add(sample.threadId());
                    agentsByBucket.get(i).computeIfAbsent(bucket, _ -> new HashSet<>()).add(sample.threadId());
                }
            }
        }
        Usage[] result = new Usage[periods];
        for (int i = 0; i < periods; i++) {
            long agentBuckets = agentsByBucket.get(i).values().stream().mapToLong(Set::size).sum();
            if (agentBuckets == 0) {
                result[i] = Usage.NONE;
                continue;
            }
            double agentMinutes = agentBuckets * (BUCKET_MILLIS / 60_000.0);
            result[i] = new Usage(total[i] / agentMinutes, (total[i] - cached[i]) / agentMinutes,
                    agents.get(i).size(), total[i], total[i] - cached[i]);
        }
        return result;
    }

    /** Whether a session's working directory is the project directory or somewhere inside it. */
    static boolean belongsTo(String cwd, String projectPath) {
        return projectFilter(projectPath).test(cwd);
    }

    /**
     * A {@link #belongsTo} check for one project, meant to be applied to many
     * samples: the project path is normalized once, and the answer is cached
     * per distinct working directory -- there are only a handful of those,
     * while there can be tens of thousands of samples.
     */
    static Predicate<String> projectFilter(String projectPath) {
        Path project = normalize(projectPath);
        if (project == null) {
            return _ -> false;
        }
        Map<String, Boolean> cache = new HashMap<>();
        return cwd -> {
            if (cwd == null) {
                return false;
            }
            return cache.computeIfAbsent(cwd, key -> {
                Path path = normalize(key);
                return path != null && path.startsWith(project);
            });
        };
    }

    private static Path normalize(String path) {
        if (path == null) {
            return null;
        }
        try {
            return Path.of(path).normalize();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    /**
     * Share of cached input in all tokens, e.g. {@code 97.6%}: totalTokens
     * already includes the cached input, and nonCachedTokens is total minus
     * cached, so the cached part is their difference. {@code —} when there
     * are no tokens.
     */
    static String formatCachedShare(Usage usage) {
        long total = usage.totalTokens();
        if (total <= 0) {
            return "—";
        }
        // The parser already keeps cached within [0, total]; the clamp only
        // guarantees the printed share can never leave 0-100%.
        long cached = Math.clamp(total - usage.nonCachedTokens(), 0, total);
        return String.format(Locale.ROOT, "%.1f%%", cached * 100.0 / total);
    }

    /**
     * Compact token count: 850, 4.2k, 1.3M, 2.1B. Thresholds sit where
     * rounding would otherwise print {@code 1000.0k} or {@code 1000.0M}.
     */
    static String formatTokens(double value) {
        if (value < 999.5) {
            return String.valueOf(Math.round(value));
        }
        if (value < 999_950) {
            return String.format(Locale.ROOT, "%.1fk", value / 1_000);
        }
        if (value < 999_950_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000);
        }
        return String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000);
    }
}
