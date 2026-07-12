package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * DG2c — {@link ReadComputation.Opaque} carries the verbatim stored text (parity with
 * {@link ReadResult.Opaque}), so an unconverted computation round-trips, not just its {@code reason}.
 */
class ReadComputationOpaqueTest {

    @Test
    void opaqueCarriesItsVerbatimCanonicalText() {
        ReadComputation.Opaque op = new ReadComputation.Opaque("[Base] * 2", "not modeled yet");
        assertEquals("[Base] * 2", op.canonicalText());
        assertEquals("not modeled yet", op.reason());
    }
}
