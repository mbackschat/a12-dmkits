package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code NumberOfValueInFields(value IN f1, f2, …)} — the count of the listed fields whose value equals
 * the given constant (the kernel {@code EntityListOperationName.NumberOfValueInFields}, an
 * {@code IEntityListOperationWithValue}). A number-valued {@link Calculation}. The {@code value} is a
 * constant — a boolean ({@code True}), number, string, or date literal — rendered as a value; the fields
 * render as bare entity refs after the {@code In} grammar literal.
 *
 * <p>The kernel requires at least two fields (or at least one group); {@code MVK_PARAMSIZE_INVALIDN}
 * otherwise — that is its call (F4/N7), not pre-checked here. Build via {@link Dsl#numberOfValueInFields}.
 *
 * <p><b>The operand list is an {@link AggregateOperand} list, not a {@link FieldOperand} list</b>, because the
 * kernel routes this operator's entities through the same {@code getRefList} as the entity-list aggregates — so
 * a {@link GroupListRef} is admitted here for the same reason it is under {@code Sum}, and the kernel expands it
 * to its contained fields before counting ({@code KF187}; {@code validate.laws.ValueInFieldsGroupOperandLawsTest}
 * locks the admission, {@code adapter.laws.GroupStarValueInFieldsDiffTest} the runtime). It was a
 * {@code FieldOperand} list until 2026-08-14, which made the admitted group shape <em>unrepresentable</em>
 * rather than refused — {@code DG34}, a type-level erasure with no measured basis.
 */
public record NumberOfValueInFields(Calculation value, List<AggregateOperand> fields) implements Calculation {

    public NumberOfValueInFields {
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("NumberOfValueInFields needs at least one field");
        }
        try {
            DuplicateGate.rejectDuplicates("NumberOfValueInFields", fields);
        } catch (KernelCodeRefusal refusal) {
            throw OperandRead.cited(refusal, fields);
        }
    }

    @Override
    public ValueKind kind() {
        return ValueKind.NUMBER;
    }

    public String operatorId() {
        return "NumberOfValueInFields";
    }
}
