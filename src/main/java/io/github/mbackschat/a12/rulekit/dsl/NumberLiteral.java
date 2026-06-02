package io.github.mbackschat.a12.rulekit.dsl;

import java.math.BigDecimal;

/** A typed numeric literal. Held as {@link BigDecimal} so the rendered scale is explicit and exact. */
public record NumberLiteral(BigDecimal value) implements Calculation {

    public static NumberLiteral of(long value) {
        return new NumberLiteral(BigDecimal.valueOf(value));
    }

    public static NumberLiteral of(String value) {
        return new NumberLiteral(new BigDecimal(value));
    }
}
