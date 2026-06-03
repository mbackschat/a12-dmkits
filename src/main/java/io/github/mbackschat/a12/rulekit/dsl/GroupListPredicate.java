package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;

/** A group-list quantifier applied to groups, e.g. {@code AtLeastOneGroupFilled(a, b)} (proposal §5). */
public record GroupListPredicate(GroupQuantifier quantifier, List<GroupRef> groups) implements Condition {

    public GroupListPredicate {
        groups = List.copyOf(groups);
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }
}
