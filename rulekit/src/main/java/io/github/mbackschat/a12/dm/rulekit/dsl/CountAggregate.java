package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * {@code NumberOfFilledFields(…)} / {@code NumberOfFilledGroups(…)} — a numeric aggregate over an
 * entity list (the kernel's {@code feldListenAnzahlOp}). The kernel's slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS): {@code NumberOfFilledFields} needs more than one field <em>or</em>
 * at least one group ({@code MVK_PARAMSIZE_INVALIDN}); {@code NumberOfFilledGroups} needs more than
 * one group <em>or</em> a sole group whose path carries a star ({@code Group*} counting that group's
 * instantiated repetitions, empty ones included, or a star on an ancestor level), groups only ({@code MVK_PARAMSIZE_INVALIDGN}, {@code
 * MVK_NO_FIELDS_ALLOWED}); a root group is banned in {@code NumberOfFilledFields} ({@code
 * MVK_ROOT_GROUP_REFERENCED}) and admitted by {@code NumberOfFilledGroups} only alone ({@code
 * MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS}). The
 * {@link Dsl} entry points enforce all of that by signature + pre-check; field kinds may be mixed.
 *
 * <p>The operands are ONE list in authored order, because A12 reads one ({@code getRefList}), and a group is held
 * as the scope it renders as. So one text is one value however it was spelled: a {@link GroupRef} passed to a
 * factory becomes {@link #scopeOf}, equal to the scope a reader builds from the same text (DEV-API-GAPS DG77,
 * {@code dsl.CountAggregateCanonicalShapeTest}).
 */
public record CountAggregate(CountOp op, List<AggregateOperand> operands) implements Calculation {

    public enum CountOp {
        FILLED_FIELDS("NumberOfFilledFields"),
        FILLED_GROUPS("NumberOfFilledGroups"),
        DIFFERENT_VALUES("NumberOfDifferentValues");

        private final String operatorId;

