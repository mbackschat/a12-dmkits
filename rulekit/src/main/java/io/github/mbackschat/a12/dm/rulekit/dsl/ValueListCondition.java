package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * {@code FieldValueIncludedInValueList(field, v1, v2, …)} / {@code FieldValueNotIncludedInValueList(…)}
 * — membership of a single field's value in a literal list. The kernel's slot rules (verified
 * 2026-06-04, KERNEL-FINDINGS): only string/enum/number fields ({@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED}),
 * the field is a bare path, values must match the field's kind ({@code MVK_INVALID_TYPES_FOR_COMPARISON}),
 * and numeric values are <em>integer literals only</em> (grammar). The typed handles
 * ({@code in}/{@code notIn} on {@link StringRef}/{@link NumberRef}/{@link EnumRef}) make all of
 * that unrepresentable.
 *
 * <p><b>The value list admits field REFERENCES beside literals</b> ({@code in(StringRef, …)},
 * {@code in(NumberRef, …)} — DG10), which makes this a duplicate carrier: the kernel builds one union of the
 * value list plus the subject field and checks it for a repeated reference. Only the exact arm applies — a
 * group operand is refused outright here ({@code MVK_NO_GROUPS_ALLOWED}), so nothing can contain anything.
 */
public record ValueListCondition(FieldRef field, boolean included, List<Calculation> values)
        implements Condition {

    public ValueListCondition {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("a value list needs at least one value");
        }
        DuplicateGate.rejectExactDuplicates(operatorId(included), union(field, values));
    }

    /**
     * The value list and the subject as the kernel builds them — values first, subject LAST.
     *
     * <p>The order mirrors the kernel's construction and nothing here depends on it: the arm reports the
     * second occurrence, and a duplicate on this carrier means the subject and a value name the same entity,
     * so both occurrences render the same text either way. Stated because the mirroring is deliberate, not
     * because it is observable — a mutant that reverses it survives, and correctly so.
     */
    private static List<DuplicateOperand> union(FieldRef field, List<Calculation> values) {
        var union = new java.util.ArrayList<DuplicateOperand>();
        for (Calculation value : values) {
            if (value instanceof DuplicateOperand operand) {
                union.add(operand);
            }
        }
        union.add(field);
        return union;
    }

    private static String operatorId(boolean included) {
        return included ? "FieldValueIncludedInValueList" : "FieldValueNotIncludedInValueList";
    }

    @Override
    public String operatorId() {
        return operatorId(included);
    }
}
