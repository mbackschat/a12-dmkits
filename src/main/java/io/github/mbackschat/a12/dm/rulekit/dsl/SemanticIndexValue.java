package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code [Field For "indexValue"]} — the field's value in the repetition whose <em>index field</em>
 * equals {@code indexValue} (the semantic index, DE {@code Fuer}; KERNEL-SEMANTICS §9). The field's
 * enclosing repeatable group must declare an {@code indexFieldName}, else the kernel reports
 * {@code MVK_NO_INDEX_FIELD} (kernel-delegated — rulekit's index does not yet expose index-field
 * metadata). Yields the field's kind, so it composes as an ordinary comparison operand.
 */
public record SemanticIndexValue(FieldRef field, String indexValue) implements Calculation {

    public String operatorId() {
        return "SemanticIndex";
    }

    @Override
    public ValueKind kind() {
        return field.kind();
    }
}
