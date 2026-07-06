package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEdit.Kind;

/**
 * {@link ConditionEdit} — the coarse classification of <i>what</i> changed between two condition ASTs,
 * the edit-script half of MODEL-REVIEW-GAPS MR1. It reuses {@link ConditionReferences} for the
 * reference set and a Condition-layer {@code operatorId}/node-count walk, so it never re-walks the deep
 * {@link Calculation} hierarchy. Order of confidence: structure (clause add/remove) → references →
 * operator → operand (a literal within an unchanged shape).
 */
class ConditionEditTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String FEE = "/Subscription/Addons/MonthlyFee";
    private static final String BASE = "/Subscription/Billing/BaseFee";

    @Test
    void aLiteralThresholdChangeIsOperand() {
        assertEquals(Kind.OPERAND, ConditionEdit.classify(m.num(FEE).lt(1000), m.num(FEE).lt(2000)));
    }

    @Test
    void aComparisonOperatorChangeIsOperator() {
        // < → <= keeps refs/structure but changes the operator (and is NOT a polarity complement)
        assertEquals(Kind.OPERATOR, ConditionEdit.classify(m.num(FEE).lt(1000), m.num(FEE).le(1000)));
    }

    @Test
    void aPresenceFlipWithinALargerTreeIsOperator() {
        Condition base = m.num(FEE).filled().and(m.num(BASE).lt(1000));
        Condition head = m.num(FEE).notFilled().and(m.num(BASE).lt(1000));
        assertEquals(Kind.OPERATOR, ConditionEdit.classify(base, head));
    }

    @Test
    void guardingADifferentFieldIsReferences() {
        assertEquals(Kind.REFERENCES, ConditionEdit.classify(m.num(FEE).lt(0), m.num(BASE).lt(0)));
    }

    @Test
    void addingAConjunctIsStructure() {
        Condition base = m.num(FEE).lt(1000);
        Condition head = m.num(FEE).lt(1000).and(m.num(BASE).gt(0));
        assertEquals(Kind.STRUCTURE, ConditionEdit.classify(base, head));
    }

    @Test
    void structureWinsEvenWhenTheNewClauseAddsAReference() {
        // a clause add that also brings a new ref reads as STRUCTURE (more informative than REFERENCES)
        Condition base = m.num(FEE).lt(1000);
        Condition head = m.num(FEE).lt(1000).and(m.str("/Subscription/Billing/InvoiceEmail").filled());
        assertEquals(Kind.STRUCTURE, ConditionEdit.classify(base, head));
    }
}
