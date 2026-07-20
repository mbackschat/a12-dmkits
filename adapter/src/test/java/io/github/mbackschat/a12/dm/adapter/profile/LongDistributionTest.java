package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LongDistributionTest {

    @Test
    void summarizesCenterTailAndLogBucketsDeterministically() {
        var distribution = LongDistribution.of(List.of(8L, 1L, 3L, 2L, 2L, 16L, 0L, 4L, 7L, 5L));

        assertEquals(10, distribution.count());
        assertEquals(0, distribution.min());
        assertEquals(3, distribution.median());
        assertEquals(8, distribution.p90());
        assertEquals(16, distribution.max());
        assertEquals(
                List.of(
                        new LongDistribution.Bucket(0, 0, 1),
                        new LongDistribution.Bucket(1, 1, 1),
                        new LongDistribution.Bucket(2, 3, 3),
                        new LongDistribution.Bucket(4, 7, 3),
                        new LongDistribution.Bucket(8, 15, 1),
                        new LongDistribution.Bucket(16, 31, 1)),
                distribution.buckets());
    }

    @Test
    void theTopLongBucketCannotOverflow() {
        var distribution =
                LongDistribution.of(List.of(Long.MAX_VALUE - 1, Long.MAX_VALUE));

        assertEquals(
                List.of(new LongDistribution.Bucket(
                        1L << 62, Long.MAX_VALUE, 2)),
                distribution.buckets());
        assertEquals(Long.MAX_VALUE, distribution.max());
    }

    @Test
    void emptyAndBuilderResultsAreImmutableValues() {
        var builder = LongDistribution.builder().add(2).add(9);
        var first = builder.build();
        builder.add(64);

        assertEquals(LongDistribution.of(List.of(2L, 9L)), first);
        assertEquals(LongDistribution.empty(), LongDistribution.of(List.of()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.buckets().add(new LongDistribution.Bucket(32, 63, 1)));
    }

    @Test
    void rejectsInvalidSamplesAndInconsistentSerializedShapes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LongDistribution.of(List.of(-1L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongDistribution(0, 0, 0, 0, 0,
                        List.of(new LongDistribution.Bucket(0, 0, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongDistribution(2, 1, 1, 1, 2,
                        List.of(new LongDistribution.Bucket(1, 1, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongDistribution(1, 3, 3, 3, 3,
                        List.of(new LongDistribution.Bucket(3, 3, 1))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LongDistribution(2, 1, 1, 2, 2,
                        List.of(
                                new LongDistribution.Bucket(2, 3, 1),
                                new LongDistribution.Bucket(1, 1, 1))));
    }
}
