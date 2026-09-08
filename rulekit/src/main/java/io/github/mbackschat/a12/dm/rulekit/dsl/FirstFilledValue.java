package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.HasEntityPath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code FirstFilledValue(/Group* then Field, …)} — the first specified value across the operands in
 * authored order; within a flattened repetition list, rows retain their order and unspecified cells are
 * skipped (KERNEL-SEMANTICS §9, a {@code feldListenOp} sibling of {@code Sum}). The owner admits fields,
 * wildcard lists, semantic-index entities, enum category projections, and groups; each authored entity
 * shape stays one operand. A single operand is legal when it expands to a list — a wildcard or group —
 * while a scalar form needs a second operand. The owner groups String/Custom/Enumeration and
 * Date/DateFragment into result families, while the other kinds remain exact.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

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
            if (!sameOwnerFamily(firstOperand.kind(), operand.kind())) {
                throw new IllegalArgumentException("FirstFilledValue operands must have one owner result family; "
                        + describe(operand) + " is " + operand.kind() + " but "
                        + describe(firstOperand) + " is " + firstOperand.kind());
            }
        }
        checkOwnerDuplicates(all);
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
            if (!sameOwnerFamily(first, operand.kind())) {
                throw new IllegalArgumentException("FirstFilledValue operands must have one owner result family; "
                        + first + " and " + operand.kind() + " differ");
            }
        }
        if (operands.stream().allMatch(operand -> operand.kind() == first)) {
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

    /** Mirror A12's entity-list identity rule: wildcarded direct refs may repeat, while exact duplicate
     *  entities and a plain group plus one of its plain descendants are refused. Category and semantic-index
     *  modifiers are part of identity, as they are in the owner {@code EntityRef}. */
    private static void checkOwnerDuplicates(List<FirstFilledOperand> operands) {
        Set<EntityIdentity> seen = new HashSet<>();
        for (FirstFilledOperand operand : operands) {
            if (!hasWildcard(operand) && !seen.add(identity(operand))) {
                throw Refusals.of(KernelCode.MVK_DUPLICATE_PARAM1, "FirstFilledValue: '" + describe(operand)
                        + "' is listed more than once");
            }
        }
        for (FirstFilledOperand child : operands) {
            EntityIdentity childIdentity = identity(child);
            for (FirstFilledOperand candidateParent : operands) {
                if (candidateParent == child || candidateParent.group().isEmpty()) {
                    continue;
                }
                EntityIdentity parentIdentity = identity(candidateParent);
                if (childIdentity.isStrictlyInside(parentIdentity)
                        && Objects.equals(childIdentity.category(), parentIdentity.category())
                        && Objects.equals(childIdentity.semanticIndex(), parentIdentity.semanticIndex())) {
                    throw Refusals.of(KernelCode.MVK_DUPLICATE_PARAM2, "FirstFilledValue: '" + describe(child)
                            + "' is already included through " + describe(candidateParent));
                }
            }
        }
    }

    private static boolean hasWildcard(FirstFilledOperand operand) {
        return operand.hasWildcard();
    }

    private static EntityIdentity identity(FirstFilledOperand operand) {
        Object index = operand.semanticIndex().map(value -> switch (value) {
            case FirstFilledOperand.LiteralIndex literal -> new LiteralIndex(literal.value());
            case FirstFilledOperand.FieldIndex field ->
                    new FieldIndex(field.field().path().value(), field.field().correlated());
        }).orElse(null);
        return new EntityIdentity(operand.identityPath(), operand.category().orElse(null), index,
                operand.outerReference());
    }

    private record EntityIdentity(String path, String category, Object semanticIndex, boolean correlated)
            implements HasEntityPath {
        @Override
        public EntityPath entityPath() {
            return EntityPath.of(path);
        }
    }
    private record LiteralIndex(String value) { }
    private record FieldIndex(String path, boolean correlated) { }

    static boolean sameOwnerFamily(ValueKind left, ValueKind right) {
        return family(left) == family(right);
    }

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
