package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A numeric field extracted from a date — {@code YearFromDate(d)} / {@code MonthFromDate(d)} /
 * {@code DayFromDate(d)} / {@code QuarterFromDate(d)} (the number-valued members of the kernel
 * {@code DateExtractionOperationName}). A {@link Calculation} of {@link ValueKind#NUMBER}; the source
 * date renders as a bare entity reference. Build via {@link Dsl#yearFromDate} and friends.
 */
public record DateExtraction(Unit unit, Calculation source) implements Calculation {

    /** The number-valued date extractions, each carrying its catalog operator-id. */
    public enum Unit {
        DAY("DayFromDate"),
        MONTH("MonthFromDate"),
        YEAR("YearFromDate"),
        QUARTER("QuarterFromDate");

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
