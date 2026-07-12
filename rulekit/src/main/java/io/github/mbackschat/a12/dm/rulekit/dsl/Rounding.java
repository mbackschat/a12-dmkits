package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code RoundDown(expr, n)} / {@code RoundUp(expr, n)} / {@code RoundAccounting(expr, n)} — round a
 * numeric expression to {@code n} decimal places (the kernel's operand-list rounding,
 * {@code IOpListOperationWithParameter}; distinct from the field-only {@code RoundDownValue} family).
 * Unlike {@link Arithmetic} the operand is an arbitrary numeric expression, so these compose anywhere
 * a numeric operand is expected — typically the right side of a computed-field consistency rule
 * ({@code [Density] != RoundDown([Population] / [Area], 2)}). Build via {@link Dsl#roundDown}, etc.
 */
public record Rounding(Mode mode, Calculation operand, int decimalPlaces) implements Calculation {

    /** The three rounding directions (kernel {@code OpListOperationName}) with their catalog operator-ids. */
    public enum Mode {
        DOWN("RoundDown"),
        UP("RoundUp"),
        ACCOUNTING("RoundAccounting");

        private final String operatorId;

        Mode(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public Rounding {
        if (decimalPlaces < 0) {
            throw new IllegalArgumentException("rounding decimal places must be >= 0, was " + decimalPlaces);
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
