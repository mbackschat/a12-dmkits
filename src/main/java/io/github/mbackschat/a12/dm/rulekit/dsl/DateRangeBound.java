package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code StartOfDateRange(range)} / {@code EndOfDateRange(range)} — the start/end bound of a date-range,
 * as a comparable {@link ValueKind#DATE date} (the kernel's {@code IDateRangeExtractionOperation}). The
 * source renders as a bare entity reference. Build via {@link Dsl#startOfDateRange}/{@link Dsl#endOfDateRange};
 * wrap in a {@link DateExpr} to compare it or feed it to a date component extraction.
 */
public record DateRangeBound(Bound bound, Calculation source) implements Calculation {

    public enum Bound {
        START("StartOfDateRange"),
        END("EndOfDateRange");

        private final String operatorId;

        Bound(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }

    public String operatorId() {
        return bound.operatorId();
    }
}
