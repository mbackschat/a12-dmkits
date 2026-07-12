package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/** {@code Today} — the current date constant (DE {@code Heute}); ignores time of day (§6). */
public record Today() implements Calculation {

    public String operatorId() {
        return "Today";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }
}