        CountOp(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public CountAggregate {
        operands = List.copyOf(operands);
        try {
            requireList(op, operands);
        } catch (KernelCodeRefusal refusal) {
            throw OperandRead.cited(refusal, operands);
        }
    }

    /**
     * A group as this carrier's operand: its scope, starred exactly when the group repeats, which is how the
     * count factories have always rendered a {@link GroupRef}. The group-list quantifiers wrap the same reference
     * unstarred, so the projection belongs to this carrier rather than to {@link GroupRef}.
     */
    static GroupListRef scopeOf(GroupRef group) {
        return new GroupListRef(group, group.repeating() ? List.of(group) : List.of());
    }

    /** {@link #scopeOf} over a list, keeping its order. */
    static List<AggregateOperand> scopesOf(List<GroupRef> groups) {
        return groups.stream().<AggregateOperand>map(CountAggregate::scopeOf).toList();
    }

    /**
     * The list gates, with their classes as A12 would report them once every operand has been read: the
     * constructor withholds them where a read may answer first, and {@link BoundModel}'s count factories ask
     * them after their own read.
     */
    static void requireList(CountOp op, List<? extends AggregateOperand> operands) {
        // Ahead of the arity and kind gates, matching the kernel: its routine for this operator
        // answers a duplicate before the param-size, fields-allowed and comparability bars, so our
        // order reported those instead on input both sides refuse.
        DuplicateGate.rejectDuplicates(op.operatorId(), operands);
        if (op == CountOp.DIFFERENT_VALUES) {
            // A12 asks the root-group gate after duplicates and before the param law on this operator.
            EntityListRoute.rejectRootGroups(op.operatorId(), operands);
        }
        switch (op) {
            case FILLED_FIELDS, DIFFERENT_VALUES -> {
                if (op == CountOp.FILLED_FIELDS) {
                    // DIFFERENT_VALUES asked this above, where A12 asks it on that operator.
                    EntityListRoute.rejectRootGroups(op.operatorId(), operands);
                }
                // a single flattened repetition list or group scope also satisfies the param law (verified)
                if (operands.size() < 2 && operands.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
                    throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDN, op.operatorId() + " needs more than one field,"
                            + " a repetition list, or a group");
                }
            }
            case FILLED_GROUPS -> {
                // A12 asks these gates in this order over the one list it reads
                // (CheckFeldListenAnzahlOpImpl; validate.laws.GroupCountScopeOperandLawsTest).
                // A root is legal here only ALONE, and A12 asks that after duplicates
                // under a class of its own (KERNEL-FINDINGS KF388, validate.laws.GroupListDiagnosticIdentityLawsTest).
                if (operands.size() > 1) {
                    for (AggregateOperand entity : operands) {
                        Optional<GroupRef> root = EntityListRoute.rootNamedBy(entity);
                        if (root.isPresent()) {
                            throw Refusals.of(KernelCode.MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS,
                                    "NumberOfFilledGroups takes a root group only as its sole operand: "
                                            + root.get().path());
                        }
                    }
                }
                for (AggregateOperand entity : operands) {
                    if (indexSelected(entity)) {
                        throw Refusals.of(KernelCode.MVK_SEMANTIC_INDEX_NOT_ALLOWED,
                                "NumberOfFilledGroups takes no index-selected operand: " + entity.renderedPath());
                    }
                }
                for (AggregateOperand entity : operands) {
                    if (!(entity instanceof GroupListRef)) {
                        throw Refusals.of(KernelCode.MVK_NO_FIELDS_ALLOWED,
                                "NumberOfFilledGroups takes groups only, no fields: " + entity.renderedPath());
                    }
                }
                // ≥2 group operands, OR a sole one whose path carries a star on any level, its own or an
                // ancestor's (KERNEL-FINDINGS KF462); `Group*` counts that group's instantiated repetitions (KF463).
                boolean soleStarred = operands.size() == 1
                        && !((GroupListRef) operands.getFirst()).flattened().isEmpty();
                if (operands.size() < 2 && !soleStarred) {
                    throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDGN,
                            "NumberOfFilledGroups needs more than one group, or a single starred group scope");
                }
                GroupRef sole = soleStarred ? ((GroupListRef) operands.getFirst()).group() : null;
                if (sole != null && isRoot(sole)) {
                    // A12 admits this text; it is the root-group count, a record of its own.
                    throw new IllegalArgumentException("NumberOfFilledGroups over the repeatable root group "
                            + sole.path() + " counts its repetitions: build it with Dsl.numberOfFilledRootGroups");
                }
            }
        }
        if (op == CountOp.DIFFERENT_VALUES) {
            checkValueComparable(operands);
        }
    }

    /** Whether A12 reads the operand through a semantic index, which this operator refuses. */
    private static boolean indexSelected(AggregateOperand operand) {
        return switch (operand) {
            case IndexedFieldValue ignored -> true;
            case IndexedGroupValue ignored -> true;
            case FieldRef ignored -> false;
            case FieldListRef ignored -> false;
            case GroupListRef ignored -> false;
        };
    }

    private static boolean isRoot(GroupRef group) {
        return group.path().value().indexOf('/', 1) < 0;
    }

