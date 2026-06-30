package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classifies <i>what kind</i> of change separates two condition ASTs — the coarse edit-script half of
 * {@code model diff}'s rule-logic review (MODEL-REVIEW-LEDGER MR1). It answers "did the logical
 * structure change, the referenced elements, an operator, or just a literal?", which is the triage a
 * reviewer wants on top of the before/after text.
 *
 * <p>Deliberately coarse and cheap: it reuses {@link ConditionReferences} for the reference set and a
 * small Condition-layer walk for the {@code operatorId} multiset + node count, so it never re-walks the
 * deep {@link Calculation} operand hierarchy. The precise, located edit script ("the {@code And} clause
 * referencing X was added") is the deferred remainder of MR1.
 */
public final class ConditionEdit {

    private ConditionEdit() {}

    /** The category of change between two conditions, in order of confidence (see {@link #classify}). */
    public enum Kind {
        /** The logical shape changed — a clause (conjunct/disjunct) was added or removed. */
        STRUCTURE,
        /** Same shape, but the referenced fields/groups differ — the rule reads different elements. */
        REFERENCES,
        /** Same shape and references, but an operator (comparison/logic/presence/pattern bit) changed. */
        OPERATOR,
        /** Same shape, references and operators — only a literal operand (a threshold, regex, value) moved. */
        OPERAND
    }

    /**
     * Classify the change from {@code base} to {@code head} (assumes they actually differ). Most-specific
     * first: a node-count change is a STRUCTURE edit (clause add/remove) regardless of anything else; then
     * a differing reference set is REFERENCES; then a differing operator multiset is OPERATOR; otherwise
     * the only thing left is an operand literal, OPERAND.
     */
    public static Kind classify(Condition base, Condition head) {
        if (count(base) != count(head)) {
            return Kind.STRUCTURE;
        }
        if (!ConditionReferences.of(base).equals(ConditionReferences.of(head))) {
            return Kind.REFERENCES;
        }
        if (!operatorIds(base).equals(operatorIds(head))) {
            return Kind.OPERATOR;
        }
        return Kind.OPERAND;
    }

    /** The number of condition nodes ({@code And}/{@code Or} recurse; every other node is a leaf). */
    private static int count(Condition c) {
        if (c instanceof LogicCondition l) {
            return 1 + count(l.left()) + count(l.right());
        }
        return 1;
    }

    /** The sorted multiset of {@code operatorId}s over the condition nodes — order-independent. */
    private static List<String> operatorIds(Condition c) {
        List<String> ids = new ArrayList<>();
        collectOperatorIds(c, ids);
        Collections.sort(ids);
        return ids;
    }

    private static void collectOperatorIds(Condition c, List<String> ids) {
        ids.add(c.operatorId());
        if (c instanceof LogicCondition l) {
            collectOperatorIds(l.left(), ids);
            collectOperatorIds(l.right(), ids);
        }
    }
}
