package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A compact summary of a set of non-negative integer measurements — the shape a {@code model profile}
 * reports for every complexity axis (rule node-count, condition depth, boolean fan-out, reference
 * fan-in, repeatable-levels-crossed, …). Carries the <b>tail</b>, not just the average: a model with one
 * monster rule among fifty trivial ones is a different eval than fifty medium rules, and the interpreter
 * cares about the worst case, so {@code p90}/{@code max} sit next to {@code mean}/{@code median}, and a
 * log-scaled {@code buckets} map keeps the distribution's silhouette visible without leaking any value.
 *
 * <p>Percentiles use the nearest-rank method over the ascending-sorted samples.
 */
record Distribution(int n, int min, int max, double mean, int median, int p90, Map<String, Integer> buckets) {

    private static final Distribution EMPTY = new Distribution(0, 0, 0, 0.0, 0, 0, Map.of());

    /**
     * {@code Map.copyOf} deliberately randomizes its iteration order PER JVM LAUNCH (a documented anti-
     * reliance safeguard in {@code java.util.ImmutableCollections} — stable within one process, different
     * across separate invocations). The synthesis samplers iterate {@code buckets()} while consuming a
     * seeded {@link java.util.Random}, so that per-launch shuffle silently
     * changed which numeric values a bucket's share drew — the root cause of the {@code ModelSynthesizer}
     * cross-invocation nondeterminism. Sorting into a
     * {@link java.util.TreeMap} by each bucket's numeric lower bound makes the stored order deterministic
     * regardless of what order (or map implementation) the caller passed in — immune to a future call site
     * building the map via an unordered collection, not just today's known callers.
     */
    public Distribution {
        Objects.requireNonNull(buckets, "buckets");
        if (n < 0 || min < 0 || max < 0 || mean < 0 || median < 0 || p90 < 0) {
            throw new IllegalArgumentException("distribution values must be non-negative");
        }
        int bucketCount = buckets.values().stream().mapToInt(Integer::intValue).sum();
        if (bucketCount != n) {
            throw new IllegalArgumentException(
                    "distribution bucket total " + bucketCount + " does not match n " + n);
        }
        buckets = java.util.Collections.unmodifiableMap(sortedByBucketValue(buckets));
    }

    private static Map<String, Integer> sortedByBucketValue(Map<String, Integer> buckets) {
        Map<String, Integer> sorted = new java.util.TreeMap<>(Comparator.comparingLong(Distribution::bucketLowerBound));
        sorted.putAll(buckets);
        return sorted;
    }

    /** The lower bound of a bucket label ({@code "0"}, {@code "1"}, or {@code "lo-hi"}) — strictly increasing
     *  across the labels {@link #bucketLabel} produces, so it is safe as a total order for a {@link java.util.TreeMap}. */
    private static long bucketLowerBound(String label) {
        int dash = label.indexOf('-');
        try {
            return dash < 0 ? Long.parseLong(label) : Long.parseLong(label.substring(0, dash));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * The fraction of {@code d}'s mass OUTSIDE its {@code "1"} bucket — how often an axis exceeds its default
     * value, which is the question each synthesizer asks of its own axis (nested aggregates, multi-way hubs,
     * work and scope capacity). Returns {@code fallback} when the axis is absent (an older profile) or empty,
     * because that default differs per axis.
     */
    static double nonTrivialFraction(Distribution d, double fallback) {
        if (d == null || d.n() == 0 || d.buckets().isEmpty()) {
            return fallback;
        }
        int nonTrivial = 0;
        for (var e : d.buckets().entrySet()) {
            if (!"1".equals(e.getKey())) {
                nonTrivial += e.getValue();
            }
        }
        return (double) nonTrivial / d.n();
    }

    /** The empty distribution — no samples. */
    public static Distribution empty() {
        return EMPTY;
    }

    /** Summarize {@code values} (order-independent; copied and sorted internally). */
    public static Distribution of(List<Integer> values) {
        if (values.isEmpty()) {
            return EMPTY;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int n = sorted.size();
        long sum = 0;
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (int v : sorted) {
            sum += v;
            buckets.merge(bucketLabel(v), 1, Integer::sum);
        }
        return new Distribution(n, sorted.get(0), sorted.get(n - 1),
                (double) sum / n, percentile(sorted, 0.50), percentile(sorted, 0.90), buckets);
    }

    /** Nearest-rank percentile: the smallest sample whose rank covers fraction {@code p}. */
    private static int percentile(List<Integer> sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.size());
        int idx = Math.max(1, Math.min(sorted.size(), rank)) - 1;
        return sorted.get(idx);
    }

    /**
     * The bucket label {@code v} would fall into — the peer lookup to {@link #buckets()} for a caller
     * holding a scalar (e.g. a distribution's own reported {@code max}) that needs to know which bucket
     * contains it, without re-deriving the log-doubling scheme itself (the bug this replaced: {@code
     * ModelSynthesizer.depthSplit} tested a bucket's LOWER bound against a target max, which only works
     * when the max happens to coincide with a bucket boundary).
     */
    public static String bucketFor(int v) {
        return bucketLabel(v);
    }

    /** Log-scaled bucket label — bounded regardless of the max, so the silhouette is compact. */
    private static String bucketLabel(int v) {
        if (v <= 0) {
            return "0";
        }
        if (v == 1) {
            return "1";
        }
        long lo = 2;                                    // long: v can reach Integer.MAX_VALUE (star-capture), where
        while (lo * 2 <= v) {                           // an int doubling would overflow to negative → infinite loop
            lo *= 2;
        }
        long hi = lo * 2 - 1;
        return lo + "-" + hi;
    }
}
