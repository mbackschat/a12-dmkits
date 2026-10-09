package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * What A12's grammar admits as a {@code Date}/{@code Time} component: {@code datumParam} is an entity, a date/time
 * extract operation or a quoted constant, so a field read, a keyed cell, a constant and an extractor are the whole
 * set. Any other operation there is a parse error ({@code MVK_UNEXPECTED_TOKEN}), and a stamp-splitting extract
 * operation one no slot expects ({@code MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED}) — each measured in every slot of
 * both constructions ({@code adapter.laws.CheckerConstructorComponentAgreementTest}, KERNEL-FINDINGS KF532).
 *
 * <p>A {@link NumberLiteral} is admitted although A12 has no bare-number arm: the renderer writes it as the quoted
 * constant A12 does admit ({@code RenderConditionTest.rendersConstantDateAndTimeComponentsQuoted}), so the
 * {@code MVK_INVALID_ENTITY} a bare number draws belongs to condition TEXT, never to a typed construction.
 *
 * <p>Checked when the construction is built, because {@link TemporalComponent}'s numeric-expression arm cannot
 * say which expressions are extractors, and the slot's other gates in the construction records assume one of
 * these four shapes.
 */
final class TemporalComponentGrammar {

    private TemporalComponentGrammar() {
    }

    static void refuseOutsideGrammar(String operatorId, Calculation component) {
        switch (component) {
            case FieldValue read -> { }
            case IndexedFieldValue cell -> { }
            case StringLiteral constant -> { }
            case DateExtraction extractor -> { }
            case DateTimePart split -> throw Refusals.of(KernelCode.MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED,
                    "a " + operatorId + " component admits no " + split.unit().operatorId());
            case NumberLiteral number -> { }
            default -> throw Refusals.of(KernelCode.MVK_UNEXPECTED_TOKEN, "a " + operatorId + " component is a "
                    + "field, a keyed cell, a quoted constant or an extractor; compute the value into a field first");
        }
    }
}
