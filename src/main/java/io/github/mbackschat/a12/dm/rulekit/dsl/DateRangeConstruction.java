package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code DateRange(start, end)} — the kernel's date-range <b>constructor</b> (its
 * {@code IDateRangeConstructionOperation}): builds a date-range value from a start and an end date.
 * A {@link Calculation} of {@link ValueKind#DATE_RANGE}.
 *
 * <p><b>Computation-only.</b> A constructed date range has no legal use site in a <em>condition</em>
 * (the overlap predicates take date-range <em>fields</em>, {@code Start/EndOfDateRange} need a field, and
 * "DateRange cannot be nested"); its one home is a <em>computation operation</em> assigning a date-range
 * field ({@code CoverageWindow = DateRange(OrderDate, DeliveryDate)}). Build via {@link Dsl#dateRange}.
 * The kernel requires both operands to be format-compatible date fields (its call — F4/N7).
 */
public record DateRangeConstruction(Calculation start, Calculation end) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.DATE_RANGE;
    }

    public String operatorId() {
        return "DateRange";
    }
}
