package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@link ConditionPolarity} — the pure-AST logical complement that powers {@code model diff}'s
 * structural {@code POLARITY_INVERTED} detection (MODEL-REVIEW-SPEC §5c level 1, LEDGER MR1). The
 * kernel has <b>no negation operator</b>, so the complement is pushed into the leaf polarity bits
 * (comparison operator, presence {@code filled}, pattern {@code matched}, value-list {@code included})
 * and across {@code And}/{@code Or} by De Morgan. Conservative: a node with no clean complement yields
 * {@link Optional#empty()} so an inversion is never claimed falsely.
 */
class ConditionPolarityTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String FEE = "/Subscription/Addons/MonthlyFee";
    private static final String NAME = "/Subscription/Addons/Name";
    private static final String EMAIL = "/Subscription/Billing/InvoiceEmail";

    @Test
    void comparisonComplementFlipsTheOperator() {
        assertEquals(Optional.of(m.num(FEE).ge(0)), ConditionPolarity.complement(m.num(FEE).lt(0)));
        assertEquals(Optional.of(m.num(FEE).gt(0)), ConditionPolarity.complement(m.num(FEE).le(0)));
        assertEquals(Optional.of(m.num(FEE).ne(0)), ConditionPolarity.complement(m.num(FEE).eq(0)));
        assertEquals(Optional.of(m.num(FEE).le(0)), ConditionPolarity.complement(m.num(FEE).gt(0)));
        assertEquals(Optional.of(m.num(FEE).lt(0)), ConditionPolarity.complement(m.num(FEE).ge(0)));
        assertEquals(Optional.of(m.num(FEE).eq(0)), ConditionPolarity.complement(m.num(FEE).ne(0)));
    }

    @Test
    void presencePatternAndValueListComplementFlipTheirBit() {
        assertEquals(Optional.of(m.num(FEE).notFilled()), ConditionPolarity.complement(m.num(FEE).filled()));
        assertEquals(Optional.of(m.str(NAME).violates("[A-Z].*")),
                ConditionPolarity.complement(m.str(NAME).matches("[A-Z].*")));
        assertEquals(Optional.of(m.str(NAME).notIn("A", "B")),
                ConditionPolarity.complement(m.str(NAME).in("A", "B")));
    }

    @Test
    void logicComplementAppliesDeMorgan() {
        Condition and = m.num(FEE).lt(0).and(m.str(EMAIL).filled());
        Condition expected = m.num(FEE).ge(0).or(m.str(EMAIL).notFilled());
        assertEquals(Optional.of(expected), ConditionPolarity.complement(and));
    }

    @Test
    void isInversionDetectsAFullFlipButNotAThresholdChange() {
        assertTrue(ConditionPolarity.isInversionOf(m.num(FEE).ge(0), m.num(FEE).lt(0)));
        assertTrue(ConditionPolarity.isInversionOf(
                m.num(FEE).ge(0).or(m.str(EMAIL).notFilled()),
                m.num(FEE).lt(0).and(m.str(EMAIL).filled())));
        // a threshold move is a change, not a polarity inversion
        assertFalse(ConditionPolarity.isInversionOf(m.num(FEE).lt(5), m.num(FEE).lt(0)));
        // a single-side flip in a conjunction is not the full complement
        assertFalse(ConditionPolarity.isInversionOf(
                m.num(FEE).ge(0).and(m.str(EMAIL).filled()),
                m.num(FEE).lt(0).and(m.str(EMAIL).filled())));
    }

    @Test
    void inversionIsIndependentOfAuthoredPathNotation() {
        // the demo surfaced this: base reads its stored notation, head reads `rule modify`'s — the same
        // field via different PathStyle hints must NOT defeat detection (styleHint is provenance, not
        // semantics; record .equals() would wrongly count it). Inversion compares the canonical rendering.
        FieldRef canonical = new FieldRef(FEE, ValueKind.NUMBER, PathStyle.CANONICAL);
        FieldRef absolute = new FieldRef(FEE, ValueKind.NUMBER, PathStyle.ABSOLUTE);
        assertTrue(ConditionPolarity.isInversionOf(absolute.notFilled(), canonical.filled()));
    }

    @Test
    void noCleanComplementYieldsEmptyAndNeverClaimsInversion() {
        // a field-list quantifier has no clean per-node complement in v1 → conservative empty
        Condition quantified = m.allFieldsFilled(NAME, FEE);
        assertEquals(Optional.empty(), ConditionPolarity.complement(quantified));
        assertFalse(ConditionPolarity.isInversionOf(quantified, quantified));
    }
}
