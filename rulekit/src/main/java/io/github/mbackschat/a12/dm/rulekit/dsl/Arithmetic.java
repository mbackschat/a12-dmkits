package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A binary arithmetic expression on numeric operands — {@code a + b}, {@code a - b}, {@code a * b},
 * {@code a / b}, {@code a ^ b} (KERNEL-SEMANTICS §computations; the kernel's {@code ICompositeOperation}
 * with a {@code CalcOp}). A {@link Calculation}, so it composes anywhere a numeric operand is expected
 * (e.g. the right side of a comparison: {@code [maxLength] < [minFractionalDigits] + 2}). Build via
 * {@link NumberExpr#plus(NumberExpr)} and friends.
 */
public record Arithmetic(Op op, Calculation left, Calculation right) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    /** The arithmetic operators (kernel {@code CalcOp}) with their DSL symbols. */
    public enum Op {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), POWER("^");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}
