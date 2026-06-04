package io.github.mbackschat.a12.rulekit.dsl;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

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
