package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The part of A12's entity-list check that runs before any kind gate, and the ROUTE it then takes — shared by
 * the carriers whose kind gate depends on it: {@code MaxValue}, {@code MinValue} and
 * {@code NumberOfDifferentValues}.
 *
 * <p>A12 reads each operand first ({@link OperandRead}), asks duplicates, the root-group gate and the param law next, then types the whole list by its FIRST EXPANDED field
 * ({@code CheckEntityListenUtils.firstType}). A date-like leader sends the list through one walk that asks
 * each operand in turn for a date-like base type, a fully known date and the leader's component set
 * ([KF389]); any other leader sends it through a kind check whose one class answers for every operand. So the
 * class a refusal may cite is fixed by the leader, not by the operand that offends — measured in both argument
 * orders ({@code validate.laws.ExtremumOperandKindGridLawsTest},
 * {@code validate.laws.NumberOfDifferentValuesOperandClassLawsTest},
 * {@code validate.laws.EntityListLeaderRouteLawsTest}).
 *
 * <p><b>How much of that a typed operand list can see.</b> A field operand names its expansion's first field
 * itself; a group operand hides it, so behind a group leader the route, and every class it would select, is
 * not the record's to claim. Behind a date-like leader the walk also reads each earlier operand's declared
 * precision and format, which only a {@link BoundModel} carries.
 */
final class EntityListRoute {

    private EntityListRoute() {
    }

    /**
     * Duplicates, the root-group gate, then the param law — the gates A12 asks of an entity list before it
     * types it, in its order ({@code getRefList} raises the duplicate classes while it builds the list, then
     * {@code checkNotRootGroupReferenced}, then the arity check; [KF388]). A carrier calling this ahead of a
     * model-bound gate gets A12's answer for a list that breaks several.
     */
    static void requireShape(String operatorId, List<? extends AggregateOperand> operands) {
        DuplicateGate.rejectDuplicates(operatorId, operands);
        rejectRootGroups(operatorId, operands);
        if (operands.size() < 2 && operands.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
            throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDN, operatorId + " needs more than one field, a"
                    + " repetition list, or a group scope");
        }
    }

    /**
     * A root group is refused on these carriers wherever it stands, beside other operands too, and ahead of the
     * param law and every kind gate ([KF388], {@code validate.laws.EntityListLeaderRouteLawsTest}), an
     * index-selected root as much as a plain one ({@code validate.laws.EntityListQuantifierReadLawsTest}).
     */
    static void rejectRootGroups(String operatorId, List<? extends AggregateOperand> operands) {
        for (AggregateOperand operand : operands) {
            if (rootNamedBy(operand).isPresent()) {
                throw Refusals.of(KernelCode.MVK_ROOT_GROUP_REFERENCED,
                        operatorId + " does not accept a root group: " + operand.renderedPath());
            }
        }
    }

    /** The root group an operand names, plain or index-selected; A12's root gates ask both alike. */
    static Optional<GroupRef> rootNamedBy(AggregateOperand operand) {
        Optional<GroupRef> group = switch (operand) {
            case GroupListRef plain -> Optional.of(plain.group());
            case IndexedGroupValue indexed -> Optional.of(indexed.group().group());
            case FieldOperand ignored -> Optional.empty();
        };
        return group.filter(candidate -> candidate.path().value().indexOf('/', 1) < 0);
    }

    /**
     * The kind of the list's first expanded field, when the operands show it: a leading field operand whose
     * kind is known. Empty behind a leading group, whose expansion the operand does not carry, and behind a
     * dangling type reference, which has no legal model to measure.
     */
    static Optional<ValueKind> visibleLeaderKind(List<? extends AggregateOperand> operands) {
        return !operands.isEmpty() && operands.getFirst() instanceof FieldOperand leader
                && leader.kind() != ValueKind.UNKNOWN ? Optional.of(leader.kind()) : Optional.empty();
    }

    /** Whether a kind sends a list down the date-like route — the four kinds A12 types as a date or time. */
    static boolean isDateLike(ValueKind kind) {
        return kind == ValueKind.DATE || kind == ValueKind.DATE_FRAGMENT
                || kind == ValueKind.TIME || kind == ValueKind.DATE_TIME;
    }

    /** Whether the list's route is VISIBLY the non-date one: a leading field that is not date-like. */
    static boolean visiblyNonDateRoute(List<? extends AggregateOperand> operands) {
        return visibleLeaderKind(operands).filter(kind -> !isDateLike(kind)).isPresent();
    }
}
