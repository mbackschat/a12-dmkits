package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** {@link Distribution} — the tail-preserving summary used across {@code model profile}. */
class DistributionTest {

    @Test
    void emptyIsAllZero() {
        Distribution d = Distribution.of(List.of());
        assertEquals(0, d.n());
        assertEquals(0, d.max());
        assertTrue(d.buckets().isEmpty());
    }

    @Test
    void summarizesMinMaxMean() {
        Distribution d = Distribution.of(List.of(1, 2, 3, 4));
        assertEquals(4, d.n());
        assertEquals(1, d.min());
        assertEquals(4, d.max());
        assertEquals(2.5, d.mean());
    }

    @Test
    void nearestRankPercentiles() {
        Distribution d = Distribution.of(List.of(1, 2, 3, 4));
        assertEquals(2, d.median()); // ceil(.5*4)=2 -> sorted[1]
        assertEquals(4, d.p90());    // ceil(.9*4)=4 -> sorted[3]
    }

    @Test
    void theTailSurvivesAmongTrivia() {
        // fifty trivial (depth 1) plus one monster (depth 40): mean stays low, max exposes the monster
        List<Integer> values = new java.util.ArrayList<>(java.util.Collections.nCopies(50, 1));
        values.add(40);
        Distribution d = Distribution.of(values);
        assertEquals(1, d.median());
        assertEquals(40, d.max());
        assertEquals(50, d.buckets().get("1")); // fifty trivial rules still visible as the bulk
    }

    @Test
    void logBucketsKeepTheSilhouetteCompact() {
        Distribution d = Distribution.of(List.of(1, 2, 3, 5, 9, 40));
        assertEquals(1, d.buckets().get("1"));
        assertEquals(2, d.buckets().get("2-3"));   // 2,3
        assertEquals(1, d.buckets().get("4-7"));    // 5
        assertEquals(1, d.buckets().get("8-15"));   // 9
        assertEquals(1, d.buckets().get("32-63"));  // 40
    }

    @Test
    void bucketsIterateInAscendingBucketOrderRegardlessOfInputMapOrderOrType() {
        // ModelSynthesizer.sampleMultiset iterates buckets() while consuming a shared seeded Random
        // sequentially (see ModelSynthesizerCrossJvmDeterminismTest) — the stored order must be a pure
        // function of the bucket labels themselves, never of whatever map
        // implementation or insertion order a caller (or a Jackson deserialization) happened to build.
        Map<String, Integer> scrambled = new HashMap<>();
        scrambled.put("64-127", 1);
        scrambled.put("4-7", 1);
        scrambled.put("1", 1);
        scrambled.put("2-3", 1);
        scrambled.put("0", 1);
        Distribution d = new Distribution(5, 0, 100, 20.0, 5, 90, scrambled);
        assertEquals(List.of("0", "1", "2-3", "4-7", "64-127"), new ArrayList<>(d.buckets().keySet()));
    }
}
