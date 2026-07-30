package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Time(hour?, minute?, second?)} — the kernel's time-of-day construction from a zero-to-three
 * component prefix (its
 * {@code ITimeConstructionOperation}). A {@link Calculation} of {@link ValueKind#TIME}; wrap in a
 * {@link TimeExpr} to compare it. Omitted trailing components default to zero. Each present component renders
 * in argument position — a field bare ({@code Time(H, M, S)}), a constant as a quoted literal
 * ({@code Time("10", "30", "50")}). The kernel rejects a bracketed value ({@code [H]}) here, so components
 * are bare fields or quoted constants only.
 */
public record TimeConstruction(List<Calculation> components) implements Calculation {

    public TimeConstruction {
        components = List.copyOf(components);
        if (components.size() > 3) {
            throw new IllegalArgumentException("Time accepts at most hour, minute, and second components");
        }
    }

    /** Backward-compatible full three-component construction. */
    public TimeConstruction(Calculation hour, Calculation minute, Calculation second) {
        this(List.of(hour, minute, second));
    }

    @Override
    public ValueKind kind() {
        return ValueKind.TIME;
    }

    public String operatorId() {
        return "Time";
    }
}
