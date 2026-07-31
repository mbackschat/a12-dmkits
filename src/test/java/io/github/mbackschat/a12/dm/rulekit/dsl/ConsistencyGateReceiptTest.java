package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Verification;

/**
 * A binding's consistency-gate <b>receipt</b>: {@code consistencyGate()} is non-empty iff the gate actually ran
 * for <em>this</em> binding, and then carries how it was arbitrated.
 *
 * <p>This replaces the older {@code verification()}, which conflated two different facts under one value: it
 * returned {@link Verification#STRUCTURAL_ONLY} both when the gate <em>ran without a kernel backend</em> and when
 * the gate <b>never ran at all</b> ({@link BoundModel#ofJsonLenient}, {@link BoundModel#of}). A consumer could not
 * tell them apart, so the CLI could report a {@code verification} key for an invocation that never gated —
 * precisely the false-provenance defect the result contract exists to remove. Empty now means "no gate ran, so
 * there is nothing to report"; {@code STRUCTURAL_ONLY} keeps its real meaning of "the gate ran, unbacked".
 */
class ConsistencyGateReceiptTest {

    @Test
    void aGatedBindYieldsAReceipt() {
        BoundModel gated = BoundModel.ofJson(Fixtures.subscriptionJson());

        assertTrue(gated.consistencyGate().isPresent(),
                "ofJson runs ModelLoader.loadAndCheck, so the gate ran and must leave a receipt");
        // the test estate bundles the :adapter backend, exactly as the CLI does
        assertEquals(Optional.of(Verification.KERNEL_CONFIRMED), gated.consistencyGate());
    }

    @Test
    void aLenientBindYieldsNoReceipt() {
        BoundModel lenient =
                BoundModel.ofJsonLenient(Fixtures.subscriptionJson(), ReferenceResolvers.selfContained());

        assertEquals(Optional.empty(), lenient.consistencyGate(),
                "ofJsonLenient is expand-only — it makes no validity promise, so it must claim NO verification "
                        + "rather than the STRUCTURAL_ONLY that used to be indistinguishable from an unbacked gate");
    }

    @Test
    void anIndexOnlyBindYieldsNoReceipt() {
        BoundModel indexOnly = BoundModel.of(
                io.github.mbackschat.a12.dm.core.ModelIndex.of(Fixtures.subscriptionJson(),
                        ReferenceResolvers.selfContained()));

        assertEquals(Optional.empty(), indexOnly.consistencyGate());
    }
}
