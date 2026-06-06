package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A date-valued {@code MaxValue(…)} / {@code MinValue(…)} — the latest/earliest date over a flattened
 * repetition list of a date field. The kernel reuses the {@code MaxValue}/{@code MinValue} keywords for
 * the date variant (the numeric variant is {@link ListAggregate}); both ignore unspecified entries
 * (KERNEL-SEMANTICS §9). A {@link Calculation} of {@link ValueKind#DATE}, so wrapping it in a
 * {@link DateExpr} makes it a comparable {@link DateOperand}. Build via {@link Dsl#latestOf} /
 * {@link Dsl#earliestOf}.
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
        if (scope.kind() != ValueKind.DATE) {
            throw new IllegalArgumentException(mode.operatorId() + " over dates needs a date field list; "
                    + scope.renderedPath() + " is " + scope.kind() + " (MVK_NOT_SORTABLE)");
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }

    public String operatorId() {
        return mode.operatorId();
    }
}
