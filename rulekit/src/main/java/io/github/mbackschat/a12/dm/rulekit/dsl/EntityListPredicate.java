package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * A quantifier over the kernel's <b>entity list</b> — {@code AllFieldsFilled(a, b)},
 * {@code AtLeastOneFieldFilled(Group*)}, {@code FieldValuesNotUnique(a, Group)} — where each member is a
 * field, a flattened repetition list, or a group scope.
 *
 * <p><b>One record, because the kernel has one construct (DG36, 2026-08-15).</b> This replaced a pair —
 * {@code FieldListPredicate} over {@code List<FieldOperand>} and {@code GroupScopedFieldFill} over
 * {@code List<GroupListRef>} — which made a <em>mixed</em> list unrepresentable even though the kernel
 * accepts one in either operand order (KERNEL-FINDINGS {@code kf187}). The split had a cost beyond
 * ergonomics: the kernel→AST converter had to replay the kernel's group expansion into explicit field
 * operands for the mixed arm, and replaying it is what produced {@code kf188}'s read-back defect. Members are
 * {@link AggregateOperand}, the sealed {@code FieldOperand | GroupListRef} union the entity-list aggregates
 * already use for exactly this reason.
 *
 * <p>What a group member <em>means</em> stays the kernel's: it expands recursively to every field below the
 * group, carrying its {@code *} onto each descendant, and the kind/category gates judge that expansion. This
 * record neither enumerates nor pre-checks it.
 */
public record EntityListPredicate(Quantifier quantifier, List<AggregateOperand> operands) implements Condition {

    public EntityListPredicate {
        operands = List.copyOf(operands);
        if (operands.isEmpty()) {
            throw new IllegalArgumentException(quantifier + " needs at least one operand");
        }
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }

    /** True when any member is a group scope — the shape whose extent belongs to the kernel's expansion. */
    public boolean hasGroupScope() {
        return operands.stream().anyMatch(GroupListRef.class::isInstance);
    }
}
