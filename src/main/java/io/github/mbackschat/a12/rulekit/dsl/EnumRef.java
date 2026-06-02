package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;

/**
 * A typed handle to an enumeration field. Comparisons are by <em>stored value</em> (§5b), and the
 * value is checked against the model's enum domain at build time — {@code is("NOPE")} on a field
 * whose values are {@code BASIC/PRO/ENTERPRISE} fails fast rather than producing text the kernel
 * later rejects. Obtain from {@link BoundModel#enumOf(String)}.
 */
public record EnumRef(FieldRef field, List<String> allowedValues) {

    public EnumRef {
        allowedValues = List.copyOf(allowedValues);
    }

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    public Condition is(String value) {
        return compareTo(CompareOp.EQ, value);
    }

    public Condition isNot(String value) {
        return compareTo(CompareOp.NE, value);
    }

    private Condition compareTo(CompareOp op, String value) {
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not a value of " + field.path() + " — allowed: " + allowedValues);
        }
        return new ComparisonCondition(field.value(), op, new EnumValueLiteral(value));
    }
}
