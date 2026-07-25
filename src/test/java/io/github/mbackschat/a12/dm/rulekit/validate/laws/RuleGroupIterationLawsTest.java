package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * Ordinary-iteration NEGATIVE-CONDITION admission ({@code MVK_NEG_CONDITION_IN_ITERATION}) — the kernel rejects
 * an iterating rule whose condition could hold on a not-filled repetition, and that admission test is
 * <b>operator- and operand-sensitive</b>, not a fixed list of negative operators.
 *
 * <p><b>The direct-Number partition.</b> For a direct iterating Number field compared with an immediate number
 * literal, ordinary {@code ==}/{@code >=}/{@code <=} are unguarded — hence rejected — exactly when the kernel's
 * host-converted integer form of the literal is <b>zero</b>; strict {@code >}/{@code <}, ordinary {@code !=},
 * and any converted-nonzero literal are admitted. Symmetric in both operand orders. The conversion is
 * {@code FeldOperationUtil}'s {@code Double.parseDouble} → {@code Math.round} → {@code (int)} — so the
 * separators are the rounding boundary, and they are ASYMMETRIC about zero: {@code 0.50} converts to 1 and is
 * admitted while {@code -0.50} converts to 0 and is rejected, because {@code Math.round} is half-up toward
 * positive infinity (this pair alone rules out HALF_EVEN and away-from-zero).
 *
 * <p><b>Direct field operations keep the partition; composite arithmetic escapes it.</b> {@code Abs}, operand-list
 * {@code MinValue}/{@code MaxValue} over all-iterating direct references, and evaluated-String {@code Length}
 * are zero-sensitive field operations. But a top-level arithmetic expression is an {@code ICompositeOperation},
 * for which the visitor has no direct-operand branch — so {@code [Count] + 0 == 0} is ADMITTED where
 * {@code [Count] == 0} is rejected. Wrapping a field in arithmetic changes static admission without changing
 * runtime arithmetic.
 *
 * <p><b>The {@code (int)} narrowing is observable, and the parse step is not.</b> On a scale-0 field a bare
 * integer literal matches the field's scale, so it reaches the partition and is judged on its narrowed value —
 * {@code == 4294967296} is REJECTED (2^32 narrows to zero) while {@code == 4294967297} is admitted. The other
 * direction is walled off: {@code Double.parseDouble} can lift a decimal in {@code [1/2 - 2^-55, 1/2)} onto
 * {@code 0.5}, but the shortest such decimal needs 17 fractional digits, a field's scale caps at 14, and the
 * comparison gate allows the literal at most the field's scale — so that witness is unauthorable.
 *
 * <p>Kernel truth + the wider partition (per-level connectives, entity lists, temporal differences):
 * KERNEL-SEMANTICS §9 and KERNEL-FINDINGS §9 supplement "ordinary-iteration negative admission is level- and
 * operand-sensitive" (a12-kernel-lean SPEC-2026-07-24-02). The kernel owns the full visitor — rulekit
 * delegates and these locks pin the observable boundary; nothing here reimplements it.
 *
 * <p>Fixture: {@code /Order/Items} is repeatable; {@code Count} is scale-0, {@code UnitWeight} scale-2,
 * {@code Sku} a String.
 */
class RuleGroupIterationLawsTest {

    private static final String ITEMS = "/Order/Items";

    // ── the original case: a negated presence test on the rule's own repeated context ────────────────────
    @Test
    void groupNotFilledRuleGroupIsRejectedInAnIterationGroup() {
        KernelLaws.rejectsOrder(ITEMS, "../Count",
                "GroupNotFilled(RuleGroup)", "MVK_NEG_CONDITION_IN_ITERATION");
    }

    // ── direct Number vs an immediate literal: converted-zero is UNGUARDED ───────────────────────────────
    @Test
    void ordinaryComparisonsAgainstConvertedZeroAreRejected() {
        for (String op : new String[] { "==", ">=", "<=" }) {
            KernelLaws.rejectsOrder(ITEMS, "../Count",
                    "[Count] " + op + " 0", "MVK_NEG_CONDITION_IN_ITERATION");
        }
    }

    @Test
    void theSameZeroRejectionHoldsWithTheLiteralOnTheLeft() {
        for (String op : new String[] { "==", ">=", "<=" }) {
            KernelLaws.rejectsOrder(ITEMS, "../Count",
                    "0 " + op + " [Count]", "MVK_NEG_CONDITION_IN_ITERATION");
        }
    }

    @Test
    void strictComparisonsAndOrdinaryNotEqualAreAdmittedAgainstZero() {
        KernelLaws.acceptsOrder(ITEMS, "../Count", "[Count] > 0");
        KernelLaws.acceptsOrder(ITEMS, "../Count", "[Count] < 0");
        KernelLaws.acceptsOrder(ITEMS, "../Count", "[Count] != 0");
    }

    @Test
    void aConvertedNonzeroLiteralIsAdmittedInEitherOperandOrder() {
        for (String op : new String[] { "==", ">=", "<=" }) {
            KernelLaws.acceptsOrder(ITEMS, "../Count", "[Count] " + op + " 5");
        }
        KernelLaws.acceptsOrder(ITEMS, "../Count", "5 == [Count]");
    }

