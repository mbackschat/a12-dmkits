package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A field-level numeric function applied to a single field — {@code RoundDownValue(f)} /
 * {@code RoundUpValue(f)} / {@code RoundAccountingValue(f)} (round the field's value to an integer) and
 * {@code AbsValue(f)} (absolute value). These are the kernel's {@code FieldIdOperationWithParameters}
 * rounding/abs operators — distinct from the operand-list {@link Rounding} ({@code RoundDown(expr, n)},
 * which carries explicit decimal places). A {@link Calculation} of {@link ValueKind#NUMBER}; the field
 * renders BARE (entity ref). Build via {@link Dsl#roundDownValue} and friends.
 */
public record FieldRounding(Mode mode, FieldRef field) implements Calculation {

    public enum Mode {
        ROUND_DOWN("RoundDownValue"),
        ROUND_UP("RoundUpValue"),
        ROUND_ACCOUNTING("RoundAccountingValue"),
        ABS("AbsValue");

        private final String operatorId;

        Mode(String operatorId) {
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
        return mode.operatorId();
    }
}
