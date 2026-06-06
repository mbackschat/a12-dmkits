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
public record CountAggregate(CountOp op, List<FieldOperand> fields, List<GroupRef> groups)
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
                // a single flattened repetition list also satisfies the param law (verified)
                if (fields.size() < 2 && groups.isEmpty()
                        && fields.stream().noneMatch(f -> f instanceof FieldListRef)) {
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
     * 2026-06-04): string/enum together, or all numbers; booleans/confirms never (dates ride the
     * date family).
     */
    private static void checkValueComparable(List<FieldOperand> fields) {
        for (FieldOperand f : fields) {
            switch (f.kind()) {
                case STRING, ENUMERATION, NUMBER -> { }
                default -> throw new IllegalArgumentException("NumberOfDifferentValues cannot compare "
                        + f.renderedPath() + " (" + f.kind()
                        + ") — MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED");
            }
        }
        boolean firstIsNumber = fields.getFirst().kind() == ValueKind.NUMBER;
        for (FieldOperand f : fields) {
            if ((f.kind() == ValueKind.NUMBER) != firstIsNumber) {
                String code = firstIsNumber ? "MVK_NUMBER_AND_NON_NUMBER" : "MVK_STRING_ENUM_AND_NON_STRING_ENUM";
                throw new IllegalArgumentException("NumberOfDifferentValues operands must be"
                        + " type-homogeneous; " + f.renderedPath() + " breaks it (" + code + ")");
            }
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
