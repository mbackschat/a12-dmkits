package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A deterministic summary of non-negative {@code long} samples.
 *
 * <p>The exact samples are deliberately discarded. The nearest-rank median and p90 retain the center
 * and tail, while logarithmic buckets retain the distribution shape without exposing source-model
 * values or overflowing at {@link Long#MAX_VALUE}.
 */
record LongDistribution(
        long count,
        long min,
        long median,
        long p90,
        long max,
        List<Bucket> buckets) {

    private static final LongDistribution EMPTY =
            new LongDistribution(0, 0, 0, 0, 0, List.of());

    LongDistribution {
        if (count < 0) {
            throw new IllegalArgumentException("distribution count must be non-negative: " + count);
        }
        buckets = List.copyOf(Objects.requireNonNull(buckets, "buckets"));
        validateSummary(count, min, median, p90, max, buckets);
    }

    record Bucket(long lower, long upper, long count) {
        Bucket {
            if (lower < 0 || upper < lower) {
                throw new IllegalArgumentException(
                        "invalid distribution bucket range: " + lower + "-" + upper);
            }
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "distribution bucket count must be positive: " + count);
            }
        }
    }

    static LongDistribution empty() {
        return EMPTY;
    }

    static LongDistribution of(Collection<Long> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return EMPTY;
        }
        var sorted = new ArrayList<Long>(values.size());
        for (Long value : values) {
            Objects.requireNonNull(value, "distribution sample");
            if (value < 0) {
                throw new IllegalArgumentException(
                        "distribution sample must be non-negative: " + value);
            }
            sorted.add(value);
        }
        sorted.sort(Long::compareTo);

        var bucketCounts = new java.util.TreeMap<Long, Long>();
        for (long value : sorted) {
            long lower = bucketLower(value);
            bucketCounts.merge(lower, 1L, Math::addExact);
        }
        List<Bucket> buckets = bucketCounts.entrySet().stream()
                .map(entry -> new Bucket(
                        entry.getKey(), bucketUpper(entry.getKey()), entry.getValue()))
                .toList();
        int size = sorted.size();
        return new LongDistribution(
                size,
                sorted.getFirst(),
                nearestRank(sorted, 50),
                nearestRank(sorted, 90),
                sorted.getLast(),
                buckets);
    }

    static Builder builder() {
        return new Builder();
    }

    static long bucketLower(long value) {
        if (value <= 1) {
            return value;
        }
        return Long.highestOneBit(value);
    }

    static long bucketUpper(long lower) {
        if (lower <= 1) {
            return lower;
        }
        return lower > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : lower * 2 - 1;
    }

    private static long nearestRank(List<Long> sorted, int percentile) {
        long numerator = Math.multiplyExact((long) percentile, sorted.size());
        int rank = Math.toIntExact(Math.max(1, (numerator + 99) / 100));
        return sorted.get(rank - 1);
    }

    private static void validateSummary(
            long count,
            long min,
            long median,
            long p90,
            long max,
            List<Bucket> buckets) {
        if (count == 0) {
            if (min != 0 || median != 0 || p90 != 0 || max != 0 || !buckets.isEmpty()) {
                throw new IllegalArgumentException(
                        "an empty distribution must have zero summaries and no buckets");
            }
            return;
        }
        if (min < 0 || min > median || median > p90 || p90 > max) {
            throw new IllegalArgumentException(
                    "distribution summaries must satisfy 0 <= min <= median <= p90 <= max");
        }
        long bucketTotal = 0;
        long previousUpper = -1;
        for (Bucket bucket : buckets) {
            if (bucket.lower() <= previousUpper) {
                throw new IllegalArgumentException(
                        "distribution buckets must be ordered and non-overlapping");
            }
            if (bucket.lower() != bucketLower(bucket.lower())
                    || bucket.upper() != bucketUpper(bucket.lower())) {
                throw new IllegalArgumentException(
                        "non-canonical distribution bucket: "
                                + bucket.lower() + "-" + bucket.upper());
            }
            bucketTotal = Math.addExact(bucketTotal, bucket.count());
            previousUpper = bucket.upper();
        }
        if (bucketTotal != count) {
            throw new IllegalArgumentException(
                    "distribution bucket total " + bucketTotal + " does not equal count " + count);
        }
        Bucket first = buckets.getFirst();
        Bucket last = buckets.getLast();
        if (min < first.lower() || min > first.upper()
                || max < last.lower() || max > last.upper()) {
            throw new IllegalArgumentException(
                    "distribution extrema do not belong to the first and last buckets");
        }
    }

    static final class Builder {
        private final List<Long> values = new ArrayList<>();

        Builder add(long value) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "distribution sample must be non-negative: " + value);
            }
            values.add(value);
            return this;
        }

        Builder addAll(Collection<Long> additions) {
            Objects.requireNonNull(additions, "additions");
            additions.forEach(this::add);
            return this;
        }

        LongDistribution build() {
            return LongDistribution.of(values);
        }
    }
}
