package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A date or time extracted from a date-time — {@code DateFromDateTime(dt)} (→ a date) /
 * {@code TimeFromDateTime(dt)} (→ a time), the value-valued members of the kernel
 * {@code DateExtractionOperationName}. The source renders as a bare entity reference. Build via
 * {@link Dsl#dateFromDateTime} / {@link Dsl#timeFromDateTime}.
 */
public record DateTimePart(Unit unit, Calculation source) implements Calculation {

    /** The value-valued date-time decompositions, each carrying its catalog operator-id and result kind. */
    public enum Unit {
        DATE("DateFromDateTime", ValueKind.DATE),
        TIME("TimeFromDateTime", ValueKind.TIME);

        private final String operatorId;
        private final ValueKind kind;

        Unit(String operatorId, ValueKind kind) {
            this.operatorId = operatorId;
            this.kind = kind;
        }

        public String operatorId() {
            return operatorId;
        }

        public ValueKind kind() {
            return kind;
        }
    }

    @Override
    public ValueKind kind() {
        return unit.kind();
    }

    public String operatorId() {
        return unit.operatorId();
    }
}
