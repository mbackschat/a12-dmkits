package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A <b>date-like</b> {@code MaxValue(…)} / {@code MinValue(…)} — the latest/earliest DATE, TIME, or DATETIME
 * over a flattened repetition list of a date-like field. The kernel folds all three through one
 * {@code DateCombiner} (KERNEL-FINDINGS §6), reusing the {@code MaxValue}/{@code MinValue} keywords (the numeric
 * variant is {@link ListAggregate}); all ignore unspecified entries (KERNEL-SEMANTICS §9). A {@link Calculation}
 * whose {@link #kind()} follows the {@code scope}'s kind, so wrapping it in the matching operand expression
 * ({@link DateExpr} / {@link TimeExpr} / {@link DateTimeExpr}) makes it a comparable {@link TemporalOperand}.
 * Build via {@link Dsl#latestOf}/{@link Dsl#earliestOf} (DATE), {@link Dsl#latestTimeOf}/{@link Dsl#earliestTimeOf}
 * (TIME), or {@link Dsl#latestDateTimeOf}/{@link Dsl#earliestDateTimeOf} (DATETIME).
 */
public record DateListAggregate(Mode mode, FieldListRef scope) implements Calculation {

    /** The two date aggregations, carrying the catalog operator-id shared with the numeric variant. */
    public enum Mode {
        LATEST("MaxValue"),
        EARLIEST("MinValue");

        private final String operatorId;

        Mode(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public DateListAggregate {
        ValueKind scopeKind = scope.kind();
        if (scopeKind != ValueKind.DATE && scopeKind != ValueKind.TIME && scopeKind != ValueKind.DATE_TIME) {
            throw new IllegalArgumentException(mode.operatorId() + " over date-like values needs a DATE, TIME, or"
                    + " DATETIME field list; " + scope.renderedPath() + " is " + scopeKind + " (MVK_NOT_SORTABLE)");
        }
    }

    @Override
    public ValueKind kind() {
        return scope.kind();
    }

    public String operatorId() {
        return mode.operatorId();
    }
}
