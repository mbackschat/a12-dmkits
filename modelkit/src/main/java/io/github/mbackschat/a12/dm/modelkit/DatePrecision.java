package io.github.mbackschat.a12.dm.modelkit;

/**
 * The precision of a {@code DateType} field — mirrors the kernel's {@code IDateType.DatePrecision}.
 * {@link #FULL} (the default) requires all three date parts; the others make the field a
 * <em>partially known date</em> (a day/month/year part may be unknown, e.g. {@code 00.02.2024}),
 * which {@code ValueAsDate(field, FirstDay|LastDay)} resolves to a concrete date.
 */
public enum DatePrecision {
    /** All three date parts must be specified (the kernel default — not emitted in DM-JSON). */
    FULL,
    /** The day part may be unknown (e.g. {@code 00.02.2024}). */
    DAY_OPTIONAL,
    /** The day and month parts may be unknown (e.g. {@code 00.00.2024}). */
    MONTH_OPTIONAL,
    /** The day, month, and year parts may be unknown. */
    YEAR_OPTIONAL;

    /** The DM-JSON value (the kernel enum name). */
    public String jsonValue() {
        return name();
    }
}
