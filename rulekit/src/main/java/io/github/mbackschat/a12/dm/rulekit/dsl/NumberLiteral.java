package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;
import java.math.BigDecimal;

/** A typed numeric literal. Held as {@link BigDecimal} so the rendered scale is explicit and exact. */
public record NumberLiteral(BigDecimal value) implements Calculation {

    public static NumberLiteral of(long value) {
        return new NumberLiteral(BigDecimal.valueOf(value));
    }

    public static NumberLiteral of(String value) {
        return new NumberLiteral(new BigDecimal(value));
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }
}
