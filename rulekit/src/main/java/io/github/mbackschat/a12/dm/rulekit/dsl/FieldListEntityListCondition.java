package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code AtLeastOne|No|NotAllFieldValue(s)IncludedInValueList(f1, f2 In g1, g2)} where the value list
 * holds <b>field references</b>, not constants — a field list's membership in <em>another</em> field
 * list (e.g. a field {@code In} a starred repetition list). The sibling of {@link
 * FieldListValueListCondition} (literal value lists); the difference is purely the value side, which
 * here renders as bare entity paths (a {@link ValueListOperand} each — a plain field, a flattened
 * repetition list, or an enum category projection {@code [field -> Cat]}) rather than quoted
 * constants. Same-base-kind and combined-reference uniqueness are enforced by the {@link Dsl} entry
 * point (the smallest model-aware owner); the value entities carry their static {@link
 * ValueListOperand#kind() kind} here.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

public record FieldListEntityListCondition(
        ValueListQuantifier quantifier, List<ValueListOperand> fields, List<ValueListOperand> valueFields)
        implements Condition {

    public FieldListEntityListCondition {
        fields = List.copyOf(fields);
        valueFields = List.copyOf(valueFields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("the field list needs at least one field");
        }
        if (valueFields.isEmpty()) {
            throw new IllegalArgumentException("the value field list needs at least one field");
        }
        if (quantifier == ValueListQuantifier.AT_LEAST_ONE_DATE_RANGE_OVERLAPS) {
            requireUnstarredSingleRange(fields);
        }
        // The kernel checks each list alone and then their UNION, so a reference repeated ACROSS the two
        // lists is a duplicate as much as one repeated within either. Gated here rather than in the Dsl
        // factory because the check is intrinsic — it needs no model — and one factory built this record
        // directly, which left the AtLeastOneDateRangeOverlaps arm with no gate at all.
        var combined = new ArrayList<ValueListOperand>(fields);
        combined.addAll(valueFields);
        DuplicateGate.rejectDuplicates(quantifier.operatorId(), combined);
    }

    /**
     * {@code AtLeastOneDateRangeOverlaps}' first slot takes exactly one UNSTARRED date-range field
     * ({@code MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON},
     * {@code validate.laws.DateRangeOverlapParameterLawsTest}).
     *
     * <p><b>The operand LIST is not covered</b>, and that is measured rather than an omission: a starred range
     * there is admitted, on this carrier and on {@code DateRangesOverlap} alike. The ledger's older wording —
     * "the range-overlap condition takes exactly one unstarred date-range field" — reads as a property of the
     * predicate family and is true of this slot only.
     *
     * <p>Intrinsic: whether an operand is starred is visible from the operand itself, so this belongs here
     * rather than on the model-bound facade (API-FINDINGS AF148).
     */
    private static void requireUnstarredSingleRange(List<ValueListOperand> fields) {
        for (ValueListOperand operand : fields) {
            if (operand instanceof FieldListRef starred) {
                throw Refusals.of(KernelCode.MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON,
                        "AtLeastOneDateRangeOverlaps takes exactly one unstarred "
                                + "date-range field in its first slot; '" + starred.field().path().value()
                                + "' is a repetition list");
            }
        }
    }

    @Override
    public String operatorId() {
        return quantifier.operatorId();
    }
}
