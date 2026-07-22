package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code BaseYear} — the model's configured base year (DE {@code Basisjahr}), a point-in-time constant.
 * It is <em>polymorphic</em> by comparison context (verified): compared to a date / date-time field it
 * acts as a date (the start of the base year); compared to a number field it acts as the year number.
 * Only valid in a model that declares a base year (else {@code MVK_NO_BASE_YEAR} — the kernel's call).
 * Its {@link #kind()} reports {@link ValueKind#DATE} (its primary nature); the converter and computation builder
 * resolve its numeric role from the typed use site.
 */
public record BaseYear() implements Calculation {

    public String operatorId() {
        return "BaseYear";
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }
}
