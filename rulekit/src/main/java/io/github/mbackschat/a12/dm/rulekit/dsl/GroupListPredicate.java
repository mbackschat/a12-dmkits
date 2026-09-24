package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A group-list quantifier over the kernel's common <em>entity list</em>, e.g.
 * {@code AtLeastOneGroupFilled(a, b)} (PROJECT-DESIGN §5). Despite the group-list names, an operand
 * is a {@link GroupListOperand} — a checked field or an exact group scope (SPEC-2026-07-23-09). A
 * group operand retains its exact starred scope because the legal single-group
 * {@code NoGroupFilled(G*)}/{@code AtLeastOneGroupFilled(G*)} forms scan repetitions rather than
 * testing scalar group presence.
 */
public final class GroupListPredicate implements Condition {

    private final GroupQuantifier quantifier;
    private final List<GroupListOperand> operands;

    /** Build a predicate over bare group references. */
    public GroupListPredicate(GroupQuantifier quantifier, List<GroupRef> groups) {
        this(quantifier, Objects.requireNonNull(groups, "groups").stream()
                .map(group -> (GroupListOperand) new GroupListRef(
                        Objects.requireNonNull(group, "group"), List.of()))
                .toList());
    }

    private GroupListPredicate(
            GroupQuantifier quantifier,
            Collection<GroupListOperand> operands) {
        this.quantifier = Objects.requireNonNull(quantifier, "quantifier");
        this.operands = List.copyOf(operands);
        if (this.operands.isEmpty()) {
            throw new IllegalArgumentException("a group-list predicate needs at least one operand");
        }
        DuplicateGate.rejectDuplicates(quantifier.operatorId(), this.operands);
    }

    static GroupListPredicate overScopes(
            GroupQuantifier quantifier, GroupListRef first, GroupListRef... rest) {
        return overEntities(quantifier, first, rest);
    }

    /** Build over the common field-or-group entity list (the arity and root-group gates stay kernel-arbitrated). */
    static GroupListPredicate overEntities(
            GroupQuantifier quantifier, GroupListOperand first, GroupListOperand... rest) {
        var operands = new java.util.ArrayList<GroupListOperand>();
        operands.add(Objects.requireNonNull(first, "first"));
        operands.addAll(List.of(rest));
        return new GroupListPredicate(quantifier, operands);
    }

    public GroupQuantifier quantifier() {
        return quantifier;
    }

    /** Exact operands — checked fields and group scopes (every starred segment retained). */
    public List<GroupListOperand> operands() {
        return operands;
    }

    /**
     * Terminal bare groups — the group-only view retained for existing consumers. Fails fast on a
     * mixed or field-only entity list (a silently field-dropping view would misreport references);
     * use {@link #operands()} there.
     */
    public List<GroupRef> groups() {
        return operands.stream().map(operand -> switch (operand) {
            case GroupListRef scope -> scope.group();
            case FieldRef field -> throw new IllegalStateException(
                    "the entity list contains field operand [" + field.path() + "] — use operands()");
            case IndexedGroupValue indexed -> throw new IllegalStateException(
                    "the entity list contains an index-selected group [" + indexed.renderedPath()
                            + "] — use operands()");
            case IndexedFieldValue indexed -> throw new IllegalStateException(
                    "the entity list contains an index-selected field [" + indexed.renderedPath()
                            + "] — use operands()");
        }).toList();
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof GroupListPredicate predicate
                && quantifier == predicate.quantifier
                && operands.equals(predicate.operands);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quantifier, operands);
    }

    @Override
    public String toString() {
        return "GroupListPredicate[quantifier=" + quantifier + ", operands=" + operands + "]";
    }
}
