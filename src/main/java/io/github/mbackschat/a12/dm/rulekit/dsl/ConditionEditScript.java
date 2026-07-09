package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>located</b> edit script between two condition ASTs (MODEL-REVIEW MR1) — the precise refinement on
 * top of {@link ConditionEdit}'s coarse category: <i>which</i> clause/operand changed, not just the kind of
 * change ("threshold 1000→2000 on [Amount]", "added clause: FieldFilled(Approved)").
 *
 * <p>The one correctness trap is {@code And}/{@code Or} <b>re-association</b>: the kernel logic tree is
 * binary, so inserting a conjunct into a chain reshapes the tree and a naive positional (left/right) diff
 * would mislocate every clause after the insertion point. This walker instead <b>flattens each same-operator
 * chain</b> into its operand list and matches operands by content — so an inserted clause is reported as one
 * {@code ADDED}, the untouched clauses stay untouched, and a pure re-association (same operands, different
 * nesting) is <b>no change at all</b>. Where an alignment is genuinely ambiguous (a mixed restructuring, an
 * operand with no clean correspondent) it <b>degrades</b> to {@link ConditionEdit}'s coarse kind for that
 * region rather than guess — it never mislocates.
 *
 * <p>The {@code address} is the breadcrumb of enclosing logic operators (e.g. {@code ""} at the root,
 * {@code "And"} for a conjunct, {@code "And/Or"} for a disjunct nested in a conjunction) — stable under
 * re-association, unlike raw left/right indices.
 */
public record ConditionEditScript(List<LocatedEdit> edits) {

    public ConditionEditScript {
        edits = List.copyOf(edits);
    }

    /** What happened to a node/subtree at {@link LocatedEdit#address()}. */
    public enum Op { ADDED, REMOVED, REPLACED }

    /**
     * One located change: its {@link Op}, the logic-operator {@code address}, the affected node's
     * {@code nodeKind} (operator id), the {@code before}/{@code after} rendered text (one side {@code null}
     * for an add/remove), and a human {@code summary}.
     */
    public record LocatedEdit(Op op, String address, String nodeKind, String before, String after, String summary) {}

    /** The located edit script from {@code base} to {@code head} (empty when they are semantically equal). */
    public static ConditionEditScript diff(Condition base, Condition head) {
        List<LocatedEdit> edits = new ArrayList<>();
        walk(base, head, "", edits, new DslRenderer());
        return new ConditionEditScript(edits);
    }

    private static void walk(Condition base, Condition head, String path, List<LocatedEdit> edits, DslRenderer r) {
        if (base.equals(head)) {
            return; // unchanged subtree
        }
        LogicCondition.Op op = chainOp(base, head);
        if (op != null) {
            // one side (or both) is a same-op chain: flatten both by it (a leaf flattens to a singleton) so a
            // clause added to / removed from the chain is located, never mislocated by re-association
            matchChain(flatten(base, op), flatten(head, op), path, op, edits, r);
            return;
        }
        if (base.getClass() == head.getClass() && !(base instanceof LogicCondition)) {
            edits.add(replacedLeaf(base, head, path, r));
            return;
        }
        edits.add(coarse(base, head, path, r)); // different logic ops / kinds → degrade to the coarse category
    }

    /**
     * The chain operator to flatten by, or {@code null}: the shared op when both are the same logic; the lone
     * logic side's op when only one is a chain (the other flattens to a singleton — a clause added/removed);
     * {@code null} when both are leaves, or the two logic ops differ (a genuine restructure → coarse).
     */
    private static LogicCondition.Op chainOp(Condition base, Condition head) {
        LogicCondition.Op b = base instanceof LogicCondition l ? l.op() : null;
        LogicCondition.Op h = head instanceof LogicCondition l ? l.op() : null;
        if (b != null && h != null) {
            return b == h ? b : null;
        }
        return b != null ? b : h;
    }

    /** The operands of a maximal same-operator chain (so {@code A And (B And C)} flattens to {@code [A,B,C]}). */
    private static List<Condition> flatten(Condition c, LogicCondition.Op op) {
        if (c instanceof LogicCondition l && l.op() == op) {
            List<Condition> out = new ArrayList<>(flatten(l.left(), op));
            out.addAll(flatten(l.right(), op));
            return out;
        }
        return List.of(c);
    }

    /**
     * Diffs two operand lists of the same-operator chain by CONTENT (never by position, which re-association
     * scrambles): matched operands are unchanged; exactly one-removed-and-one-added is the edited clause
     * (recurse to locate the edit within it); otherwise each unmatched operand is a clause add/remove.
     */
    private static void matchChain(List<Condition> baseOps, List<Condition> headOps, String path,
            LogicCondition.Op op, List<LocatedEdit> edits, DslRenderer r) {
        String childPath = path.isEmpty() ? op.operatorId() : path + "/" + op.operatorId();
        List<Condition> headRemaining = new ArrayList<>(headOps);
        List<Condition> removed = new ArrayList<>();
        for (Condition b : baseOps) {
            if (!headRemaining.remove(b)) {
                removed.add(b); // no content-equal operand in head → this clause left
            }
        }
        List<Condition> added = headRemaining; // head operands with no content-equal in base → arrived
        if (removed.isEmpty() && added.isEmpty()) {
            return; // same operands, different nesting — a pure re-association is semantically no change
        }
        if (removed.size() == 1 && added.size() == 1) {
            walk(removed.get(0), added.get(0), childPath, edits, r); // the one edited clause — locate within it
            return;
        }
        for (Condition b : removed) {
            edits.add(new LocatedEdit(Op.REMOVED, childPath, b.operatorId(), r.render(b), null,
                    "removed clause: " + r.render(b)));
        }
        for (Condition h : added) {
            edits.add(new LocatedEdit(Op.ADDED, childPath, h.operatorId(), null, r.render(h),
                    "added clause: " + r.render(h)));
        }
    }

    /** A same-kind leaf that changed: extract a threshold move when only one comparison operand differs. */
    private static LocatedEdit replacedLeaf(Condition base, Condition head, String path, DslRenderer r) {
        String before = r.render(base);
        String after = r.render(head);
        String summary;
        if (base instanceof ComparisonCondition cb && head instanceof ComparisonCondition ch
                && cb.operator() == ch.operator() && cb.left().equals(ch.left()) && !cb.right().equals(ch.right())) {
            summary = "threshold " + r.render(cb.right()) + "→" + r.render(ch.right()) + " on " + r.render(cb.left());
        } else {
            summary = before + " → " + after;
        }
        return new LocatedEdit(Op.REPLACED, path, head.operatorId(), before, after, summary);
    }

    /** The graceful degrade: one edit carrying {@link ConditionEdit}'s coarse category + before/after text. */
    private static LocatedEdit coarse(Condition base, Condition head, String path, DslRenderer r) {
        String kind = switch (ConditionEdit.classify(base, head)) {
            case STRUCTURE -> "restructured";
            case REFERENCES -> "references changed";
            case OPERATOR -> "operator changed";
            case OPERAND -> "operand changed";
        };
        String before = r.render(base);
        String after = r.render(head);
        return new LocatedEdit(Op.REPLACED, path, "?", before, after, kind + " (" + before + " → " + after + ")");
    }
}