    // ── the host conversion IS the separator, and it is asymmetric about zero ────────────────────────────
    @Test
    void aBelowHalfLiteralConvertsToZeroAndIsRejected() {
        KernelLaws.rejectsOrder(ITEMS, "../UnitWeight", "[UnitWeight] == 0.40", "MVK_NEG_CONDITION_IN_ITERATION");
        KernelLaws.rejectsOrder(ITEMS, "../UnitWeight", "[UnitWeight] == 0.49", "MVK_NEG_CONDITION_IN_ITERATION");
        KernelLaws.rejectsOrder(ITEMS, "../UnitWeight", "0.40 == [UnitWeight]", "MVK_NEG_CONDITION_IN_ITERATION");
    }

    @Test
    void theHalfTieRoundsUpToOneAndIsAdmitted() {
        KernelLaws.acceptsOrder(ITEMS, "../UnitWeight", "[UnitWeight] == 0.50");
        KernelLaws.acceptsOrder(ITEMS, "../UnitWeight", "0.50 == [UnitWeight]");
        KernelLaws.acceptsOrder(ITEMS, "../UnitWeight", "[UnitWeight] == 0.60");
        KernelLaws.acceptsOrder(ITEMS, "../UnitWeight", "[UnitWeight] == 1.00");
    }

    /** The discriminator for {@code Math.round}: half-up toward +∞, so −0.50 converts to 0, not to −1. */
    @Test
    void theNegativeHalfTieConvertsToZeroAndIsRejected() {
        KernelLaws.rejectsOrder(ITEMS, "../UnitWeight", "[UnitWeight] == -0.50", "MVK_NEG_CONDITION_IN_ITERATION");
        KernelLaws.rejectsOrder(ITEMS, "../UnitWeight", "[UnitWeight] == -0.40", "MVK_NEG_CONDITION_IN_ITERATION");
    }

    @Test
    void strictAndNotEqualStayAdmittedOnAFractionalField() {
        KernelLaws.acceptsOrder(ITEMS, "../UnitWeight", "[UnitWeight] != 0.00");
        KernelLaws.acceptsOrder(ITEMS, "../UnitWeight", "[UnitWeight] > 0.00");
    }

    // ── direct field OPERATIONS retain the zero-sensitive partition ──────────────────────────────────────
    @Test
    void directFieldAbsIsZeroSensitive() {
        KernelLaws.rejectsOrder(ITEMS, "../Count", "Abs([Count]) == 0", "MVK_NEG_CONDITION_IN_ITERATION");
    }

    @Test
    void allIteratingOperandListMinAndMaxAreZeroSensitive() {
        KernelLaws.rejectsOrder(ITEMS, "../Count",
                "MinValue(Count, UnitWeight) == 0", "MVK_NEG_CONDITION_IN_ITERATION");
        KernelLaws.rejectsOrder(ITEMS, "../Count",
                "MaxValue(Count, UnitWeight) == 0", "MVK_NEG_CONDITION_IN_ITERATION");
    }

    @Test
    void directEvaluatedStringLengthIsZeroSensitive() {
        KernelLaws.rejectsOrder(ITEMS, "../Sku", "Length(Sku) == 0", "MVK_NEG_CONDITION_IN_ITERATION");
        KernelLaws.acceptsOrder(ITEMS, "../Sku", "Length(Sku) > 0");
        KernelLaws.acceptsOrder(ITEMS, "../Sku", "Length(Sku) == 3");
    }

    // ── the AUTHORABILITY WALL that keeps the conversion exact-decimal for every legal model ─────────────
    //
    // The conversion's parse step can lift a decimal STRICTLY BELOW one half onto 0.5 — binary64
    // round-to-nearest-even maps everything in [1/2 - 2^-55, 1/2) to 0.5, and Math.round(0.5) is 1. So the
    // classifier is NOT exact-decimal in principle. These three locks prove no kernel-legal model can express
    // that witness: it needs 17 fractional digits, a field's scale caps at 14, and the comparison gate demands
    // the literal carry EXACTLY the field's scale. See KERNEL-FINDINGS §9 supplement "the host conversion's
    // parse step".

    private static final String SCALED_ITEMS = "/M/Items";

    /** A repeatable group whose only Number field declares {@code fractionDigits} decimal places. */
    private static String scaledItems(int fractionDigits) {
        return io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("scaled").locale("en_US")
                .rootGroup("M", m -> m
                        .group("Items", i -> i
                                .repeatable(5)
                                .numberField("W", n -> n.fractionDigits(fractionDigits))))
                .build().toJson();
    }

    /**
     * A Number field's {@code maxFractionalDigits} caps at <b>14</b> — the kernel rejects 15 at model check
     * ("Only values smaller than or equal to 14 are allowed"), with no MVK code. Both sides here compare against
     * a literal of matching scale, so the pair isolates the FIELD cap from the comparison gate below.
     */
    @Test
    void aNumberFieldScaleCapsAtFourteen() {
        KernelLaws.acceptsModel(scaledItems(14), SCALED_ITEMS, "../W", "[W] > 0.00000000000000");
        KernelLaws.rejectsModel(scaledItems(15), SCALED_ITEMS, "../W", "[W] > 0.000000000000000");
    }

