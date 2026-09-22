package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code [Group For "indexValue"]} — the group's repetition whose <em>index field</em> equals
 * {@code indexValue} (the literal-keyed peer of {@link GroupSemanticIndexFieldValue}), the group
 * analog of {@link SemanticIndexValue}. The enclosing group must declare an {@code indexFieldName}
 * ({@code MVK_NO_INDEX_FIELD}, kernel-delegated, exactly as the field form's own doc notes).
 */
public record GroupSemanticIndexValue(GroupListRef group, String indexValue) implements IndexedGroupValue {
}