    /**
     * NumberOfDifferentValues compares values, so its operands must be type-homogeneous (verified
     * 2026-06-04; temporal operands added 2026-07-25): string/enum/custom together, all numbers, or all
     * <b>date-like</b> (DATE/DATE_FRAGMENT/TIME/DATETIME — the kernel's own code name says so and it accepts
     * each pair, {@code validate.laws.NumberOfDifferentValuesKindLawsTest}). Booleans/confirms never.
     *
     * <p>A custom field type counts as textual and a date fragment as date-like — both are ordinary kinds the
     * model produces, and the kernel compares them here. {@code OpListMath.resultKind} folds DATE_FRAGMENT
     * into DATE for the Min/Max sibling on the same grounds.
     *
     * <p><b>The LEADING field picks the gate, and so the class</b> ({@link EntityListRoute}): a leader of no
     * comparable class draws {@code MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED}, and behind a comparable
     * leader every other operand, a Boolean included, is refused under the leader's own class
     * ({@code validate.laws.NumberOfDifferentValuesOperandClassLawsTest}). Behind a date-like leader A12 walks
     * the list asking each operand for its date precision and component set too, model data this record does
     * not see, so there the refusal stands and its class is withheld; {@link BoundModel#numberOfDifferentValues}
     * runs that walk first and cites it. Behind a group leader the leading class itself is hidden.
     */
    static void checkValueComparable(List<? extends AggregateOperand> operands) {
        // Group scopes carry no kind of their own — the rule applies to the fields the kernel expands them
        // to, and both the admissible set and the homogeneity verdict over an expansion stay kernel-arbitrated
        // (AggregateOperand). So the partition below runs over the FIELD operands, which is all it can see.
        List<FieldOperand> fields = operands.stream()
                .filter(FieldOperand.class::isInstance).map(FieldOperand.class::cast).toList();
        if (fields.isEmpty()) {
            return;
        }
        Optional<ValueKind> leader = EntityListRoute.visibleLeaderKind(operands);
        if (leader.isEmpty()) {
            // A kind no class admits, or two classes among the fields, is refused whatever class the hidden
            // leader selects; which class A12 names for it is the leader's.
            boolean certain = fields.stream().anyMatch(f -> ValueClass.of(f.kind()).isEmpty())
                    || fields.stream().map(f -> ValueClass.of(f.kind())).distinct().count() > 1;
            if (certain) {
                throw new IllegalArgumentException("NumberOfDifferentValues operands must be type-homogeneous;"
                        + " the leading operand " + operands.getFirst().renderedPath()
                        + " decides the class, and its kind is not visible here");
            }
            return;
        }
        ValueClass leading = ValueClass.of(leader.get()).orElseThrow(() -> Refusals.of(
                KernelCode.MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED, "NumberOfDifferentValues cannot compare "
                        + operands.getFirst().renderedPath() + " (" + leader.get() + ")"));
        for (FieldOperand f : fields) {
            if (ValueClass.of(f.kind()).orElse(null) != leading) {
                String problem = "NumberOfDifferentValues operands must be type-homogeneous; " + f.renderedPath()
                        + " (" + f.kind() + ") breaks the class its leading operand sets";
                throw leading == ValueClass.TEMPORAL || f.kind() == ValueKind.UNKNOWN
                        ? new IllegalArgumentException(problem)
                        : Refusals.of(leading.mismatchCode(), problem);
            }
        }
    }

    /** The three comparable value classes the kernel partitions {@code NumberOfDifferentValues} operands into. */
    private enum ValueClass {
        NUMERIC(KernelCode.MVK_NUMBER_AND_NON_NUMBER),
        TEXTUAL(KernelCode.MVK_STRING_ENUM_AND_NON_STRING_ENUM),
        TEMPORAL(KernelCode.MVK_DATE_AND_NONDATE);

        private final KernelCode mismatchCode;

        ValueClass(KernelCode mismatchCode) {
            this.mismatchCode = mismatchCode;
        }

        KernelCode mismatchCode() {
            return mismatchCode;
        }

        /** The class a kind belongs to, or empty for a kind the operator cannot compare at all. */
        static Optional<ValueClass> of(ValueKind kind) {
            return switch (kind) {
                case NUMBER -> Optional.of(NUMERIC);
                case DATE, DATE_FRAGMENT, TIME, DATE_TIME -> Optional.of(TEMPORAL);
                case STRING, ENUMERATION, CUSTOM -> Optional.of(TEXTUAL);
                case BOOLEAN, CONFIRM, DATE_RANGE, UNKNOWN -> Optional.empty();
            };
        }
    }

    public String operatorId() {
        return op.operatorId();
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }
}