    /** Control: the negative-admission classifier still runs at the maximum legal scale, so the wall below is
     *  about the DIGIT COUNT, not about scale-14 fields being exempt from the partition. */
    @Test
    void theZeroSensitivePartitionStillHoldsAtTheMaximumScale() {
        KernelLaws.rejectsModel(scaledItems(14), SCALED_ITEMS, "../W",
                "[W] == 0.49999999999999", "MVK_NEG_CONDITION_IN_ITERATION");
    }

    /**
     * The comparison gate caps the literal's fractional digits at the field's scale — <b>at most</b>, not
     * exactly. A 17-digit literal against a scale-14 field is rejected {@code MVK_INVALID_COMPARE_DEC_PLACES}
     * (whose message says the two counts "are different", which understates it: a SHORTER literal is fine and
     * reaches the classifier normally). Since 14 &lt; 17, no kernel-legal model can carry the lifting witness.
     */
    @Test
    void aComparisonLiteralMayCarryAtMostTheFieldsScale() {
        KernelLaws.rejectsModel(scaledItems(14), SCALED_ITEMS, "../W",
                "[W] == 0.49999999999999998", "MVK_INVALID_COMPARE_DEC_PLACES");
        // shorter is admitted by the gate, so it reaches the partition and is judged on its converted value
        KernelLaws.acceptsModel(scaledItems(14), SCALED_ITEMS, "../W", "[W] == 0.5");
        KernelLaws.rejectsModel(scaledItems(14), SCALED_ITEMS, "../W",
                "[W] == 0.4", "MVK_NEG_CONDITION_IN_ITERATION");
    }

    // ── the (int) NARROWING is observable through the public DSL ─────────────────────────────────────────
    /**
     * The {@code (int)} step of the conversion is not academic: on a scale-0 field an integer literal matches the
     * field's scale, so it sails through {@code MVK_INVALID_COMPARE_DEC_PLACES} into the partition and is judged
     * on its <b>narrowed</b> value. {@code 2^32} narrows to <b>0</b> and is therefore rejected as unguarded —
     * a literal of 4,294,967,296 classified as zero — while {@code 2^32 + 1} narrows to 1 and is admitted.
     * The pair differs by one and straddles the whole partition, which pins 32-bit truncation specifically
     * (a widening or saturating conversion would admit both).
     */
    @Test
    void theThirtyTwoBitNarrowingIsObservable() {
        KernelLaws.rejectsOrder(ITEMS, "../Count", "[Count] == 4294967296", "MVK_NEG_CONDITION_IN_ITERATION");
        KernelLaws.acceptsOrder(ITEMS, "../Count", "[Count] == 4294967297");
    }

    /** {@code Math.round} saturates at {@code Long.MAX_VALUE}, which narrows to −1 — nonzero, so admitted. */
    @Test
    void longSaturationNarrowsToMinusOneAndIsAdmitted() {
        KernelLaws.acceptsOrder(ITEMS, "../Count", "[Count] == 9223372036854775808");
    }

    /**
     * <b>The parse-step lift IS authorable — the dec-places gate is the kernel's one suppressible warning.</b>
     * With {@code @SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)} (the CLI's {@code allowDifferingDecimals})
     * a 17-digit literal reaches the classifier against a scale-14 field, and the two literals below differ only
     * in their final digit: {@code …98} falls inside {@code [1/2 - 2^-55, 1/2)} so {@code parseDouble} lifts it
     * onto {@code 0.5} and it converts to 1 (admitted), while {@code …90} sits just below the interval and
     * converts to 0 (rejected). Both are strictly below one half, so this pair is the end-to-end witness that the
     * classifier is <b>not</b> exact-decimal — the separator is a real language property, not a host-only detail.
     */
    @Test
    void theParseStepLiftIsAuthorableWithTheGateSuppressed() {
        KernelLaws.acceptsModel(scaledItems(14), SCALED_ITEMS, "../W",
                "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) [W] == 0.49999999999999998");
        KernelLaws.rejectsModel(scaledItems(14), SCALED_ITEMS, "../W",
                "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) [W] == 0.49999999999999990",
                "MVK_NEG_CONDITION_IN_ITERATION");
    }

    // ── the composite-operation separator ────────────────────────────────────────────────────────────────
    /**
     * The sharpest control in this file: wrapping the field in arithmetic makes the comparison's left side an
     * {@code ICompositeOperation}, which the visitor has no direct-operand branch for — so the SAME
     * zero comparison flips from rejected to admitted while the runtime arithmetic is unchanged.
     */
    @Test
    void topLevelArithmeticEscapesTheDirectFieldPartition() {
        KernelLaws.rejectsOrder(ITEMS, "../Count", "[Count] == 0", "MVK_NEG_CONDITION_IN_ITERATION");
        KernelLaws.acceptsOrder(ITEMS, "../Count", "[Count] + 0 == 0");
    }
}
