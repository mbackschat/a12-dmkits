package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Objects;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A field-level numeric function applied to a single field — {@code RoundDownValue(f, n)} /
 * {@code RoundUpValue(f, n)} / {@code RoundAccountingValue(f, n)} and {@code AbsValue(f)}. The
 * rounding operators accept {@code 0..14} decimal places; omission is the zero-place shorthand.
 * These are the kernel's {@code FieldIdOperationWithParameters} rounding/abs operators — distinct
 * from the operand-list {@link Rounding} ({@code RoundDown(expr[, n])}). A {@link Calculation} of
 * {@link ValueKind#NUMBER}; the field renders BARE (entity ref). Build via
 * {@link Dsl#roundDownValue} and friends.
 */
public record FieldRounding(Mode mode, FieldRef field, Optional<Integer> decimalPlaces) implements Calculation {

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

    public FieldRounding {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(field, "field");
        decimalPlaces = Objects.requireNonNull(decimalPlaces, "decimalPlaces");
        decimalPlaces.ifPresent(places -> {
            if (mode == Mode.ABS) {
                throw new IllegalArgumentException("AbsValue does not accept decimal places");
            }
            if (places < 0 || places > 14) {
                throw new IllegalArgumentException("rounding decimal places must be in 0..14, was " + places);
            }
        });
    }

    /**
     * Backward-compatible constructor for the one-argument spelling. Omitted decimal places mean
     * zero for the three rounding modes.
     */
    public FieldRounding(Mode mode, FieldRef field) {
        this(mode, field, Optional.empty());
    }

    public FieldRounding(Mode mode, FieldRef field, int decimalPlaces) {
        this(mode, field, Optional.of(decimalPlaces));
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return mode.operatorId();
    }
}
