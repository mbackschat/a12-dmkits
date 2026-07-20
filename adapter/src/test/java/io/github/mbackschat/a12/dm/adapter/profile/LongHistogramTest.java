package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class LongHistogramTest {

    @Test
    void canonicalizesKeysAndDefensivelyCopiesCounts() {
        var source = new LinkedHashMap<String, Long>();
        source.put("zeta", 2L);
        source.put("alpha", 3L);

        var histogram = LongHistogram.of(source);
        source.put("middle", 7L);

        assertEquals(List.of("alpha", "zeta"), List.copyOf(histogram.counts().keySet()));
        assertEquals(5L, histogram.total());
        assertThrows(UnsupportedOperationException.class, () -> histogram.counts().put("later", 1L));
    }

    @Test
    void builderCollectsRepeatedKeysWithCheckedLongArithmetic() {
        var histogram = LongHistogram.builder()
                .add("FieldFilled")
                .add("FieldFilled", 2)
                .add("And", 4)
                .build();

        assertEquals(3L, histogram.counts().get("FieldFilled"));
        assertEquals(4L, histogram.counts().get("And"));
        assertEquals(7L, histogram.total());
        assertThrows(
                ArithmeticException.class,
                () -> LongHistogram.builder()
                        .add("FieldFilled", Long.MAX_VALUE)
                        .add("FieldFilled")
                        .build());
    }

    @Test
    void putRejectsDuplicateLabelsRatherThanSilentlyReplacingCounts() {
        var builder = LongHistogram.builder().put("NUMBER", 2);

        assertThrows(IllegalArgumentException.class, () -> builder.put("NUMBER", 3));
    }

    @Test
    void rejectsNullBlankAndNegativeEntries() {
        assertThrows(NullPointerException.class, () -> LongHistogram.of(null));
        assertThrows(
                NullPointerException.class,
                () -> LongHistogram.of(java.util.Collections.singletonMap(null, 1L)));
        assertThrows(NullPointerException.class, () -> LongHistogram.of(java.util.Collections.singletonMap("x", null)));
        assertThrows(IllegalArgumentException.class, () -> LongHistogram.of(java.util.Map.of(" ", 1L)));
        assertThrows(IllegalArgumentException.class, () -> LongHistogram.of(java.util.Map.of("x", -1L)));
        assertThrows(IllegalArgumentException.class, () -> LongHistogram.builder().add("", 1));
        assertThrows(IllegalArgumentException.class, () -> LongHistogram.builder().add("x", -1));
    }

    @Test
    void totalOverflowFailsDuringConstruction() {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("alpha", Long.MAX_VALUE);
        counts.put("zeta", 1L);

        assertThrows(ArithmeticException.class, () -> LongHistogram.of(counts));
    }

    @Test
    void buildSnapshotsTheBuilderAndEmptyIsAValue() {
        var builder = LongHistogram.builder().add("one", 1);
        var first = builder.build();
        builder.add("two", 2);

        assertEquals(java.util.Map.of("one", 1L), first.counts());
        assertEquals(LongHistogram.of(java.util.Map.of("one", 1L)), first);
        assertEquals(LongHistogram.empty(), LongHistogram.builder().build());
        assertEquals(0L, LongHistogram.empty().total());
    }
}
