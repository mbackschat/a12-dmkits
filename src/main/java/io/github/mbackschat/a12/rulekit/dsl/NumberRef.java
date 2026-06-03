package io.github.mbackschat.a12.rulekit.dsl;

import java.math.BigDecimal;

/**
 * A typed handle to a number field. Only number fields get the comparison builders, so a
 * type-illegal ordering can't be written (§5b). The declared {@code scale} (decimal places) is read
 * from the model and will feed the field-vs-field scale pre-check (D13) once comparisons take field
 * operands. All comparisons delegate to {@link NumberExpr} — one implementation, every overload
 * (long / scale-faithful String / BigDecimal).
 */
public record NumberRef(FieldRef field, int scale) {

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    public FieldValue value() {
        return field.value();
    }

    /** This field's value as a comparable numeric expression. */
    public NumberExpr asNumber() {
        return new NumberExpr(value());
    }

    public Condition lt(long literal) {
        return asNumber().lt(literal);
    }

    public Condition lt(String literal) {
        return asNumber().lt(literal);
    }

    public Condition lt(BigDecimal literal) {
        return asNumber().lt(literal);
    }

    public Condition le(long literal) {
        return asNumber().le(literal);
    }

    public Condition le(String literal) {
        return asNumber().le(literal);
    }

    public Condition le(BigDecimal literal) {
        return asNumber().le(literal);
    }

    public Condition gt(long literal) {
        return asNumber().gt(literal);
    }

    public Condition gt(String literal) {
        return asNumber().gt(literal);
    }

    public Condition gt(BigDecimal literal) {
        return asNumber().gt(literal);
    }

    public Condition ge(long literal) {
        return asNumber().ge(literal);
    }

    public Condition ge(String literal) {
        return asNumber().ge(literal);
    }

    public Condition ge(BigDecimal literal) {
        return asNumber().ge(literal);
    }

    public Condition eq(long literal) {
        return asNumber().eq(literal);
    }

    public Condition eq(String literal) {
        return asNumber().eq(literal);
    }

    public Condition eq(BigDecimal literal) {
        return asNumber().eq(literal);
    }

    public Condition ne(long literal) {
        return asNumber().ne(literal);
    }

    public Condition ne(String literal) {
        return asNumber().ne(literal);
    }

    public Condition ne(BigDecimal literal) {
        return asNumber().ne(literal);
    }
}
