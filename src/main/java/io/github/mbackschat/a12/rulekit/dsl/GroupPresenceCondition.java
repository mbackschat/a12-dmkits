package io.github.mbackschat.a12.rulekit.dsl;

/** {@code GroupFilled(...)} / {@code GroupNotFilled(...)} — whether the group (row) exists/has content. */
public record GroupPresenceCondition(GroupRef group, boolean filled) implements Condition {

    @Override
    public String operatorId() {
        return filled ? "GroupFilled" : "GroupNotFilled";
    }
}
