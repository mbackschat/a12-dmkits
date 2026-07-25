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
