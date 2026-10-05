package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code Valid(field, "TypeName")} / {@code Invalid(field, "TypeName")} — the kernel's
 * <b>custom-field-type</b> validity condition (its {@code IPredefinedTypeValidityCondition}): true iff
 * the field's value is a valid instance of the predefined field type named by {@code typeName} (e.g.
 * {@code "IBAN"}, {@code "BIC"}). The field operand is a STRING or ENUMERATION field.
 *
 * <p>Distinct from {@link DateValidity} (the {@code Valid(Date(...))} date-construct overload) — same
 * keyword, different operand shape — so this carries a distinct {@link #operatorId()} ({@code ValidType}
 * / {@code InvalidType}).
 *
 * <p><b>The type name is not validated at model-check time</b> (KERNEL-FINDINGS / item 10): the kernel's
 * {@code checkConsistency} stores it verbatim and accepts any string — the predefined types are a
 * runtime client-SPI ({@code IPredefinedType}), so a wrong/unregistered name only surfaces when the rule
 * is evaluated against data, not when the model is checked. Build via {@link Dsl#validType} /
 * {@link Dsl#invalidType}.
 */
public record CustomTypeValidity(boolean valid, FieldRef field, String typeName) implements Condition {

    public CustomTypeValidity {
        if (typeName == null || typeName.isBlank()) {
            throw new IllegalArgumentException("a custom-field-type validity condition needs a non-blank type name");
        }
    }

    @Override
    public String operatorId() {
        return valid ? "ValidType" : "InvalidType";
    }
}
