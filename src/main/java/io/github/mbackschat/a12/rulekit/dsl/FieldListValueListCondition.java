package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;

/**
 * {@code AtLeastOne|No|NotAllFieldValue(s)IncludedInValueList(f1, f2 In v1, v2)} — a quantifier
 * over several fields' membership in one literal list. The kernel requires the field list to be
 * type-homogeneous ({@code MVK_VARYING_TYPES_NOT_ALLOWED}, verified 2026-06-04) — the typed
 * {@link Dsl} entry points (all-{@link StringRef} or all-{@link EnumRef}) erase that. The {@code In}
 * separator is a language-independent grammar literal (like the comparison symbols).
 */
public record FieldListValueListCondition(
        ValueListQuantifier quantifier, List<FieldOperand> fields, List<Calculation> values)
        implements Condition {

    public FieldListValueListCondition {
        fields = List.copyOf(fields);
        values = List.copyOf(values);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("the field list needs at least one field");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("a value list needs at least one value");
        }
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }
}
