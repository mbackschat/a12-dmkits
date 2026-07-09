package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEditScript.LocatedEdit;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEditScript.Op;

/**
 * {@link ConditionEditScript} — the LOCATED edit script (MODEL-REVIEW MR1). It must pin down <i>which</i>
 * clause/operand changed, and — the ratified must-prove — a clause INSERTED INTO an And/Or chain (which
 * re-associates the binary tree) must be located as one add, NEVER mislocating a downstream clause; where an
 * alignment is genuinely ambiguous it degrades to {@link ConditionEdit}'s coarse kind for that region.
 */
class ConditionEditScriptTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String FEE = "/Subscription/Addons/MonthlyFee";
    private static final String BASE = "/Subscription/Billing/BaseFee";
    private static final String EFF = "/Subscription/Billing/EffectiveFee";

    @Test
    void aThresholdChangeIsLocatedToTheOperand() {
        ConditionEditScript script = ConditionEditScript.diff(m.num(FEE).lt(1000), m.num(FEE).lt(2000));
        assertEquals(1, script.edits().size(), () -> "one located edit, got: " + script.edits());
        LocatedEdit edit = script.edits().get(0);
        assertEquals(Op.REPLACED, edit.op());
        assertTrue(edit.summary().contains("threshold") && edit.summary().contains("1000")
                && edit.summary().contains("2000"), () -> "a located threshold move: " + edit.summary());
    }

    @Test
    void aClauseInsertedIntoAChainIsLocatedAndNeverMislocatesADownstreamClause() {
        // base: A And B.  head: (A And C) And B — C inserted, which RE-ASSOCIATES the binary tree.
        // A naive left/right diff would mislocate B; the flatten-and-match must report exactly one ADD (C).
        Condition a = m.num(FEE).gt(0);
        Condition b = m.num(BASE).gt(0);
        Condition c = m.num(EFF).gt(0);
        ConditionEditScript script = ConditionEditScript.diff(a.and(b), a.and(c).and(b));

        assertEquals(1, script.edits().size(), () -> "exactly one located edit (the insertion): " + script.edits());
        LocatedEdit edit = script.edits().get(0);
        assertEquals(Op.ADDED, edit.op(), () -> "the inserted clause is an ADD: " + edit);
        assertTrue(edit.after().contains("EffectiveFee"), () -> "the located clause is C: " + edit);
        // the untouched clauses must NOT appear anywhere — no mislocation onto a downstream clause
        assertTrue(script.edits().stream().noneMatch(e -> text(e).contains("MonthlyFee")),
                () -> "clause A must not be touched: " + script.edits());
        assertTrue(script.edits().stream().noneMatch(e -> text(e).contains("BaseFee")),
                () -> "downstream clause B must not be mislocated: " + script.edits());
    }

    @Test
    void aPureReassociationIsNoChange() {
        // A And (B And C)  vs  (A And B) And C — same operands, different nesting → semantically identical
        Condition a = m.num(FEE).gt(0);
        Condition b = m.num(BASE).gt(0);
        Condition c = m.num(EFF).gt(0);
        assertTrue(ConditionEditScript.diff(a.and(b.and(c)), a.and(b).and(c)).edits().isEmpty(),
                "re-association is not a semantic edit");
    }

    @Test
    void aRemovedClauseCollapsingTheChainIsLocated() {
        // base: A And B.  head: A — the conjunct B was removed (the And collapses to a single leaf)
        Condition a = m.num(FEE).gt(0);
        Condition b = m.num(BASE).gt(0);
        List<LocatedEdit> edits = ConditionEditScript.diff(a.and(b), a).edits();
        assertEquals(1, edits.size(), () -> "one located edit: " + edits);
        assertEquals(Op.REMOVED, edits.get(0).op());
        assertTrue(edits.get(0).before().contains("BaseFee"), () -> "the removed clause is B: " + edits.get(0));
    }

    @Test
    void anAmbiguousRestructureDegradesToTheCoarseKind() {
        // And → Or at the root is a genuine structural change with no clean per-clause alignment → degrade
        Condition base = m.num(FEE).gt(0).and(m.num(BASE).gt(0));
        Condition head = m.num(FEE).gt(0).or(m.num(BASE).gt(0));
        List<LocatedEdit> edits = ConditionEditScript.diff(base, head).edits();
        assertEquals(1, edits.size(), () -> "a single degraded edit for the region: " + edits);
        assertTrue(edits.get(0).summary().contains("restructured") || edits.get(0).summary().contains("operator"),
                () -> "degrades to the coarse category: " + edits.get(0));
    }

    @Test
    void identicalConditionsYieldAnEmptyScript() {
        assertTrue(ConditionEditScript.diff(m.num(FEE).lt(1000), m.num(FEE).lt(1000)).edits().isEmpty(),
                "no change → no edits");
    }

    private static String text(LocatedEdit e) {
        return (e.before() == null ? "" : e.before()) + " " + (e.after() == null ? "" : e.after());
    }
}
