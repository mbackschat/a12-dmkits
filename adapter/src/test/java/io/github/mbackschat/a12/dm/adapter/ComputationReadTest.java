package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.fieldFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * F10 read direction for <b>computations</b> (the {@code ReadResult} peer): a computation authored + added
 * to a model reads back to a typed {@link Computation} whose rendered slots ({@code draft()}) match
 * the original — render → extract → convert is the identity for the covered (numeric, exact) shapes.
 * Drives {@code KernelAdapter.readComputation}/{@code readAllComputations} over the kernel's
 * {@code ICalculationService.extractCalculationInfo}.
 */
class ComputationReadTest {

    private static final String EFFECTIVE = "/Subscription/Billing/EffectiveFee";
    private static final String BASE = "/Subscription/Billing/BaseFee";

    private static BoundModel base() {
        return BoundModel.ofJson(read("models/subscription.dm.json"));
    }

    @Test
    void roundTripsAnUnconditionalComputation() {
        BoundModel m = base();
        Computation comp = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).value())
                .message(en("EffectiveFee must equal BaseFee."), de("Effektivgebühr = Basisgebühr.")).build();

        Computation got = read(m.add(comp), "/Subscription/Billing/EffectiveFeeComp");
        assertEquals(comp.draft(), got.draft());
    }

    @Test
    void roundTripsATieredComputationWithCommonPrecondition() {
        BoundModel m = base();
        NumberRef base = m.num(BASE);
        Computation comp = m.computation(m.num(EFFECTIVE)).name("TieredFeeComp")
                .commonPrecondition(fieldFilled(base.field()))
                .when(base.ge(100)).calc(base.value())
                .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
                .message(en("EffectiveFee follows the tiers."), de("Effektivgebühr nach Stufen.")).build();

        Computation got = read(m.add(comp), "/Subscription/Billing/TieredFeeComp");
        assertEquals(comp.draft(), got.draft());
    }

    @Test
    void readAllComputationsTypesEveryComputation() {
        BoundModel m = base();
        Computation comp = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).value()).message(en("x"), de("y")).build();

        var all = KernelAdapter.of(m.add(comp)).readAllComputations();
        assertEquals(1, all.size(), () -> "one computation expected, got: " + all.keySet());
        assertInstanceOf(ReadComputation.Converted.class, all.get("/Subscription/Billing/EffectiveFeeComp"));
    }

    @Test
    void fullCycleReadModifyWriteBack() {
        BoundModel m = base();
        Computation comp = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).value())
                .message(en("EffectiveFee equals BaseFee."), de("Effektivgebühr = Basisgebühr.")).build();
        BoundModel withComp = m.add(comp);

        // read the computation back — it's the original
        Computation current = read(withComp, "/Subscription/Billing/EffectiveFeeComp");
        assertEquals(comp.draft(), current.draft());

        // re-express: EffectiveFee = BaseFee * 2 (a markup)
        Computation modified = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).asNumber().times(new NumberExpr(NumberLiteral.of(2))).expression())
                .message(en("EffectiveFee is double BaseFee."), de("Effektivgebühr ist doppelte Basisgebühr.")).build();

        // write back (F8 #3) and re-read → the modified computation round-trips, the old logic is gone
        BoundModel applied = withComp.replaceComputation("/Subscription/Billing/EffectiveFeeComp", modified);
        Computation reread = read(applied, "/Subscription/Billing/EffectiveFeeComp");
        assertEquals(modified.draft(), reread.draft());
        assertNotEquals(comp.draft(), reread.draft(), "the modify took effect");
    }

    private static Computation read(BoundModel withComp, String fullName) {
        ReadComputation result = KernelAdapter.of(withComp).readComputation(fullName);
        return assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
    }

    private static String read(String path) {
        try (InputStream in = ComputationReadTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
