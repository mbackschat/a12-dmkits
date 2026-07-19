package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A computation alternative's closed assignment-tolerance domain.
 *
 * <p>The wire value is persisted as {@code toleranceRangeOp}; an absent band means strict inequality for the
 * computation's implicit validation.
 */
public enum ToleranceBand {
    TR1("DiffersWithToleranceRange1"),
    TR2("DiffersWithToleranceRange2"),
    TR5("DiffersWithToleranceRange5"),
    TR10("DiffersWithToleranceRange10");

    private final String wire;

    ToleranceBand(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public static ToleranceBand fromWire(String wire) {
        return Arrays.stream(values())
                .filter(value -> value.wire.equals(wire))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown toleranceRangeOp '" + wire + "' — expected one of "
                                + Arrays.stream(values()).map(ToleranceBand::wire).toList()));
    }
}
