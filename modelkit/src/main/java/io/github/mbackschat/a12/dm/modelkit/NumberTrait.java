package io.github.mbackschat.a12.dm.modelkit;

/**
 * A number field's <b>unit</b> trait (the kernel {@code trait} slot) — its presentation/semantics as a money
 * {@link #AMOUNT}, a {@link #PERCENT}, or a {@link #PERMILLE} value. The DM-JSON value is the lowercase token
 * the kernel's client contract declares (`amount`/`percent`/`permille`, per {@code kernel-md-facade} api.ts);
 * the runtime treats {@code amount} specially (money formatting). Distinct from the metamodel's
 * {@code AmountWithCent}/{@code AmountWithoutCent}, which are a <em>derived</em> category used only in
 * cross-field type-compatibility checks (KERNEL-FINDINGS), not this field-level slot.
 */
public enum NumberTrait {

    AMOUNT("amount"),
    PERCENT("percent"),
    PERMILLE("permille");

    private final String json;

    NumberTrait(String json) {
        this.json = json;
    }

    /** The DM-JSON {@code trait} value (lowercase). */
    public String json() {
        return json;
    }
}
