package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
        // The kernel's order, so a list breaking two gates is refused for the one A12 reports: duplicates, then
        // a root group, then the two gates over every field the operands expand to (CheckFeldListenOpImpl.check,
        // measured in validate.laws.FirstFilledValueGateOrderLawsTest). Both duplicate arms go through the one
        // shared gate: this carrier's operands are richer than the sealed unions' (an owner entity may be a
        // field OR a group, keyed by a literal or by a field read), and FirstFilledOperand answers for that.
        DuplicateGate.rejectDuplicates("FirstFilledValue", all);
        all.forEach(FirstFilledValue::rejectRootGroup);
        requireOneValueType(all.stream().flatMap(FirstFilledValue::expandedFields).toList());
    }

    /**
     * Uncoded on an indexed root, where the index gate ({@code MVK_NO_INDEX_FIELD}) answers first and the record
     * cannot see whether the index resolves.
     */
    private static void rejectRootGroup(FirstFilledOperand operand) {
        if (operand.rootGroup()) {
            String problem = "FirstFilledValue does not accept a root group: " + operand.renderedPath();
            throw operand.semanticIndex().isPresent()
                    ? new IllegalArgumentException(problem)
                    : Refusals.of(KernelCode.MVK_ROOT_GROUP_REFERENCED, problem);
        }
    }

    /** One field an operand expands to — the unit both of the kernel's field-level list gates read. */
    private record ExpandedField(String path, ValueKind kind) {}

    /**
     * The fields a list gate reads for one operand: a group's recursive expansion, else the operand itself.
     * Never the group operand's own {@link FirstFilledOperand#kind()}, which types a mixed group by refusing
     * it and would answer ahead of a CONFIRM elsewhere in the list.
     */
    private static Stream<ExpandedField> expandedFields(FirstFilledOperand operand) {
        return operand.group().isPresent()
                ? operand.expandedGroupFields().stream().map(f -> new ExpandedField(f.path().value(), f.kind()))
                : Stream.of(new ExpandedField(operand.renderedPath(), operand.kind()));
    }

    /**
     * {@code checkNoBoolY} over every field, then {@code checkAllFieldsMatchingOperationType} against the first
     * one, each citing its code. A pair involving {@code UNKNOWN} is refused uncoded: that kind comes only from
     * a dangling type reference, which has no legal model to put through the oracle.
     */
    private static void requireOneValueType(List<ExpandedField> fields) {
        for (ExpandedField field : fields) {
            if (field.kind() == ValueKind.CONFIRM) {
                throw Refusals.of(KernelCode.MVK_NO_BOOLY_ALLOWED,
                        "FirstFilledValue does not accept CONFIRM operands; " + field.path() + " is CONFIRM");
            }
        }
        ExpandedField first = fields.getFirst();
        for (ExpandedField field : fields) {
            if (!sameBaseType(first.kind(), field.kind())) {
                String problem = "FirstFilledValue operands must have one owner result family; "
                        + field.path() + " is " + field.kind() + " but " + first.path() + " is " + first.kind();
                throw first.kind() == ValueKind.UNKNOWN || field.kind() == ValueKind.UNKNOWN
                        ? new IllegalArgumentException(problem)
                        : Refusals.of(KernelCode.MVK_VARYING_TYPES_NOT_ALLOWED, problem);
            }
        }
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
        // A group operand is typed through here before any record exists (Dsl's factory routing asks its kind),
        // so the list gates run here too, in the same order.
        requireOneValueType(operands.stream().flatMap(FirstFilledValue::expandedFields).toList());
        ValueKind first = operands.getFirst().kind();
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
