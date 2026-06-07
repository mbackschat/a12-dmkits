package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A temporal value an extraction can read from — a {@link DateOperand date}, a {@link TimeOperand
 * time}, or a {@link DateTimeRef date-time}. The kernel's component extractions ({@code YearFromDate},
 * {@code HoursFromTime}, …) accept a date-, time-, or date-time-typed source; this is the common
 * supertype so one builder serves them all. The source always renders BARE (an entity reference).
 */
public sealed interface TemporalOperand permits DateOperand, TimeOperand, DateTimeRef {

    /** This operand as a value expression (a {@link FieldValue}, {@link Today}, or a derived value). */
    Calculation expression();
}
