package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * {@code AtLeastOne|No|NotAllFieldValue(s)IncludedInValueList(f1, f2 In g1, g2)} where the value list
 * holds <b>field references</b>, not constants — a field list's membership in <em>another</em> field
 * list (e.g. a field {@code In} a starred repetition list). The sibling of {@link
 * FieldListValueListCondition} (literal value lists); the difference is purely the value side, which
 * here renders as bare entity paths (a {@link ValueListOperand} each — a plain field, a flattened
 * repetition list, or an enum category projection {@code [field -> Cat]}) rather than quoted
 * constants. Same-base-kind and combined-reference uniqueness are enforced by the {@link Dsl} entry
 * point (the smallest model-aware owner); the value entities carry their static {@link
 * ValueListOperand#kind() kind} here.
 */
public record FieldListEntityListCondition(
        ValueListQuantifier quantifier, List<ValueListOperand> fields, List<ValueListOperand> valueFields)
        implements Condition {

    public FieldListEntityListCondition {
        fields = List.copyOf(fields);
        valueFields = List.copyOf(valueFields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("the field list needs at least one field");
        }
        if (valueFields.isEmpty()) {
            throw new IllegalArgumentException("the value field list needs at least one field");
        }
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }
}
