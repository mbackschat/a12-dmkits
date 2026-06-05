package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The group-list quantifier predicates — the group twin of {@link Quantifier} (proposal §5). Each
 * maps to its catalog operator id, from which the renderer looks up the localized keyword.
 */
public enum GroupQuantifier {
    ALL_GROUPS_FILLED("AllGroupsFilled"),
    NO_GROUP_FILLED("NoGroupFilled"),
    AT_LEAST_ONE_GROUP_FILLED("AtLeastOneGroupFilled"),
    NOT_ALL_GROUPS_FILLED("NotAllGroupsFilled"),
    GROUPS_NOT_COLLECTIVELY_FILLED("GroupsNotCollectivelyFilled");

    private final String operatorId;

    GroupQuantifier(String operatorId) {
        this.operatorId = operatorId;
    }

    public String operatorId() {
        return operatorId;
    }
}
