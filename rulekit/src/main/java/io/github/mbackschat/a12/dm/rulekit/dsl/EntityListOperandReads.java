package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * How the model-bound aggregate factories of {@link BoundModel} read an entity list before A12's list gates ask
 * it anything. They live apart from {@link Dsl} because they need the model, which the static facade never sees.
 */
final class EntityListOperandReads {

    private EntityListOperandReads() {
    }

    /**
     * The walk A12 runs over an entity list its leading field sends down the date-like route, in A12's order,
     * for the model-bound factories that can see what it reads.
     *
     * <p>{@code CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType} asks each operand in turn three
     * questions and stops at the first that fails: is it date-like ({@code MVK_DATE_AND_NONDATE}), is its date
     * fully known ({@code MVK_PARTIAL_DATE_NOT_ALLOWED}), and does its declared component set equal the
     * leader's ({@code MVK_DATEFORMATS_NOT_COMPATIBLE}). The walk is interleaved rather than phased, so two
     * defects answer by operand POSITION ([KF389], {@code validate.laws.DateAggregateBaseTypeGateLawsTest},
     * {@code validate.laws.EntityListLeaderRouteLawsTest}); two separate passes answered the precision class
     * for a list whose earlier operand already broke the component set. Duplicates and the param law are
     * asked before any of it, as A12 asks them.
     *
     * <p>Component SETS are compared rather than format strings, after YEAR is added to every set when the
     * model declares a base year ([KF193], {@code validate.laws.TemporalAggregateComponentSetLawsTest}), so
     * {@code yyyy-MM-dd} and {@code dd.MM.yyyy} agree. Only a DATE declares a precision, so a TIME, DATETIME or
     * DATE_FRAGMENT operand passes that question ([KF427]).
     *
     * <p>A group operand is walked as its expansion, in its place, which is what A12 walks
     * ({@link BoundModel#expansionOf}). <b>The walk stops, and leaves the rest to the kernel, at the first operand
     * it cannot read</b>: a comparison with a field that declares no format, whose default this walk does not
     * compare, and a dangling type. Stopping under-reports; walking on would cite a later operand's class where
     * the unread one may answer first. A leader that is not date-like is not this route at all, so the record's
     * own gate answers.
     */
    static void requireComparableTemporalList(String operatorId, BoundModel model,
            AggregateOperand first, AggregateOperand... rest) {
        var operands = new ArrayList<AggregateOperand>();
        operands.add(first);
        operands.addAll(List.of(rest));
        readOperandsThen(operatorId, model, operands, () -> {
            EntityListRoute.requireShape(operatorId, operands);
            walkTemporalList(operatorId, model, model.expansionOf(operands));
        });
    }

    /**
     * The walk above for {@code NumberOfDifferentValues}, then the record's own kind gate, asked coded, both over
     * the expansion. Off the date-like route the walk asks nothing, and behind a textual or numeric leader every
     * mismatch is the leader's class wherever it stands ({@code validate.laws.NumberOfDifferentValuesOperandClassLawsTest}),
     * so once the groups are read the record's class is A12's. Behind a date-like leader the record never cites.
     */
    static void requireDifferentValuesList(BoundModel model, AggregateOperand first, AggregateOperand... rest) {
        var operands = new ArrayList<AggregateOperand>();
        operands.add(first);
        operands.addAll(List.of(rest));
        String operatorId = CountAggregate.CountOp.DIFFERENT_VALUES.operatorId();
        readOperandsThen(operatorId, model, operands, () -> {
            EntityListRoute.requireShape(operatorId, operands);
            List<AggregateOperand> expanded = model.expansionOf(operands);
            walkTemporalList(operatorId, model, expanded);
            CountAggregate.checkValueComparable(expanded);
        });
    }

