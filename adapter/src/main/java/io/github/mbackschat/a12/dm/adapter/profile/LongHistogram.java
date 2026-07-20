package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * An immutable, deterministically ordered histogram with {@code long} counts.
 */
final class LongHistogram {

    private static final LongHistogram EMPTY = new LongHistogram(Map.of());

    private final SortedMap<String, Long> counts;
    private final long total;

    private LongHistogram(Map<String, Long> source) {
        Objects.requireNonNull(source, "source");
        var canonical = new TreeMap<String, Long>();
        long checkedTotal = 0;
        for (var entry : source.entrySet()) {
            var key = requireKey(entry.getKey());
            var count = requireCount(entry.getValue());
            if (canonical.putIfAbsent(key, count) != null) {
                throw new IllegalArgumentException("duplicate histogram key: " + key);
            }
            checkedTotal = Math.addExact(checkedTotal, count);
        }
        counts = Collections.unmodifiableSortedMap(canonical);
        total = checkedTotal;
    }

    static LongHistogram empty() {
        return EMPTY;
    }

    static LongHistogram of(Map<String, Long> counts) {
        return counts != null && counts.isEmpty() ? EMPTY : new LongHistogram(counts);
    }

    static Builder builder() {
        return new Builder();
    }

    SortedMap<String, Long> counts() {
        return counts;
    }

    long total() {
        return total;
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "histogram key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("histogram key must not be blank");
        }
        return key;
    }

    private static long requireCount(Long count) {
        Objects.requireNonNull(count, "histogram count");
        return requireCount(count.longValue());
    }

    private static long requireCount(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("histogram count must be non-negative: " + count);
        }
        return count;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof LongHistogram histogram && counts.equals(histogram.counts);
    }

    @Override
    public int hashCode() {
        return counts.hashCode();
    }

    @Override
    public String toString() {
        return "LongHistogram[counts=" + counts + ", total=" + total + "]";
    }

    static final class Builder {
        private final SortedMap<String, Long> counts = new TreeMap<>();

        Builder add(String key) {
            return add(key, 1);
        }

        Builder add(String key, long increment) {
            var validKey = requireKey(key);
            var validIncrement = requireCount(increment);
            counts.compute(
                    validKey,
                    (ignored, current) ->
                            current == null ? validIncrement : Math.addExact(current, validIncrement));
            return this;
        }

        Builder put(String key, long count) {
            var validKey = requireKey(key);
            var validCount = requireCount(count);
            if (counts.putIfAbsent(validKey, validCount) != null) {
                throw new IllegalArgumentException("duplicate histogram key: " + validKey);
            }
            return this;
        }

        LongHistogram build() {
            return counts.isEmpty() ? EMPTY : new LongHistogram(counts);
        }
    }
}
