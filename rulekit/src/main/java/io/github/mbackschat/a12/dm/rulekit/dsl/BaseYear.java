package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code BaseYear} — the model's configured base year (DE {@code Basisjahr}), a point-in-time constant.
 * It is <em>polymorphic</em> by comparison context (verified): compared to a date / date-time field it
 * acts as a date (the start of the base year); through {@link Dsl#baseYearNumber()} it acts as the year number
 * in comparisons, arithmetic, computations, and numeric wrappers.
 * Only valid in a model that declares a base year, else {@code MVK_NO_BASE_YEAR}. That is a property of the
 * MODEL rather than of this node, so it is pre-checked on the model-bound factories
 * ({@link BoundModel#baseYear()} and its three peers); the {@link Dsl} forms stay ungated for the inbound
 * converters. Its FORMAT is its kind's default rather than none: measured to behave exactly as a date field
 * declaring no format, so month, day and quarter extraction are admitted over it while the time family is
 * refused. Assigning it {@code yyyy} instead — the shape it reads like — would refuse legal models.
 * Its {@link #kind()} reports {@link ValueKind#DATE} (its primary nature); the converter and computation builder
 * resolve its numeric role from the typed use site. In particular, the kernel does not classify this separate
 * grammar branch as an immediate numeric literal, so operation-form rounding and {@code Abs} accept it directly.
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
