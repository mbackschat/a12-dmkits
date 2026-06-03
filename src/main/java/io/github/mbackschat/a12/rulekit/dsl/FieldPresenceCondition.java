package io.github.mbackschat.a12.rulekit.dsl;

/** {@code FieldFilled(...)} / {@code FieldNotFilled(...)} — true depending on whether the field holds a value. */
public record FieldPresenceCondition(FieldRef field, boolean filled) implements Condition {

    @Override
    public String operatorId() {
        return filled ? "FieldFilled" : "FieldNotFilled";
    }
}
