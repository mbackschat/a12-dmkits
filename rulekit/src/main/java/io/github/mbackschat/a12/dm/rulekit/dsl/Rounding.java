package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Objects;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code RoundDown(expr[, n])} / {@code RoundUp(expr[, n])} /
 * {@code RoundAccounting(expr[, n])} — round a numeric expression to the optional {@code n} decimal
 * places; omission means zero (the kernel's operand-list rounding,
 * {@code IOpListOperationWithParameter}; distinct from the field-only {@code RoundDownValue} family).
 * Unlike {@link Arithmetic} the operand is an arbitrary numeric expression, so these compose anywhere
 * a numeric operand is expected — typically the right side of a computed-field consistency rule
 * ({@code [Density] != RoundDown([Population] / [Area], 2)}). Build via {@link Dsl#roundDown}, etc.
 */
public record Rounding(Mode mode, Calculation operand, Optional<Integer> decimalPlaces) implements Calculation {

    /** The three rounding directions (kernel {@code OpListOperationName}) with their catalog operator-ids. Not
     *  {@link RoundingMode}, which is a computation's rounding of the value it writes. */
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
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(operand, "operand");
        decimalPlaces = Objects.requireNonNull(decimalPlaces, "decimalPlaces");
        decimalPlaces.ifPresent(RoundingPlaces::check);
    }

    /** Backward-compatible constructor for the explicit-places spelling. */
    public Rounding(Mode mode, Calculation operand, int decimalPlaces) {
        this(mode, operand, Optional.of(decimalPlaces));
    }

    /** The one-argument spelling; omitted decimal places mean zero. */
    public Rounding(Mode mode, Calculation operand) {
        this(mode, operand, Optional.empty());
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return mode.operatorId();
    }
}
