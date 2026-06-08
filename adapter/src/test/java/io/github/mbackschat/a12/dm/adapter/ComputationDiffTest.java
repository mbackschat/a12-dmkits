package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;

/**
 * F8 modify-by-re-express diff for <b>computations</b> ({@link KernelAdapter#diffComputation}, the
 * {@code diff} peer): re-express an ingested computation and review the before/after rendering of the
 * whole computation (computed field + common precondition + alternatives) before writing it back.
 */
class ComputationDiffTest {

    private static final String EFFECTIVE = "/Subscription/Billing/EffectiveFee";
    private static final String BASE = "/Subscription/Billing/BaseFee";

    private static BoundModel base() {
        return BoundModel.ofJson(read("models/subscription.dm.json"));
    }

    /** A model carrying EffectiveFee = BaseFee. */
    private static BoundModel withComputation() {
        BoundModel m = base();
        Computation comp = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).value())
                .message(en("EffectiveFee equals BaseFee."), de("Effektivgebühr = Basisgebühr.")).build();
        return m.add(comp);
    }

    @Test
    void diffsAReExpressedComputation() {
        BoundModel withComp = withComputation();
        // re-express: EffectiveFee = BaseFee * 2
        Computation modified = withComp.computation(withComp.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(withComp.num(BASE).asNumber().times(new NumberExpr(NumberLiteral.of(2))).expression())
                .message(en("EffectiveFee is double BaseFee."), de("Effektivgebühr ist doppelte Basisgebühr.")).build();

        RenderedDiff diff = KernelAdapter.of(withComp).diffComputation(EFFECTIVE + "Comp", modified);

        assertTrue(diff.changed(), () -> "the re-expression is a change: " + diff.unified());
        assertTrue(diff.before().contains("BaseFee") && !diff.before().contains("* 2"),
                () -> "before is the original: " + diff.before());
        assertTrue(diff.after().contains("* 2"), () -> "after carries the markup: " + diff.after());
    }

    @Test
    void anUnchangedReExpressionIsNotAChange() {
        BoundModel withComp = withComputation();
        // re-express to the identical computation
        Computation same = withComp.computation(withComp.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(withComp.num(BASE).value())
                .message(en("EffectiveFee equals BaseFee."), de("Effektivgebühr = Basisgebühr.")).build();

        RenderedDiff diff = KernelAdapter.of(withComp).diffComputation(EFFECTIVE + "Comp", same);
        assertFalse(diff.changed(), () -> "an identical re-expression is not a change: " + diff.unified());
    }

    @Test
    void diffingAMissingComputationFails() {
        BoundModel withComp = withComputation();
        Computation any = withComp.computation(withComp.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(withComp.num(BASE).value()).message(en("x"), de("y")).build();
        assertThrows(RuntimeException.class,
                () -> KernelAdapter.of(withComp).diffComputation("/Subscription/Billing/NoSuchComp", any));
    }

    private static String read(String path) {
        try (InputStream in = ComputationDiffTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
