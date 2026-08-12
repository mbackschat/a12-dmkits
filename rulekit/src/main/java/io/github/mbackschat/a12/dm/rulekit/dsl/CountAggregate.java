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
        switch (op) {
            case FILLED_FIELDS, DIFFERENT_VALUES -> {
                // a single flattened repetition list or group scope also satisfies the param law (verified)
                if (fields.size() < 2 && groups.isEmpty()
                        && fields.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
                    throw new IllegalArgumentException(op.operatorId() + " needs more than one field,"
                            + " a repetition list, or a group (MVK_PARAMSIZE_INVALIDN)");
                }
            }
            case FILLED_GROUPS -> {
                if (!fields.isEmpty()) {
                    throw new IllegalArgumentException(
                            "NumberOfFilledGroups takes groups only, no fields (MVK_NO_FIELDS_ALLOWED)");
                }
                // ≥2 distinct groups, OR a single repeatable group as a wildcard scope (Group*) —
                // the latter counts that group's filled repetitions (kernel-accepted, verified).
                boolean singleWildcard = groups.size() == 1 && groups.get(0).repeating();
                if (groups.size() < 2 && !singleWildcard) {
                    throw new IllegalArgumentException("NumberOfFilledGroups needs more than one group, or a"
                            + " single repeatable group as a wildcard scope (MVK_PARAMSIZE_INVALIDGN)");
                }
            }
        }
        if (op == CountOp.DIFFERENT_VALUES) {
            checkValueComparable(fields);
        }
        ListAggregate.checkNoDuplicates(op.operatorId(), fields);
        for (GroupRef g : groups) {
            if (g.path().indexOf('/', 1) < 0) {
                throw new IllegalArgumentException("a root group may not be referenced in "
                        + op.operatorId() + ": " + g.path() + " (MVK_ROOT_GROUP_REFERENCED)");
            }
        }
    }

    /**
     * NumberOfDifferentValues compares values, so its operands must be type-homogeneous (verified
     * 2026-06-04; temporal operands added 2026-07-25): string/enum together, all numbers, or all
     * <b>date-like</b> (DATE/TIME/DATETIME — the kernel's own code name says so and it accepts each pair,
     * {@code validate.laws.AggregateSlotLawsTest}). Booleans/confirms never.
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
                case STRING, ENUMERATION, NUMBER, DATE, TIME, DATE_TIME -> { }
                default -> throw new IllegalArgumentException("NumberOfDifferentValues cannot compare "
                        + f.renderedPath() + " (" + f.kind()
                        + ") — MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED");
            }
        }
        ValueClass first = ValueClass.of(fields.getFirst().kind());
        for (FieldOperand f : fields) {
            ValueClass actual = ValueClass.of(f.kind());
            if (actual != first) {
                throw new IllegalArgumentException("NumberOfDifferentValues operands must be"
                        + " type-homogeneous; " + f.renderedPath() + " breaks it (" + first.mismatchCode() + ")");
            }
        }
    }

    /** The three comparable value classes the kernel partitions {@code NumberOfDifferentValues} operands into. */
    private enum ValueClass {
        NUMERIC("MVK_NUMBER_AND_NON_NUMBER"),
        TEXTUAL("MVK_STRING_ENUM_AND_NON_STRING_ENUM"),
        TEMPORAL("MVK_DATE_AND_NONDATE");

        private final String mismatchCode;

        ValueClass(String mismatchCode) {
            this.mismatchCode = mismatchCode;
        }

        String mismatchCode() {
            return mismatchCode;
        }

        static ValueClass of(ValueKind kind) {
            return switch (kind) {
                case NUMBER -> NUMERIC;
                case DATE, TIME, DATE_TIME -> TEMPORAL;
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
