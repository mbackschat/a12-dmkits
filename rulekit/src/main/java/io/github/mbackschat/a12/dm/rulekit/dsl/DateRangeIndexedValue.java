package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code [Range For key]} — a date-range field's cell reached through a {@link SemanticIndex semantic index}
 * (KERNEL-SEMANTICS §9). Obtain from {@link DateRangeRef#forIndex(String)} or
 * {@link DateRangeRef#forIndex(FieldRef)}.
 *
 * <p>It is its own arm of {@link DateRangeOperand}, rather than a plain {@link DateRangeExpr}, because the
 * bound extractors accept a <em>field-like</em> range and reject a computed one: keeping the keyed cell
 * distinguishable is what lets {@link Dsl#startOfDateRange(DateRangeIndexedValue)} exist without also
 * admitting {@code StartOfDateRange(FirstFilledValue(…))} (DEV-API-GAPS DG39).
 */
public record DateRangeIndexedValue(IndexedFieldValue index) implements DateRangeOperand {

    @Override
    public Calculation expression() {
        return index;
    }

    /** This keyed cell as a PRESENCE handle — {@code filled()} / {@code notFilled()}. */
    public SemanticIndex semanticIndex() {
        return new SemanticIndex(index);
    }
}
