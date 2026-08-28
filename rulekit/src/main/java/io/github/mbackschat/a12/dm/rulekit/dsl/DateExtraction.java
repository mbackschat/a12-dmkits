package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A numeric component extracted from a date or time — the number-valued members of the kernel
 * {@code DateExtractionOperationName}: {@code YearFromDate}/{@code MonthFromDate}/{@code DayFromDate}/
 * {@code QuarterFromDate} (from a date, date-fragment or date-time) and {@code HoursFromTime}/
 * {@code MinutesFromTime}/{@code SecondsFromTime} (from a time or date-time). A {@link Calculation} of
 * {@link ValueKind#NUMBER}; the source renders as a bare entity reference. Build via
 * {@link Dsl#yearFromDate} and friends — those typed entry points only ever pass an already-correct-kind
 * operand, so the compact constructor's check is a no-op for them; it exists for a caller (a bridge reading
 * a parsed AST) that holds a bare {@link Calculation} instead of a {@code DateOperand}/{@code TimeOperand}/
 * {@code DateTimeOperand} — the {@code MVK_WRONG_DATE_FORMAT_FOR_OP} corrective the catalog states on
 * {@code SecondsFromTime} and its siblings.
 */
public record DateExtraction(Unit unit, Calculation source) implements Calculation {

    /** The number-valued date/time extractions, each carrying its catalog operator-id and admitted kinds. */
    public enum Unit {
        DAY("DayFromDate", ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.DATE_TIME),
        MONTH("MonthFromDate", ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.DATE_TIME),
        YEAR("YearFromDate", ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.DATE_TIME),
        QUARTER("QuarterFromDate", ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.DATE_TIME),
        HOURS("HoursFromTime", ValueKind.TIME, ValueKind.DATE_TIME),
        MINUTES("MinutesFromTime", ValueKind.TIME, ValueKind.DATE_TIME),
        SECONDS("SecondsFromTime", ValueKind.TIME, ValueKind.DATE_TIME);

        private final String operatorId;
        private final java.util.Set<ValueKind> admits;

        Unit(String operatorId, ValueKind... admits) {
            this.operatorId = operatorId;
            this.admits = java.util.Set.of(admits);
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public DateExtraction {
        if (!unit.admits.contains(source.kind())) {
            throw new IllegalArgumentException(unit.operatorId() + " needs a " + unit.admits
                    + " operand, but the source is " + source.kind() + " (MVK_WRONG_DATE_FORMAT_FOR_OP)");
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
