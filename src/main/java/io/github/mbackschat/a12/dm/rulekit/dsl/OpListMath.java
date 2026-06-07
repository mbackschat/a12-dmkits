package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The parameterless operand-list math functions — {@code Abs(value)}, {@code Min(v1, v2, …)},
 * {@code Max(v1, v2, …)} (the kernel's {@code IOpListOperation} members other than the rounding family,
 * which is {@link Rounding}). A number-valued {@link Calculation} over numeric value <em>expressions</em>
 * (distinct from the field-list {@link ListAggregate} {@code MaxValue}/{@code MinValue}, which aggregate
 * a repetition list). Operands render as values ({@code Min([A], [B])}), not bare entity refs.
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
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return op.operatorId();
    }
}
