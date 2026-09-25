package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A computation's typed {@code roundingMode}, from the builder to the stored member and back to the kernel.
 *
 * <p>The builder refuses exactly the cell {@code validate.laws.ComputationRoundingModeLawsTest} measured the
 * kernel refusing: a non-{@code EXACT} mode beside an alternative without a tolerance band, in any position
 * (KERNEL-FINDINGS KF297). The admitted rows are asked of the kernel itself, so the refusal cannot be broader
 * than its basis without a row here going red.
 */
class ComputationRoundingModeTest {

    private static final BoundModel m = Fixtures.subscription();

    private static final NumberRef BASE_FEE = m.num("/Subscription/Billing/BaseFee");

    @Test
    void theModeDefaultsToExactAndTravelsIntoTheDraft() {
        assertEquals(RoundingMode.EXACT, fee(b -> b.calc(BASE_FEE.value())).roundingMode());
        Computation rounded = fee(b -> b.calc(BASE_FEE.value(), ToleranceBand.TR1)
                .roundingMode(RoundingMode.ROUND_UP));
        assertEquals(RoundingMode.ROUND_UP, rounded.roundingMode());
        assertEquals(RoundingMode.ROUND_UP, rounded.draft().roundingMode());
    }

    /** A12's serializer omits {@code Exact}; its mapping reads the absent member as {@code EXACT}. */
    @Test
    void onlyANonExactModeIsWritten() {
        String exact = ModelWriter.addComputation(Fixtures.subscriptionJson(),
                fee(b -> b.calc(BASE_FEE.value(), ToleranceBand.TR1)));
        assertFalse(exact.contains("roundingMode"), () -> "Exact is never stored: " + exact);
        String down = ModelWriter.addComputation(Fixtures.subscriptionJson(),
                fee(b -> b.calc(BASE_FEE.value(), ToleranceBand.TR1).roundingMode(RoundingMode.ROUND_DOWN)));
        assertTrue(down.contains("\"roundingMode\" : \"RoundDown\""), () -> "the wire spelling is stored: " + down);
    }

    @Test
    void aBandedRoundingComputationIsAdmittedByTheKernel() {
        StringRef email = m.str("/Subscription/Billing/InvoiceEmail");
        List<UnaryOperator<Computation.Builder>> shapes = List.of(
                b -> b.calc(BASE_FEE.value(), ToleranceBand.TR2),
                // every alternative banded: the admitted twin of the refused mixed shapes below
                b -> b.when(email.filled()).calc(BASE_FEE.value(), ToleranceBand.TR1)
                        .when(email.notFilled()).calc(BASE_FEE.value(), ToleranceBand.TR5));
        for (RoundingMode mode : List.of(RoundingMode.ROUND_UP, RoundingMode.ROUND_DOWN)) {
            for (UnaryOperator<Computation.Builder> shape : shapes) {
                ValidationResult result = new RuleValidator().check(Fixtures.subscriptionJson(),
                        fee(b -> shape.apply(b).roundingMode(mode)).draft());
                assertTrue(result.ok(), () -> mode + " beside a band is admitted: " + result.diagnostics());
            }
        }
    }

    /** One unbanded alternative among banded ones is enough, in either position, as the kernel measures it. */
    @Test
    void aNonExactModeBesideAnUnbandedAlternativeIsRefusedWithTheKernelsClass() {
        StringRef email = m.str("/Subscription/Billing/InvoiceEmail");
        List<UnaryOperator<Computation.Builder>> shapes = List.of(
                b -> b.calc(BASE_FEE.value()),
                b -> b.when(email.filled()).calc(BASE_FEE.value(), ToleranceBand.TR1)
                        .when(email.notFilled()).calc(BASE_FEE.value()),
                b -> b.when(email.filled()).calc(BASE_FEE.value())
                        .when(email.notFilled()).calc(BASE_FEE.value(), ToleranceBand.TR1));
        for (UnaryOperator<Computation.Builder> shape : shapes) {
            PrecheckException refusal = assertThrows(PrecheckException.class,
                    () -> fee(b -> shape.apply(b).roundingMode(RoundingMode.ROUND_UP)));
            assertEquals(Optional.of(KernelCode.MVK_ERROR_WRONG_ROUNDING_MODE.name()), refusal.diagnostic().code());
        }
    }

    /**
     * The mode is the calculation module's last question, so an unbanded mode beside a self-read or a
     * CustomCondition is refused for those, as the kernel refuses it
     * ({@code validate.laws.CalculationMemberWithholdingLawsTest.theCalculationModuleAsksItsStagesInOrder}, rows
     * "ROUNDING · a read of the computed NUMBER field" and "ROUNDING · the language feature").
     */
    @Test
    void anEarlierStageNamesItsOwnClassBesideAnUnbandedMode() {
        NumberRef effectiveFee = m.num("/Subscription/Billing/EffectiveFee");
        Map<String, UnaryOperator<Computation.Builder>> shapes = new LinkedHashMap<>();
        shapes.put("a read of the computed field", b -> b.when(effectiveFee.filled()).calc(BASE_FEE.value()));
        shapes.put("a CustomCondition", b -> b.when(Dsl.customCondition("IsRenewal")).calc(BASE_FEE.value()));
        Map<String, String> actual = new LinkedHashMap<>();
        shapes.forEach((row, shape) -> actual.put(row, assertThrows(PrecheckException.class,
                () -> fee(b -> shape.apply(b).roundingMode(RoundingMode.ROUND_UP))).diagnostic().code().orElse("")));
        assertEquals(Map.of(
                "a read of the computed field", KernelCode.MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD.name(),
                "a CustomCondition", KernelCode.MVK_ERROR_INVALID_LANGUAGE_FEATURE.name()), actual);
    }

    /**
     * The draft path writes the mode too: a {@code DraftComputation} never meets the builder's refusal, so the
     * kernel is the one to refuse the unbanded pair, which it can only do if the validator stored the mode.
     */
    @Test
    void theValidatorStoresTheModeOfARawDraft() {
        DraftComputation unbanded = DraftComputation.builder()
                .groupPath("/Subscription/Billing").name("RoundedFee").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, "[BaseFee]")))
                .roundingMode(RoundingMode.ROUND_UP)
                .messages(List.of(Dsl.en("rounded"), Dsl.de("gerundet")))
                .build();
        ValidationResult result = new RuleValidator().check(Fixtures.subscriptionJson(), unbanded);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code()
                        .filter(KernelCode.MVK_ERROR_WRONG_ROUNDING_MODE.name()::equals).isPresent()),
                () -> "the kernel refuses the stored pair: " + result.diagnostics());
    }

    @Test
    void aStoredSpellingIsMatchedExactlyAsA12MatchesIt() {
        assertEquals(RoundingMode.ROUND_UP, RoundingMode.fromWire("RoundUp"));
        assertEquals(RoundingMode.EXACT, RoundingMode.fromWire("Exact"));
        assertThrows(IllegalArgumentException.class, () -> RoundingMode.fromWire("ROUND_UP"));
        assertThrows(IllegalArgumentException.class, () -> RoundingMode.fromWire("roundup"));
    }

    private static Computation fee(UnaryOperator<Computation.Builder> shape) {
        return shape.apply(m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("RoundedFee"))
                .message(Dsl.en("rounded"), Dsl.de("gerundet")).build();
    }
}
