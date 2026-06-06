package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * A field-fill quantifier ({@code AllFieldsFilled} / {@code AtLeastOneFieldFilled} / {@code NoFieldFilled})
 * applied to <b>group scopes</b> rather than an explicit field list — "(all / at least one / no) of the
 * fields inside this group are filled", optionally across all repetitions ({@code Group*}). The peer of
 * {@link FieldListPredicate} (which takes fields); kept separate so each operand kind renders correctly
 * and the exhaustive AST switches account for it. Built from {@link GroupListRef} scopes.
 */
public record GroupScopedFieldFill(Quantifier quantifier, List<GroupListRef> scopes) implements Condition {

    public GroupScopedFieldFill {
        scopes = List.copyOf(scopes);
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("a group-scoped field-fill needs at least one group scope");
        }
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }
}
