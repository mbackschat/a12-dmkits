package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A group-list quantifier applied to group operands, e.g. {@code AtLeastOneGroupFilled(a, b)}
 * (PROJECT-DESIGN §5). An operand retains its exact starred scope because the legal single-group
 * {@code NoGroupFilled(G*)}/{@code AtLeastOneGroupFilled(G*)} forms scan repetitions rather than
 * testing scalar group presence.
 */
public final class GroupListPredicate implements Condition {

    private final GroupQuantifier quantifier;
    private final List<GroupListRef> operands;

    /** Build a predicate over bare group references. */
    public GroupListPredicate(GroupQuantifier quantifier, List<GroupRef> groups) {
        this(quantifier, Objects.requireNonNull(groups, "groups").stream()
                .map(group -> new GroupListRef(
                        Objects.requireNonNull(group, "group"), List.of()))
                .toList());
    }

    private GroupListPredicate(
            GroupQuantifier quantifier,
            Collection<GroupListRef> operands) {
        this.quantifier = Objects.requireNonNull(quantifier, "quantifier");
        this.operands = List.copyOf(operands);
        if (this.operands.isEmpty()) {
            throw new IllegalArgumentException("a group-list predicate needs at least one operand");
        }
    }

    static GroupListPredicate overScopes(
            GroupQuantifier quantifier, GroupListRef first, GroupListRef... rest) {
        var operands = new java.util.ArrayList<GroupListRef>();
        operands.add(Objects.requireNonNull(first, "first"));
        operands.addAll(List.of(rest));
        return new GroupListPredicate(quantifier, operands);
    }

    public GroupQuantifier quantifier() {
        return quantifier;
    }

    /** Exact operands, including every starred repeatable-group segment. */
    public List<GroupListRef> operands() {
        return operands;
    }

    /** Terminal bare groups, retained as the source-compatible view for existing consumers. */
    public List<GroupRef> groups() {
        return operands.stream().map(GroupListRef::group).toList();
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
