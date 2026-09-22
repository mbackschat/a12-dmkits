package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FirstFilledValue(/Group* then Field, …)} — the first specified value across the operands in
 * authored order; within a flattened repetition list, rows retain their order and unspecified cells are
 * skipped (KERNEL-SEMANTICS §9, a {@code feldListenOp} sibling of {@code Sum}). The owner admits fields,
 * wildcard lists, semantic-index entities, enum category projections, and groups; each authored entity
 * shape stays one operand. A single operand is legal when it expands to a list — a wildcard or group —
 * while a scalar form needs a second operand.
 *
 * <p>The operands must share one kernel base type: String/Custom/Enumeration are one, and so are all four
 * date-bearing kinds, which the kernel tells apart only by declared format (KERNEL-FINDINGS KF71). A record
 * holding kinds cannot see a format, so the format half of that gate is the kernel's.
 */
public record FirstFilledValue(FirstFilledOperand firstOperand, List<FirstFilledOperand> trailingOperands)
        implements Calculation {

    public FirstFilledValue(FieldListRef scope) {
        this(scope.firstFilledOperand(), List.of());
    }

    /** Binary/source-compatible constructor for the original list-first component type. */
    public FirstFilledValue(FieldListRef scope, List<FieldOperand> trailingOperands) {
        this(scope.firstFilledOperand(), trailingOperands.stream()
                .map(FieldOperand::firstFilledOperand).toList());
    }

    public FirstFilledValue {
        if (!firstOperand.expandsToList() && trailingOperands.isEmpty()) {
            throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDN, "FirstFilledValue needs at least two scalar fields"
                    + " or one repetition list");
        }
        trailingOperands = List.copyOf(trailingOperands);
        List<FirstFilledOperand> all = operands(firstOperand, trailingOperands);
        for (FirstFilledOperand operand : all) {
            if (operand.kind() == ValueKind.CONFIRM) {
                throw Refusals.of(KernelCode.MVK_NO_BOOLY_ALLOWED, "FirstFilledValue does not accept CONFIRM operands");
            }
            if (!sameBaseType(firstOperand.kind(), operand.kind())) {
                throw new IllegalArgumentException("FirstFilledValue operands must have one owner result family; "
                        + describe(operand) + " is " + operand.kind() + " but "
                        + describe(firstOperand) + " is " + firstOperand.kind());
            }
        }
        // Both arms through the one shared gate: this carrier's operands are richer than the sealed
        // unions' (an owner entity may be a field OR a group, keyed by a literal or by a field read), and
        // FirstFilledOperand answers for that richness itself rather than a second predicate doing it here.
        DuplicateGate.rejectDuplicates("FirstFilledValue", all);
    }

    /** Every operand in authored encounter order. */
    public List<FirstFilledOperand> operands() {
        return operands(firstOperand, trailingOperands);
    }

    @Override
    public ValueKind kind() {
        return resultKind(operands());
    }

    public String operatorId() {
        return "FirstFilledValue";
    }

    private static List<FirstFilledOperand> operands(
            FirstFilledOperand firstOperand, List<FirstFilledOperand> trailingOperands) {
        var operands = new ArrayList<FirstFilledOperand>(trailingOperands.size() + 1);
        operands.add(firstOperand);
        operands.addAll(trailingOperands);
        return List.copyOf(operands);
    }

    static ValueKind resultKind(List<? extends FirstFilledOperand> operands) {
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("FirstFilledValue needs at least one operand");
        }
        ValueKind first = operands.getFirst().kind();
        for (FirstFilledOperand operand : operands) {
            if (!sameBaseType(first, operand.kind())) {
                throw new IllegalArgumentException("FirstFilledValue operands must have one owner result family; "
                        + first + " and " + operand.kind() + " differ");
            }
        }
        if (operands.stream().allMatch(operand -> operand.kind() == first)) {
            return first;
        }
        if (!operands.stream().allMatch(operand -> sameOwnerFamily(first, operand.kind()))) {
            // Date-bearing kinds from different families: the kernel types the list as its first field
            // (CheckEntityListenUtils.checkAllFieldsMatchingOperationType), so this does too.
            return first;
        }
        return switch (family(first)) {
            case STRING -> ValueKind.STRING;
            case DATE -> ValueKind.DATE;
            default -> first;
        };
    }

    private static String describe(FirstFilledOperand operand) {
        return operand.renderedPath();
    }

    /**
     * Whether two kinds may share one operand list. This is the kernel's base type
     * ({@code OperationResultTypeMap.getOpTypeForField}), which is coarser than {@link #sameOwnerFamily}: its one
     * {@code DATETIME} covers every date-bearing kind, because the declared format tells them apart there. Keyed
     * on the owner family instead, this refused a TIME beside a time-only DATE_TIME and a DATE beside a date-only
     * TIME, both kernel-admitted ({@code validate.laws.FirstFilledValueCrossKindFormatLawsTest}).
     */
    static boolean sameBaseType(ValueKind left, ValueKind right) {
        return baseType(left) == baseType(right);
    }

    /** Whether a list mixing these kinds keeps its family's own result type, and a result may be assigned. */
    static boolean sameOwnerFamily(ValueKind left, ValueKind right) {
        return family(left) == family(right);
    }

    private static BaseType baseType(ValueKind kind) {
        return switch (kind) {
            case STRING, ENUMERATION, CUSTOM -> BaseType.STRING;
            case DATE, DATE_FRAGMENT, TIME, DATE_TIME -> BaseType.DATETIME;
            case NUMBER -> BaseType.NUMBER;
            case BOOLEAN -> BaseType.BOOLEAN;
            case CONFIRM -> BaseType.CONFIRM;
            case DATE_RANGE -> BaseType.DATE_RANGE;
            case UNKNOWN -> BaseType.UNKNOWN;
        };
    }

    private enum BaseType { NUMBER, STRING, BOOLEAN, CONFIRM, DATETIME, DATE_RANGE, UNKNOWN }

    private static Family family(ValueKind kind) {
        return switch (kind) {
            case STRING, ENUMERATION, CUSTOM -> Family.STRING;
            case DATE, DATE_FRAGMENT -> Family.DATE;
            case NUMBER -> Family.NUMBER;
            case BOOLEAN -> Family.BOOLEAN;
            case CONFIRM -> Family.CONFIRM;
            case TIME -> Family.TIME;
            case DATE_TIME -> Family.DATE_TIME;
            case DATE_RANGE -> Family.DATE_RANGE;
            case UNKNOWN -> Family.UNKNOWN;
        };
    }

    private enum Family { NUMBER, STRING, BOOLEAN, CONFIRM, DATE, TIME, DATE_TIME, DATE_RANGE, UNKNOWN }
}
