package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FirstFilledValue(/Group* then Field, …)} — the first specified value across the operands in
 * authored order; within a flattened repetition list, rows retain their order and unspecified cells are
 * skipped (KERNEL-SEMANTICS §9, a {@code feldListenOp} sibling of {@code Sum}). The first operand remains
 * a repetition list for source compatibility with the original typed surface; optional trailing operands
 * may be plain fields or further repetition lists. All operands have one element type, which is also this
 * node's result type.
 */
public record FirstFilledValue(FieldListRef scope, List<FieldOperand> trailingOperands) implements Calculation {

    public FirstFilledValue(FieldListRef scope) {
        this(scope, List.of());
    }

    public FirstFilledValue {
        trailingOperands = List.copyOf(trailingOperands);
        for (FieldOperand operand : trailingOperands) {
            if (operand.kind() != scope.kind()) {
                throw new IllegalArgumentException("FirstFilledValue operands must have one type; "
                        + operand.renderedPath() + " is " + operand.kind() + " but "
                        + scope.renderedPath() + " is " + scope.kind());
            }
        }
        ListAggregate.checkNoDuplicates("FirstFilledValue", operands(scope, trailingOperands));
    }

    /** Every operand in authored encounter order. */
    public List<FieldOperand> operands() {
        return operands(scope, trailingOperands);
    }

    @Override
    public ValueKind kind() {
        return scope.kind();
    }

    public String operatorId() {
        return "FirstFilledValue";
    }

    private static List<FieldOperand> operands(FieldListRef scope, List<FieldOperand> trailingOperands) {
        var operands = new ArrayList<FieldOperand>(trailingOperands.size() + 1);
        operands.add(scope);
        operands.addAll(trailingOperands);
        return List.copyOf(operands);
    }
}
