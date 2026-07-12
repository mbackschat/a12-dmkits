package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * String concatenation — {@code [First] + " " + [Last]} — the kernel's {@code +} ({@code ICompositeOperation}
 * with {@code CalcOp.ADD}) over string-valued operands. Like {@link Arithmetic} the {@code +} is an
 * intrinsic symbol, not a catalog keyword, so this node carries no operator-id and renders by joining
 * its parts with {@code +}. Each part is a string-valued expression (a field value, a string literal,
 * or a nested concatenation). Build via {@link StringExpr#plus}.
 */
public record Concatenation(List<Calculation> parts) implements Calculation {

    public Concatenation {
        parts = List.copyOf(parts);
        if (parts.size() < 2) {
            throw new IllegalArgumentException("a concatenation needs at least two parts, got " + parts.size());
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.STRING;
    }
}
