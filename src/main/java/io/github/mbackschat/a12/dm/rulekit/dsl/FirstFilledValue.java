package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FirstFilledValue(/Group* then Field)} — the first specified value over a flattened repetition
 * list, in row order; unspecified rows are skipped (KERNEL-SEMANTICS §9, a {@code feldListenOp} sibling
 * of {@code Sum}). Polymorphic in its result: it yields the scope's element type, so this node's
 * {@link #kind()} follows the scope. Wrapping it in a {@link NumberExpr} (number scope) or {@link DateExpr}
 * (date scope) makes it a comparable operand — build via {@link Dsl#firstFilledNumber} / {@link Dsl#firstFilledDate}.
 */
public record FirstFilledValue(FieldListRef scope) implements Calculation {

    @Override
    public ValueKind kind() {
        return scope.kind();
    }

    public String operatorId() {
        return "FirstFilledValue";
    }
}
