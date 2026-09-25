package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

/**
 * A rounding computation reads back as a typed {@link Computation} carrying its mode, where it used to degrade
 * to Opaque because the typed surface had no member for it.
 *
 * <p>The oracle for the round trip is the kernel's own extraction: {@code ICalculationInfo.getRoundingMode}
 * after A12 built the model, so a writer that dropped or misspelled the member would read back {@code EXACT}
 * or fail to build.
 */
class ComputationRoundingModeReadTest {

    private static final String NAME = "/Premium/RoundedPremiumComp";

    private static BoundModel base() {
        return BoundModel.ofJson(DocumentModelDraft.named("premium-rounding").locale("en_US").locale("de_DE")
                .rootGroup("Premium", p -> p
                        .numberField("BasePremium", n -> n.fractionDigits(2))
                        .numberField("ChargedPremium", n -> n.fractionDigits(2)))
                .build().toJson());
    }

    private static Computation roundedFee(BoundModel m, RoundingMode mode) {
        return m.computation(m.num("/Premium/ChargedPremium")).name("RoundedPremiumComp")
                .calc(m.num("/Premium/BasePremium").value(), ToleranceBand.TR2).roundingMode(mode)
                .message(en("The charged premium is the rounded base premium."),
                        de("Die berechnete Prämie ist die gerundete Grundprämie."))
                .build();
    }

    @Test
    void aRoundingComputationReadsBackTypedWithItsMode() {
        for (RoundingMode mode : RoundingMode.values()) {
            BoundModel m = base();
            Computation authored = roundedFee(m, mode);
            ReadComputation read = KernelAdapter.of(m.add(authored)).readComputation(NAME);
            Computation got = assertInstanceOf(ReadComputation.Converted.class, read,
                    () -> mode + " reads as a typed computation, got: " + read).computation();
            assertEquals(mode, got.roundingMode());
            assertEquals(authored.draft(), got.draft(), () -> mode + " round-trips slot for slot");
        }
    }

    @Test
    void aModeOnlyChangeIsVisibleInTheComputationDiff() {
        BoundModel m = base();
        BoundModel stored = m.add(roundedFee(m, RoundingMode.ROUND_UP));
        RenderedDiff diff = KernelAdapter.of(stored)
                .diffComputation(NAME, roundedFee(stored, RoundingMode.ROUND_DOWN));
        assertTrue(diff.changed(), () -> "a mode change is semantic: " + diff);
        assertTrue(diff.before().contains("roundingMode=RoundUp") && diff.after().contains("roundingMode=RoundDown"),
                () -> "both modes are rendered: " + diff);
    }
}
