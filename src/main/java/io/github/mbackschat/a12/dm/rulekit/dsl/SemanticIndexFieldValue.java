package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code [Field For keyField]} — the FIELD-KEYED semantic index: the value of {@code field} in the
 * repetition whose <em>index field</em> equals the CURRENT value of {@code indexField} (BA doc: "the value
 * of the semantic index can be specified as either a constant or a Field"). The constant-key form is
 * {@link SemanticIndexValue}; this is the field-key form. Like its sibling it yields the field's kind and
 * composes as an ordinary comparison operand, and the enclosing repeatable group must declare an
 * {@code indexFieldName} ({@code MVK_NO_INDEX_FIELD}, kernel-delegated). DEV-API-GAPS DG8.
 */
public record SemanticIndexFieldValue(FieldRef field, FieldRef indexField) implements IndexedFieldValue {

    public String operatorId() {
        return "SemanticIndex";
    }

    @Override
    public ValueKind kind() {
        return field.kind();
    }
}
