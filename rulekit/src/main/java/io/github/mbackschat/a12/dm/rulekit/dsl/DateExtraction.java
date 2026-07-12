package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A numeric component extracted from a date or time — the number-valued members of the kernel
 * {@code DateExtractionOperationName}: {@code YearFromDate}/{@code MonthFromDate}/{@code DayFromDate}/
 * {@code QuarterFromDate} (from a date or date-time) and {@code HoursFromTime}/{@code MinutesFromTime}/
 * {@code SecondsFromTime} (from a time or date-time). A {@link Calculation} of {@link ValueKind#NUMBER};
 * the source renders as a bare entity reference. Build via {@link Dsl#yearFromDate} and friends.
 */
public record DateExtraction(Unit unit, Calculation source) implements Calculation {

    /** The number-valued date/time extractions, each carrying its catalog operator-id. */
    public enum Unit {
        DAY("DayFromDate"),
        MONTH("MonthFromDate"),
        YEAR("YearFromDate"),
        QUARTER("QuarterFromDate"),
        HOURS("HoursFromTime"),
        MINUTES("MinutesFromTime"),
        SECONDS("SecondsFromTime");

        private final String operatorId;

        Unit(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return unit.operatorId();
    }
}
