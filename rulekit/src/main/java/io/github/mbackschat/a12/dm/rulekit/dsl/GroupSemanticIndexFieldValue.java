package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code [Group For keyField]} — the FIELD-KEYED semantic index over a group: the repetition of
 * {@code group} whose <em>index field</em> equals the CURRENT value of {@code indexField}, the group
 * analog of {@link SemanticIndexFieldValue}. The enclosing group must declare an
 * {@code indexFieldName} ({@code MVK_NO_INDEX_FIELD}, kernel-delegated).
 */
public record GroupSemanticIndexFieldValue(GroupListRef group, FieldRef indexField) implements IndexedGroupValue {
}
