package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;

/**
 * A12 reads every operand of a list before it asks any list gate, so a read that fails answers first: a
 * semantic index that does not resolve (no index field, an empty or ill-typed key), a group holding no field,
 * an empty root included, an undeclared category, and a {@code $} marker outside a filter
 * (KERNEL-FINDINGS KF458). That holds on every list carrier, the entity-list aggregates
 * ({@code validate.laws.EntityListOperandReadLawsTest}) and the fill, group-list and value-list quantifiers,
 * {@code NumberOfValueInFields}, {@code RepetitionNotUnique} and the scalar value list alike
 * ({@code validate.laws.EntityListQuantifierReadLawsTest}).
 *
 * <p>A record sees none of those outcomes: an index handle never sees the model, a group scope does not carry
 * its expansion, and a category projection does not carry the enumeration's categories. So on a list holding
 * such an operand, a list gate's refusal stands and its class is withheld (API-FINDINGS AF160). A plain field's
 * read can still answer first where it crosses a repeatable level the rule does not iterate, which a
 * {@link FieldRef} does not show (DEV-API-GAPS DG75); {@link BoundModel}'s factories read the model and answer
 * for groups themselves.
 */
final class OperandRead {

    private OperandRead() {
    }

    /** A list gate's refusal as a record may cite it: withheld when an operand's read may answer first. */
    static IllegalArgumentException cited(KernelCodeRefusal refusal, List<? extends DuplicateOperand> operands) {
        return operands.stream().anyMatch(OperandRead::mayRefuse) ? refusal.withheld() : refusal;
    }

    /** Whether A12 may refuse this operand while reading it, from a fact the operand does not carry. */
    static boolean mayRefuse(DuplicateOperand operand) {
        return switch (operand) {
            case IndexedFieldValue ignored -> true;
            case IndexedGroupValue ignored -> true;
            case GroupListRef ignored -> true;
            case EnumCategoryAccess ignored -> true;
            case FieldRef field -> field.correlated();
            case FieldListRef list -> list.correlated();
            case FieldValue value -> value.outer();
            case FirstFilledOperand first -> first.readMayAnswerFirst();
        };
    }
}
