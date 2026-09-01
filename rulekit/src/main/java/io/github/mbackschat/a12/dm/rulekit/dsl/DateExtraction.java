package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A numeric component extracted from a date or time — the number-valued members of the kernel
 * {@code DateExtractionOperationName}: {@code YearFromDate}/{@code MonthFromDate}/{@code DayFromDate}/
 * {@code QuarterFromDate} and {@code HoursFromTime}/{@code MinutesFromTime}/{@code SecondsFromTime}.
 * A {@link Calculation} of {@link ValueKind#NUMBER}; the source renders as a bare entity reference.
 * Build via {@link Dsl#yearFromDate} and friends.
 *
 * <p><b>Which operand each unit admits is decided by the source's DECLARED FORMAT, not by its kind, so this
 * record cannot decide it.</b> A TIME field declared {@code yyyy-MM-dd} answers {@code YearFromDate} and is
 * refused {@code HoursFromTime} — the mirror of what its kind suggests (KERNEL-FINDINGS KF211). A
 * {@link Calculation} carries {@link Calculation#kind()} and no format, so a kind-keyed gate here would
 * over-reject that field's legal extractions and under-reject its illegal ones. What is decidable from the
 * operand alone is that a NON-TEMPORAL source has no component to extract at all, under the kernel's
 * {@code MVK_NO_DATE}; the choice among the temporal kinds is left to the kernel's own authoring gate, which
 * {@code RuleDiagnostics.diagnose} surfaces. The check exists for a caller (a bridge reading a parsed AST)
 * that holds a bare {@link Calculation} rather than a {@code DateOperand}/{@code TimeOperand}/
 * {@code DateTimeOperand}.
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

    public DateExtraction {
        TemporalOperands.requirePointInTime(unit.operatorId(), source);
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return unit.operatorId();
    }
}
