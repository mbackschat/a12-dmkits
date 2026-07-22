package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The parameterless operand-list math functions — {@code Abs(value)}, {@code Min(v1, v2, …)},
 * {@code Max(v1, v2, …)} (the kernel's {@code IOpListOperation} members other than the rounding family,
 * which is {@link Rounding}). A {@link Calculation} over value <em>expressions</em>: {@code Abs} is
 * number-valued, while {@code Min}/{@code Max} carry their operands' kind — NUMBER over number expressions,
 * or DATE/TIME/DATETIME over homogeneous temporal operands (the kernel's {@code DateCombiner},
 * KERNEL-FINDINGS §6); see {@link #kind()}.
 * Distinct from the field-list {@link ListAggregate} {@code MaxValue}/{@code MinValue}, which aggregate a
 * repetition list. Operands render as values ({@code Min([A], [B])}), not bare entity refs.
 *
 * <p>{@code Abs} takes exactly one operand; {@code Min}/{@code Max} take one or more.
 */
public record OpListMath(Op op, List<Calculation> operands) implements Calculation {

    /** The three operand-list functions, each carrying its catalog operator-id. */
    public enum Op {
        ABS("Abs"),
        MIN("Min"),
        MAX("Max");

        private final String operatorId;

        Op(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public OpListMath {
        operands = List.copyOf(operands);
        if (operands.isEmpty()) {
            throw new IllegalArgumentException(op + " needs at least one operand");
        }
        if (op == Op.ABS && operands.size() != 1) {
            throw new IllegalArgumentException("Abs takes exactly one operand, got " + operands.size());
        }
        ValueKind kind = resultKind(operands.getFirst());
        // BaseYear's public node reports its primary DATE role, but Dsl.baseYearNumber() selects the
        // kernel's numeric role before reaching Abs (the same polymorphic exception Computation owns).
        if (op == Op.ABS && kind != ValueKind.NUMBER && !(operands.getFirst() instanceof BaseYear)) {
            throw new IllegalArgumentException("Abs needs a NUMBER operand, got " + kind);
        }
        if (op != Op.ABS && kind != ValueKind.NUMBER && kind != ValueKind.DATE
                && kind != ValueKind.TIME && kind != ValueKind.DATE_TIME) {
            throw new IllegalArgumentException(op + " needs NUMBER or date-like operands, got " + kind);
        }
        for (Calculation operand : operands) {
            ValueKind operandKind = resultKind(operand);
            if (operandKind != kind) {
                throw new IllegalArgumentException(op + " needs operands of one kind, got " + kind
                        + " and " + operandKind);
            }
        }
    }

    @Override
    public ValueKind kind() {
        // Abs is number-only; Min/Max carry their operands' kind (NUMBER over number expressions,
        // DATE/TIME/DATETIME over temporal operands — the kernel's DateCombiner, KERNEL-FINDINGS §6).
        return op == Op.ABS ? ValueKind.NUMBER : resultKind(operands.getFirst());
    }

    private static ValueKind resultKind(Calculation operand) {
        return operand.kind() == ValueKind.DATE_FRAGMENT ? ValueKind.DATE : operand.kind();
    }

    public String operatorId() {
        return op.operatorId();
    }
}
