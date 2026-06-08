package io.github.mbackschat.a12.dm.adapter;

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
 * F10 read direction for <b>computations</b> (the {@code Ingest} peer): a computation authored + added
 * to a model ingests back to a typed {@link Computation} whose rendered slots ({@code draft()}) match
 * the original — render → extract → convert is the identity for the covered (numeric, exact) shapes.
 * Drives {@code KernelAdapter.ingestComputation}/{@code ingestAllComputations} over the kernel's
 * {@code ICalculationService.extractCalculationInfo}.
 */
class ComputationIngestTest {

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

        Computation got = ingested(m.add(comp), "/Subscription/Billing/EffectiveFeeComp");
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

        Computation got = ingested(m.add(comp), "/Subscription/Billing/TieredFeeComp");
        assertEquals(comp.draft(), got.draft());
    }

    @Test
    void ingestAllComputationsTypesEveryComputation() {
        BoundModel m = base();
        Computation comp = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).value()).message(en("x"), de("y")).build();

        var all = KernelAdapter.of(m.add(comp)).ingestAllComputations();
        assertEquals(1, all.size(), () -> "one computation expected, got: " + all.keySet());
        assertInstanceOf(IngestedComputation.Converted.class, all.get("/Subscription/Billing/EffectiveFeeComp"));
    }

    @Test
    void fullCycleIngestModifyWriteBack() {
        BoundModel m = base();
        Computation comp = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).value())
                .message(en("EffectiveFee equals BaseFee."), de("Effektivgebühr = Basisgebühr.")).build();
        BoundModel withComp = m.add(comp);

        // ingest the computation back — it's the original
        Computation current = ingested(withComp, "/Subscription/Billing/EffectiveFeeComp");
        assertEquals(comp.draft(), current.draft());

        // re-express: EffectiveFee = BaseFee * 2 (a markup)
        Computation modified = m.computation(m.num(EFFECTIVE)).name("EffectiveFeeComp")
                .calc(m.num(BASE).asNumber().times(new NumberExpr(NumberLiteral.of(2))).expression())
                .message(en("EffectiveFee is double BaseFee."), de("Effektivgebühr ist doppelte Basisgebühr.")).build();

        // write back (F8 #3) and re-ingest → the modified computation round-trips, the old logic is gone
        BoundModel applied = withComp.replaceComputation("/Subscription/Billing/EffectiveFeeComp", modified);
        Computation reingested = ingested(applied, "/Subscription/Billing/EffectiveFeeComp");
        assertEquals(modified.draft(), reingested.draft());
        assertNotEquals(comp.draft(), reingested.draft(), "the modify took effect");
    }

    private static Computation ingested(BoundModel withComp, String fullName) {
        IngestedComputation result = KernelAdapter.of(withComp).ingestComputation(fullName);
        return assertInstanceOf(IngestedComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
    }

    private static String read(String path) {
        try (InputStream in = ComputationIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