    /**
     * Reads each entity-list operand as A12 does, then asks {@code gates} with their classes intact.
     *
     * <p>A12 reads every operand before any list gate, and the first it cannot read answers
     * ({@code validate.laws.EntityListOperandReadLawsTest}). A group's emptiness is the model's to show, so an
     * empty group is cited here, a root as its own class. Three earlier stages of the read are not: an index's
     * resolution, and the rule and filter the condition sits in, which bind a repeatable level left unstarred and
     * admit the {@code $} marker ({@code validate.laws.EntityListReadStageLawsTest}). So from the first operand
     * one of those may answer for, nothing further is read and every class {@code gates} raises is withheld, as
     * the records withhold it.
     */
    static void readOperandsThen(String operatorId, BoundModel model, List<? extends AggregateOperand> operands,
            Runnable gates) {
        boolean unread = false;
        for (AggregateOperand operand : operands) {
            if (readNeedsMoreThanTheModel(model, operand)) {
                unread = true;
                break;
            }
            if (operand instanceof GroupListRef scope && model.descendantFields(scope.group()).findAny().isEmpty()) {
                boolean root = scope.group().path().value().indexOf('/', 1) < 0;
                throw Refusals.of(root ? KernelCode.MVK_ROOT_GROUP_IS_EMPTY : KernelCode.MVK_GROUP_IS_EMPTY,
                        operatorId + " reads " + scope.renderedPath() + ", which holds no field");
            }
        }
        try {
            gates.run();
        } catch (KernelCodeRefusal refusal) {
            throw unread ? refusal.withheld() : refusal;
        }
    }

    /** Whether A12's read of this operand may answer from what the model does not hold — see {@link #readOperandsThen}. */
    private static boolean readNeedsMoreThanTheModel(BoundModel model, AggregateOperand operand) {
        return switch (operand) {
            case IndexedFieldValue ignored -> true;
            case IndexedGroupValue ignored -> true;
            case FieldRef field -> field.correlated() || model.crossesUnstarredRepetition(field);
            case FieldListRef list -> list.semanticIndex().isPresent() || list.correlated() || model.crossesUnstarredRepetition(list);
            case GroupListRef scope -> scope.correlated() || model.crossesUnstarredRepetition(scope);
        };
    }

    /** The date-like walk over an expanded list; the shape gates have been asked of the operands as written. */
    private static void walkTemporalList(String operatorId, BoundModel model, List<AggregateOperand> operands) {
        AggregateOperand first = operands.getFirst();
        String leaderPath = declaredFieldPath(first);
        if (leaderPath == null || !EntityListRoute.isDateLike(model.field(leaderPath).kind())) {
            return;
        }
        boolean supplemented = model.hasBaseYear();
        Set<TemporalFormats.Component> witness = null;
        for (int position = 0; position < operands.size(); position++) {
            String path = declaredFieldPath(operands.get(position));
            if (path == null) {
                return;
            }
            ValueKind kind = model.field(path).kind();
            if (kind == ValueKind.UNKNOWN) {
                return;
            }
            if (!EntityListRoute.isDateLike(kind)) {
                throw Refusals.of(KernelCode.MVK_DATE_AND_NONDATE, operatorId + " is led by the date-like '"
                        + leaderPath + "', so every operand must be date-like; '" + path + "' is " + kind);
            }
            String precision = model.datePrecision(path);
            if (precision != null && !DatePrecisions.guaranteedComponents(precision).containsAll(
                    EnumSet.of(TemporalFormats.Component.YEAR, TemporalFormats.Component.MONTH,
                            TemporalFormats.Component.DAY))) {
                throw Refusals.of(KernelCode.MVK_PARTIAL_DATE_NOT_ALLOWED,
                        operatorId + " does not admit a partly-known date; '"
                                + path + "' is declared " + precision);
            }
            String declared = model.temporalFormat(path);
            Set<TemporalFormats.Component> components = null;
            if (declared != null) {
                components = EnumSet.copyOf(TemporalFormats.componentsOf(declared));
                if (supplemented) {
                    components.add(TemporalFormats.Component.YEAR);
                }
            }
            if (position == 0) {
                // The leader's set is only the witness; the next operand is still asked its first two
                // questions when the leader declares none, since A12 asks both ahead of the comparison.
                witness = components;
            } else if (witness == null || components == null) {
                return;
            } else if (!witness.equals(components)) {
                throw Refusals.of(KernelCode.MVK_DATEFORMATS_NOT_COMPATIBLE,
                        operatorId + " needs every temporal operand to declare the"
                                + " same date components; '" + leaderPath + "' declares " + witness + " and '" + path
                                + "' declares " + components
                                + (supplemented ? " (both supplemented by the model's base year)" : ""));
            }
        }
    }

    /** The model path of an operand that directly names ONE field declaration, else {@code null}. */
    private static String declaredFieldPath(AggregateOperand operand) {
        return switch (operand) {
            case FieldRef ref -> ref.path().value();
            case FieldListRef list -> list.field().path().value();
            case IndexedFieldValue indexed -> indexed.field().path().value();
            default -> null;
        };
    }
}
