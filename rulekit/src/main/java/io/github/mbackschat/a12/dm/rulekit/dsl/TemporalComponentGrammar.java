package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * What A12's grammar admits as a {@code Date}/{@code Time} component: {@code datumParam} is an entity, a date/time
 * extract operation or a quoted constant, so a field read, a keyed cell, a constant and an extractor are the whole
 * set, and every other operation there is a parse error ({@code MVK_UNEXPECTED_TOKEN}), measured in every slot of
 * both constructions ({@code adapter.laws.CheckerConstructorComponentAgreementTest}, KERNEL-FINDINGS KF532).
 *
 * <p>The parse error answers before any slot's own gate and, between two of them, in text order, so a construction
 * asks this of every component before it asks any slot which extractor it takes (measured in both orders, for both
 * constructions). A typed literal of any kind is admitted because the renderer writes it as the quoted constant A12
 * admits ({@code RenderConditionTest.rendersConstantDateAndTimeComponentsQuoted}); the {@code MVK_INVALID_ENTITY} a
 * bare number draws belongs to condition TEXT, which the interpreter bridge refuses itself. A stamp-splitting
 * extract operation is grammatical, so its refusal is the slot's, not this check's.
 */
public final class TemporalComponentGrammar {

    private TemporalComponentGrammar() {
    }

    /**
     * Refuses a component A12's grammar cannot parse as a {@code Date}/{@code Time} component.
     *
     * @param operatorId {@code Date} or {@code Time}, for the message
     */
    public static void refuseOutsideGrammar(String operatorId, Calculation component) {
        switch (component) {
            case FieldValue read -> { }
            case IndexedFieldValue cell -> { }
            case StringLiteral constant -> { }
            case NumberLiteral constant -> { }
            case DateLiteral constant -> { }
            case TimeLiteral constant -> { }
            case DateTimeLiteral constant -> { }
            case EnumValueLiteral constant -> { }
            case DateExtraction extractor -> { }
            case DateTimePart extractor -> { }
            case BoolLiteral b -> throw outside(operatorId);
            case LengthOf l -> throw outside(operatorId);
            case EnumCategoryAccess e -> throw outside(operatorId);
            case CountAggregate c -> throw outside(operatorId);
            case ListAggregate l -> throw outside(operatorId);
            case CurrentRepetition c -> throw outside(operatorId);
            case Today t -> throw outside(operatorId);
            case Now n -> throw outside(operatorId);
            case BaseYear b -> throw outside(operatorId);
            case DateDifference d -> throw outside(operatorId);
            case DateShift d -> throw outside(operatorId);
            case Arithmetic a -> throw outside(operatorId);
            case Rounding r -> throw outside(operatorId);
            case Concatenation c -> throw outside(operatorId);
            case DateListAggregate d -> throw outside(operatorId);
            case FirstFilledValue f -> throw outside(operatorId);
            case FieldValueAsString f -> throw outside(operatorId);
            case IndexedFieldValueAsString i -> throw outside(operatorId);
            case RangeAsString r -> throw outside(operatorId);
            case FieldValueAsNumber f -> throw outside(operatorId);
            case RangeAsNumber r -> throw outside(operatorId);
            case OpListMath o -> throw outside(operatorId);
            case NumberOfValueInFields n -> throw outside(operatorId);
            case ValueAsDate v -> throw outside(operatorId);
            case SumOfProducts s -> throw outside(operatorId);
            case SumOfTerms s -> throw outside(operatorId);
            case DateConstruction d -> throw outside(operatorId);
            case TimeConstruction t -> throw outside(operatorId);
            case DateTimeConstruction d -> throw outside(operatorId);
            case FieldRounding f -> throw outside(operatorId);
            case DateRangeBound d -> throw outside(operatorId);
            case RootGroupCount r -> throw outside(operatorId);
            case DateRangeConstruction d -> throw outside(operatorId);
        }
    }

    private static KernelCodeRefusal outside(String operatorId) {
        return Refusals.of(KernelCode.MVK_UNEXPECTED_TOKEN, "a " + operatorId + " component is a field, a keyed cell, "
                + "a quoted constant or an extractor; compute the value into a field first");
    }
}
