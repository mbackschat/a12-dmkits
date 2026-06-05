package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * A field-list quantifier applied to fields, e.g. {@code AllFieldsFilled(a, b)} (proposal §5).
 * Operands may be plain fields or flattened repetition lists ({@code Group*}{@code /Field}) — list slots
 * take {@link FieldOperand}, and mixing both is kernel-legal (verified 2026-06-04).
 */
public record FieldListPredicate(Quantifier quantifier, List<FieldOperand> fields) implements Condition {

    public FieldListPredicate {
        fields = List.copyOf(fields);
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }
}
