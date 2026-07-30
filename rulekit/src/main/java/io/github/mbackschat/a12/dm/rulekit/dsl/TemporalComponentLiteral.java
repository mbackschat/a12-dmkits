package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Objects;

/**
 * A quoted numeric component whose lexical digits are preserved exactly ({@code "01"}, {@code "00"}, …).
 * Use {@link Dsl#temporalComponent(String)}; parsing through {@link NumberExpr} would normalize those significant
 * leading zeroes before the component is rendered.
 */
public record TemporalComponentLiteral(String value) implements TemporalComponent {

    public TemporalComponentLiteral {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public Calculation expression() {
        return new StringLiteral(value);
    }
}
