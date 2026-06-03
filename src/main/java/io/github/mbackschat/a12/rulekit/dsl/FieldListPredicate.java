package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;

/** A field-list quantifier applied to fields, e.g. {@code AllFieldsFilled(a, b)} (proposal §5). */
public record FieldListPredicate(Quantifier quantifier, List<FieldRef> fields) implements Condition {

    public FieldListPredicate {
        fields = List.copyOf(fields);
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }
}
