package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Sum(…)} / {@code MaxValue(…)} / {@code MinValue(…)} — numeric aggregates over an entity
 * list, typically a flattened repetition list (KERNEL-SEMANTICS §9: {@code Sum} substitutes 0 for
 * unspecified fields; {@code MaxValue}/{@code MinValue} ignore them). Kernel slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS §9): number operands only ({@code MVK_NO_NUMBER} for Sum,
 * {@code MVK_NOT_SORTABLE} for Max/Min — their date variant rides the date family, item 9), more
 * than one field or a repetition list ({@code MVK_PARAMSIZE_INVALIDN}), no duplicates
 * ({@code MVK_DUPLICATE_PARAM1}) — all pre-checked here.
 */
public record ListAggregate(AggOp op, List<FieldOperand> operands) implements Calculation {

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
        if (operands.size() < 2 && operands.stream().noneMatch(o -> o instanceof FieldListRef)) {
            throw new IllegalArgumentException(op.operatorId() + " needs more than one field or a"
                    + " repetition list (MVK_PARAMSIZE_INVALIDN)");
        }
        for (FieldOperand o : operands) {
            if (o.kind() != ValueKind.NUMBER) {
                String code = op == AggOp.SUM ? "MVK_NO_NUMBER" : "MVK_NOT_SORTABLE";
                throw new IllegalArgumentException(op.operatorId() + " takes number fields; "
                        + o.renderedPath() + " is " + o.kind() + " (" + code
                        + "; the date variant of Max/Min lands with the date family)");
            }
        }
        checkNoDuplicates(op.operatorId(), operands);
    }

    static void checkNoDuplicates(String opName, List<FieldOperand> operands) {
        var seen = new java.util.HashSet<String>();
        for (FieldOperand o : operands) {
            if (!seen.add(o.renderedPath())) {
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
