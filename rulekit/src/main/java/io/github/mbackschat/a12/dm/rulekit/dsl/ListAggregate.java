package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Sum(…)} / {@code MaxValue(…)} / {@code MinValue(…)} — numeric aggregates over an entity
 * list, typically a flattened repetition list (KERNEL-SEMANTICS §9: {@code Sum} substitutes 0 for
 * unspecified fields; {@code MaxValue}/{@code MinValue} ignore them). Kernel slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS §9): number operands only ({@code MVK_NO_NUMBER} for Sum,
 * {@code MVK_NOT_SORTABLE} for Max/Min — their date variant rides the date family, item 9), more
 * than one field or a repetition list ({@code MVK_PARAMSIZE_INVALIDN}), no repeated DIRECT field
 * ({@code MVK_DUPLICATE_PARAM1}) — an <em>identical</em> wildcarded operand may repeat, mirroring the kernel
 * skipping wildcarded refs in {@code checkDuplicatesInDirectRefs} — all pre-checked here. What the star
 * exempts is that one gate and no more: a star does not make an operand exempt from duplicate checking as
 * such, and the indirect-overlap arm ({@code MVK_DUPLICATE_PARAM2}) still refuses two operands where one
 * strictly contains the other, whichever side carries it — measured in both argument orders by
 * {@code rulekit.validate.laws.AggregateGroupOperandLawsTest} ({@link AggregateOperand}).
 *
 * <p>An operand may also be a <b>group scope</b> ({@link GroupListRef}), which the kernel expands to the
 * group's contained fields; the two gates that behave differently for it, and why they are delegated rather
 * than pre-checked, are on {@link AggregateOperand}.
 */
public record ListAggregate(AggOp op, List<AggregateOperand> operands) implements Calculation {

    public enum AggOp {
        SUM("Sum"),
        MAX_VALUE("MaxValue"),
        MIN_VALUE("MinValue");

        private final String operatorId;

        AggOp(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public ListAggregate {
        operands = List.copyOf(operands);
        if (operands.size() < 2 && operands.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
            throw new IllegalArgumentException(op.operatorId() + " needs more than one field, a"
                    + " repetition list, or a group scope (MVK_PARAMSIZE_INVALIDN)");
        }
        for (AggregateOperand o : operands) {
            switch (o) {
                case FieldOperand f -> requireNumber(op, f);
                // A group carries no kind of its own: the rule applies to the fields the kernel EXPANDS it
                // to, whose extent recurses into nested subgroups. Delegated rather than re-implemented —
                // see AggregateOperand. An index-selected group is the same shape (still no kind of its
                // own — the index only picks WHICH repetition, not what it expands to).
                case GroupListRef ignored -> { }
                case IndexedGroupValue ignored -> { }
            }
        }
        checkNoDuplicates(op.operatorId(), operands);
    }

    private static void requireNumber(AggOp op, FieldOperand operand) {
        if (operand.kind() != ValueKind.NUMBER) {
            String code = op == AggOp.SUM ? "MVK_NO_NUMBER" : "MVK_NOT_SORTABLE";
            throw new IllegalArgumentException(op.operatorId() + " takes number fields; "
                    + operand.renderedPath() + " is " + operand.kind() + " (" + code
                    + "; the date variant of Max/Min lands with the date family)");
        }
    }

    /** Whether one operand alone satisfies the param law — a repetition list or a group scope does. */
    static boolean satisfiesArityAlone(AggregateOperand operand) {
        return operand instanceof FieldListRef || operand instanceof GroupListRef;
    }

    static void checkNoDuplicates(String opName, List<AggregateOperand> operands) {
        var seen = new java.util.HashSet<String>();
        for (AggregateOperand o : operands) {
            // The kernel's checkDuplicatesInDirectRefs continues past ref.hasWildCards() outside HModel, so a
            // flattened repetition list may occupy several authored slots and is expanded and consumed again at
            // each — only repeated DIRECT (non-wildcard) fields draw MVK_DUPLICATE_PARAM1. This mirrors the
            // value-list identity in Dsl.referenceIdentity (a FieldListRef scope has no dedup identity).
            //
            // A GroupListRef has no dedup identity either, and the two halves of that are measured separately:
            // the same starred group listed twice is ACCEPTED, while a group beside its own descendant is
            // MVK_DUPLICATE_PARAM2 — an INDIRECT overlap this direct check never saw, and which stays
            // kernel-arbitrated because deciding it here would mean owning the expansion's extent.
            // The `$` outer-iteration correlation is part of the identity, exactly as it is in
            // FirstFilledValue's richer EntityIdentity: `$path` reads the outer row and `path` the current
            // one, so the two are different operands and may share a slot list.
            //
            // An indexed field (IndexedFieldValue) behaves like a single field entity here too: the same
            // field under two different index keys is NOT a duplicate, so identity folds
            // in the key (and every $ correlation in the shape) via the shared IndexedFieldValue#duplicateIdentity,
            // rather than repeating that compound computation here (see Dsl.referenceIdentity's identical arm).
            // An indexed GROUP (IndexedGroupValue) is the same shape one level up, sharing the identical
            // duplicateIdentity contract.
            String identity = switch (o) {
                case FieldListRef ignored -> null;
                case GroupListRef ignored -> null;
                case FieldRef f -> (f.correlated() ? "$" : "") + f.renderedPath();
                case IndexedFieldValue indexed -> indexed.duplicateIdentity();
                case IndexedGroupValue indexed -> indexed.duplicateIdentity();
            };
            if (identity == null) {
                continue;
            }
            if (!seen.add(identity)) {
                throw new IllegalArgumentException(opName + ": '" + o.renderedPath()
                        + "' is listed more than once (MVK_DUPLICATE_PARAM1)");
            }
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return op.operatorId();
    }
}
