package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code NumberOfFilledFields(…)} / {@code NumberOfFilledGroups(…)} — a numeric aggregate over an
 * entity list (the kernel's {@code feldListenAnzahlOp}). The kernel's slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS): {@code NumberOfFilledFields} needs more than one field <em>or</em>
 * at least one group ({@code MVK_PARAMSIZE_INVALIDN}); {@code NumberOfFilledGroups} needs more than
 * one group <em>or</em> a single repeatable group as a wildcard scope ({@code Group*}, counting that
 * group's filled repetitions), groups only ({@code MVK_PARAMSIZE_INVALIDGN}, {@code
 * MVK_NO_FIELDS_ALLOWED}); a root group is banned in {@code NumberOfFilledFields} ({@code
 * MVK_ROOT_GROUP_REFERENCED}) and admitted by {@code NumberOfFilledGroups} only alone ({@code
 * MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS}). The
 * {@link Dsl} entry points enforce all of that by signature + pre-check; field kinds may be mixed.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;

public record CountAggregate(CountOp op, List<AggregateOperand> fields, List<GroupRef> groups)
        implements Calculation {

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
        fields = List.copyOf(fields);
        groups = List.copyOf(groups);
        try {
            requireList(op, fields, groups);
        } catch (KernelCodeRefusal refusal) {
            throw EntityListRoute.cited(refusal, allOperands(fields, groups));
        }
    }

    /**
     * The list gates, with their classes as A12 would report them once every operand has been read: the
     * constructor withholds them where a read may answer first, and {@link BoundModel}'s count factories ask
     * them after their own read.
     */
    static void requireList(CountOp op, List<AggregateOperand> fields, List<GroupRef> groups) {
        // Ahead of the arity and kind gates, matching the kernel: its routine for this operator
        // answers a duplicate before the param-size, fields-allowed and comparability bars, so our
        // order reported those instead on input both sides refuse.
        DuplicateGate.rejectDuplicates(op.operatorId(), allOperands(fields, groups));
        if (op == CountOp.DIFFERENT_VALUES) {
            // A12 asks the root-group gate after duplicates and before the param law on this operator.
            EntityListRoute.rejectRootGroups(op.operatorId(), fields);
        }
        switch (op) {
            case FILLED_FIELDS, DIFFERENT_VALUES -> {
                if (op == CountOp.FILLED_FIELDS) {
                    // DIFFERENT_VALUES asked this above, where A12 asks it on that operator.
                    EntityListRoute.rejectRootGroups(op.operatorId(), allOperands(fields, groups));
                }
                // a single flattened repetition list or group scope also satisfies the param law (verified)
                if (fields.size() < 2 && groups.isEmpty()
                        && fields.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
                    throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDN, op.operatorId() + " needs more than one field,"
                            + " a repetition list, or a group");
                }
            }
            case FILLED_GROUPS -> {
                // A root is legal here only ALONE, and A12 asks that ahead of the fields-allowed and arity bars
                // under a class of its own (CheckFeldListenAnzahlOpImpl, KERNEL-FINDINGS KF388,
                // validate.laws.GroupListDiagnosticIdentityLawsTest).
                if (fields.size() + groups.size() > 1) {
                    for (GroupRef g : groups) {
                        if (isRoot(g)) {
                            throw Refusals.of(KernelCode.MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS,
                                    "NumberOfFilledGroups takes a root group only as its sole operand: " + g.path());
                        }
                    }
                }
                if (!fields.isEmpty()) {
                    throw Refusals.of(KernelCode.MVK_NO_FIELDS_ALLOWED,
                            "NumberOfFilledGroups takes groups only, no fields");
                }
                // ≥2 distinct groups, OR a single repeatable group as a wildcard scope (Group*) —
                // the latter counts that group's filled repetitions (kernel-accepted, verified).
                boolean singleWildcard = groups.size() == 1 && groups.get(0).repeating();
                if (groups.size() < 2 && !singleWildcard) {
                    throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDGN,
                            "NumberOfFilledGroups needs more than one group, or a"
                                    + " single repeatable group as a wildcard scope");
                }
                if (singleWildcard && isRoot(groups.get(0))) {
                    // A12 admits this text; it is the root-group count, a record of its own.
                    throw new IllegalArgumentException("NumberOfFilledGroups over the repeatable root group "
                            + groups.get(0).path() + " counts its repetitions: build it with Dsl.numberOfFilledRootGroups");
                }
            }
        }
        if (op == CountOp.DIFFERENT_VALUES) {
            checkValueComparable(fields);
        }
    }

    private static boolean isRoot(GroupRef group) {
        return group.path().value().indexOf('/', 1) < 0;
    }

    /**
     * Both lists as ONE list of operands, because the kernel's own {@code getRefList} sees one: a field in
     * this record's field list and its containing group in the group list are an overlap, and checking the
     * two lists separately would miss the pair. No factory populates both — each passes an empty list for
     * the other — but the canonical constructor is public and the read path uses it. It is also the order in
     * which A12 reads them.
     */
    static List<AggregateOperand> allOperands(List<AggregateOperand> fields, List<GroupRef> groups) {
        var all = new java.util.ArrayList<AggregateOperand>(fields);
        all.addAll(groupOperands(groups));
        return all;
    }

    /**
     * This carrier's group list as duplicate operands, each starred exactly when the group repeats.
     *
     * <p>The star is applied here because it is applied when RENDERING — {@code countOperands} emits
     * {@code path + "*"} for a repeatable group — and the kernel's exact-duplicate arm skips whatever carries
     * a star in the emitted text. Projecting rather than gating the {@link GroupRef}s in place keeps one
     * exemption rule: the group-list quantifiers wrap the same reference UNSTARRED and render it bare, so the
     * identical {@link GroupRef} is exempt on this carrier and not on that one. Exemption belongs to the
     * rendered operand.
     */
    private static List<GroupListRef> groupOperands(List<GroupRef> groups) {
        return groups.stream()
                .map(group -> new GroupListRef(group, group.repeating() ? List.of(group) : List.of()))
                .toList();
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
    private static void checkValueComparable(List<AggregateOperand> operands) {
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
