package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Time(hour, minute, second)} — the kernel's time-of-day construction from components (its
 * {@code ITimeConstructionOperation}). A {@link Calculation} of {@link ValueKind#TIME}; wrap in a
 * {@link TimeExpr} to compare it. Each component renders in argument position — a field bare
 * ({@code Time(H, M, S)}), a constant as a quoted literal ({@code Time("10", "30", "50")}). The kernel
 * rejects a bracketed value ({@code [H]}) here, so components are bare fields or quoted constants only.
 */
public record TimeConstruction(Calculation hour, Calculation minute, Calculation second) implements Calculation {

    @Override
    public ValueKind kind() {
        return ValueKind.TIME;
    }

    public String operatorId() {
        return "Time";
    }
}
