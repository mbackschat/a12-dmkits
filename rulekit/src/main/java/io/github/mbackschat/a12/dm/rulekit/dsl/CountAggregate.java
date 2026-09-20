package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code NumberOfFilledFields(…)} / {@code NumberOfFilledGroups(…)} — a numeric aggregate over an
 * entity list (the kernel's {@code feldListenAnzahlOp}). The kernel's slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS): {@code NumberOfFilledFields} needs more than one field <em>or</em>
 * at least one group ({@code MVK_PARAMSIZE_INVALIDN}); {@code NumberOfFilledGroups} needs more than
 * one group <em>or</em> a single repeatable group as a wildcard scope ({@code Group*}, counting that
 * group's filled repetitions), groups only ({@code MVK_PARAMSIZE_INVALIDGN}, {@code
 * MVK_NO_FIELDS_ALLOWED}); root groups are banned in both ({@code MVK_ROOT_GROUP_REFERENCED}). The
 * {@link Dsl} entry points enforce all of that by signature + pre-check; field kinds may be mixed.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
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
        // Ahead of the arity and kind gates, matching the kernel: its routine for this operator
        // answers a duplicate before the param-size, fields-allowed and comparability bars, so our
        // order reported those instead on input both sides refuse.
        DuplicateGate.rejectDuplicates(op.operatorId(), allOperands(fields, groups));
        switch (op) {
            case FILLED_FIELDS, DIFFERENT_VALUES -> {
                // a single flattened repetition list or group scope also satisfies the param law (verified)
                if (fields.size() < 2 && groups.isEmpty()
                        && fields.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
                    throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDN, op.operatorId() + " needs more than one field,"
                            + " a repetition list, or a group");
                }
            }
            case FILLED_GROUPS -> {
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
            }
        }
        if (op == CountOp.DIFFERENT_VALUES) {
            checkValueComparable(fields);
        }
        for (GroupRef g : groups) {
            if (g.path().value().indexOf('/', 1) < 0) {
                throw Refusals.of(KernelCode.MVK_ROOT_GROUP_REFERENCED, "a root group may not be referenced in "
                        + op.operatorId() + ": " + g.path());
            }
        }
    }

    /**
     * Both lists as ONE list of operands, because the kernel's own {@code getRefList} sees one: a field in
     * this record's field list and its containing group in the group list are an overlap, and checking the
     * two lists separately would miss the pair. No factory populates both — each passes an empty list for
     * the other — but the canonical constructor is public and the read path uses it.
     */
    private static List<DuplicateOperand> allOperands(List<AggregateOperand> fields, List<GroupRef> groups) {
        var all = new java.util.ArrayList<DuplicateOperand>(fields);
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
     * <p>Two temporal laws are deliberately NOT pre-checked and stay F4-arbitrated, as the MVK ledger
     * classifies them: mixing a date with a non-date is {@code MVK_DATE_AND_NONDATE} (caught by the
     * homogeneity partition below, which reports that code), and two date-like operands whose FORMATS are
     * incompatible is {@code MVK_DATEFORMATS_NOT_COMPATIBLE} — format compatibility is model data the kernel
     * arbitrates, so pre-checking it here would risk refusing a legal pair.
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
        for (FieldOperand f : fields) {
            switch (f.kind()) {
                case STRING, ENUMERATION, CUSTOM, NUMBER, DATE, DATE_FRAGMENT, TIME, DATE_TIME -> { }
                default -> throw Refusals.of(KernelCode.MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED,
                        "NumberOfDifferentValues cannot compare " + f.renderedPath() + " (" + f.kind() + ")");
            }
        }
        ValueClass first = ValueClass.of(fields.getFirst().kind());
        for (FieldOperand f : fields) {
            ValueClass actual = ValueClass.of(f.kind());
            if (actual != first) {
                throw Refusals.of(first.mismatchCode(), "NumberOfDifferentValues operands must be"
                        + " type-homogeneous; " + f.renderedPath() + " breaks it");
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

        static ValueClass of(ValueKind kind) {
            return switch (kind) {
                case NUMBER -> NUMERIC;
                case DATE, DATE_FRAGMENT, TIME, DATE_TIME -> TEMPORAL;
                default -> TEXTUAL;
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
