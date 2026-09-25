package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A computation's rounding mode — A12's {@code IBaseCalculationService.RoundingMode}, whose internal values are
 * the stored spellings of {@code Computation.roundingMode}.
 *
 * <p>{@link #EXACT} is the default and is never stored: A12's {@code ComputationSerializer} omits it, and its
 * mapping reads an absent member as {@code EXACT}. {@link #stored()} is that rule, for every writer.
 *
 * <p>The mode is judged together with each alternative's {@link ToleranceBand}: a non-{@code EXACT} mode is
 * refused against an alternative without a band and admitted against one with a band
 * ({@code MVK_ERROR_WRONG_ROUNDING_MODE}, KERNEL-FINDINGS KF297). At run time it rounds the value the
 * computation writes to a whole number (KF474).
 *
 * <p>Not {@link Rounding.Mode}, which is the direction of an explicit {@code Round*} operator inside an
 * operation.
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

    /** The spelling A12 stores for this mode, or empty for {@link #EXACT}, which its serializer omits. */
    public Optional<String> stored() {
        return this == EXACT ? Optional.empty() : Optional.of(wire);
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
