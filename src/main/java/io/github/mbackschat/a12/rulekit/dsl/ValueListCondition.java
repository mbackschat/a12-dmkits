package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;

/**
 * {@code FieldValueIncludedInValueList(field, v1, v2, …)} / {@code FieldValueNotIncludedInValueList(…)}
 * — membership of a single field's value in a literal list. The kernel's slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS): only string/enum/number fields ({@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED}),
 * the field is a bare path, values must match the field's kind ({@code MVK_INVALID_TYPES_FOR_COMPARISON}),
 * and numeric values are <em>integer literals only</em> (grammar). The typed handles
 * ({@code in}/{@code notIn} on {@link StringRef}/{@link NumberRef}/{@link EnumRef}) make all of
 * that unrepresentable.
 */
public record ValueListCondition(FieldRef field, boolean included, List<Calculation> values)
        implements Condition {

    public ValueListCondition {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("a value list needs at least one value");
        }
    }

    @Override
    public String operatorId() {
        return included ? "FieldValueIncludedInValueList" : "FieldValueNotIncludedInValueList";
    }
}
