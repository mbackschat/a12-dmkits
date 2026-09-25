package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A computation's rounding mode — A12's {@code IBaseCalculationService.RoundingMode}, whose internal values are
 * the stored spellings of {@code Computation.roundingMode}.
 *
 * <p>{@link #EXACT} is the default and is never stored: A12's {@code ComputationSerializer} omits it, and its
 * mapping reads an absent member as {@code EXACT}. So a writer emits the member only for the other two, which
 * keeps a read → write round trip byte-identical to what A12 itself writes.
 *
 * <p>The mode is judged together with each alternative's {@link ToleranceBand}: a non-{@code EXACT} mode is
 * refused against an alternative without a band and admitted against one with a band
 * ({@code MVK_ERROR_WRONG_ROUNDING_MODE}, KERNEL-FINDINGS KF297).
 */
public enum RoundingMode {
    EXACT("Exact"),
    ROUND_UP("RoundUp"),
    ROUND_DOWN("RoundDown");

    private final String wire;

    RoundingMode(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * The mode a stored spelling names, matched exactly as A12's {@code getByInternalValue} matches it: case
     * matters, and a constant NAME such as {@code ROUND_UP} is not a spelling of anything (KF297).
     */
    public static RoundingMode fromWire(String wire) {
        return Arrays.stream(values())
                .filter(value -> value.wire.equals(wire))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown roundingMode '" + wire + "' — expected one of "
                                + Arrays.stream(values()).map(RoundingMode::wire).toList()));
    }
}
