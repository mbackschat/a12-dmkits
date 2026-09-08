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
 * ({@code MVK_DUPLICATE_PARAM1}) — a wildcarded operand may repeat, mirroring the kernel skipping
 * wildcarded refs in {@code checkDuplicatesInDirectRefs} — all pre-checked here.
 *
 * <p>An operand may also be a <b>group scope</b> ({@link GroupListRef}), which the kernel expands to the
 * group's contained fields; the two gates that behave differently for it, and why they are delegated rather
 * than pre-checked, are on {@link AggregateOperand}.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

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
            throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDN, op.operatorId() + " needs more than one field, a"
                    + " repetition list, or a group scope");
        }
        // Ahead of the kind loop, because the kernel's own routine checks duplicates first: it answers
        // MVK_DUPLICATE_PARAM2 for a group scope beside a field inside it even when that field is the wrong
        // kind, and reporting the kind error there would disagree with it on input both refuse.
        DuplicateGate.rejectDuplicates(op.operatorId(), operands);
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
    }

    private static void requireNumber(AggOp op, FieldOperand operand) {
        if (operand.kind() != ValueKind.NUMBER) {
            KernelCode code = op == AggOp.SUM ? KernelCode.MVK_NO_NUMBER : KernelCode.MVK_NOT_SORTABLE;
            throw Refusals.of(code, op.operatorId() + " takes number fields; " + operand.renderedPath()
                    + " is " + operand.kind() + "; the date variant of Max/Min lands with the date family");
        }
    }

    /** Whether one operand alone satisfies the param law — a repetition list or a group scope does. */
    static boolean satisfiesArityAlone(AggregateOperand operand) {
        return operand instanceof FieldListRef || operand instanceof GroupListRef;
    }


    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return op.operatorId();
    }
}
