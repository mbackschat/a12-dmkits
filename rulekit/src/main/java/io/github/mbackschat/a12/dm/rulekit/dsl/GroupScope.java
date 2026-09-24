package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * A group whose contained fields an entity list reads: a {@link GroupListRef} scope, or the one row of it that a
 * semantic index selects ({@link IndexedGroupValue}, from {@link GroupListRef#forIndex(String)}). A12 admits the
 * two on the same carriers (KERNEL-FINDINGS KF351, {@code validate.laws.SemanticIndexGroupOperandCarrierLawsTest}),
 * so the group-scoped field-fill quantifiers ({@link Dsl#allFieldsFilled(GroupScope, GroupScope...)} and its peers)
 * take either, in one list.
 */
public sealed interface GroupScope extends AggregateOperand permits GroupListRef, IndexedGroupValue {
}
