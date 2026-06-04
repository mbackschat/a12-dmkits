package io.github.mbackschat.a12.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * {@code NumberOfFilledFields(…)} / {@code NumberOfFilledGroups(…)} — a numeric aggregate over an
 * entity list (the kernel's {@code feldListenAnzahlOp}). The kernel's slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS): {@code NumberOfFilledFields} needs more than one field <em>or</em>
 * at least one group ({@code MVK_PARAMSIZE_INVALIDN}); {@code NumberOfFilledGroups} needs more than
 * one group, groups only ({@code MVK_PARAMSIZE_INVALIDGN}, {@code MVK_NO_FIELDS_ALLOWED}); root
 * groups are banned in both ({@code MVK_ROOT_GROUP_REFERENCED}). The {@link Dsl} entry points
 * enforce all of that by signature + pre-check; field kinds may be mixed.
 */
public record CountAggregate(CountOp op, List<FieldOperand> fields, List<GroupRef> groups)
        implements Calculation {

    public enum CountOp {
        FILLED_FIELDS("NumberOfFilledFields"),
        FILLED_GROUPS("NumberOfFilledGroups");

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
            case FILLED_FIELDS -> {
                if (fields.size() < 2 && groups.isEmpty()) {
                    throw new IllegalArgumentException("NumberOfFilledFields needs more than one field"
                            + " or at least one group (MVK_PARAMSIZE_INVALIDN)");
                }
            }
            case FILLED_GROUPS -> {
                if (!fields.isEmpty() || groups.size() < 2) {
                    throw new IllegalArgumentException(
                            "NumberOfFilledGroups needs more than one group (MVK_PARAMSIZE_INVALIDGN)");
                }
            }
        }
        for (GroupRef g : groups) {
            if (g.path().indexOf('/', 1) < 0) {
                throw new IllegalArgumentException("a root group may not be referenced in "
                        + op.operatorId() + ": " + g.path() + " (MVK_ROOT_GROUP_REFERENCED)");
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
