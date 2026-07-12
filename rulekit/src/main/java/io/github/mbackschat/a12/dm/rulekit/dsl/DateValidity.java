package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code Valid(dateConstruct)} / {@code Invalid(dateConstruct)} — the kernel's date-validity condition
 * (its {@code IDateValidityCondition}): true iff the constructed date is a real calendar date (e.g.
 * {@code Date(31, 2, 2024)} is invalid). The argument is typically a {@link DateConstruction}. Build
 * via {@link Dsl#valid} / {@link Dsl#invalid}.
 */
public record DateValidity(boolean valid, Calculation dateConstruct) implements Condition {

    @Override
    public String operatorId() {
        return valid ? "Valid" : "Invalid";
    }
}
