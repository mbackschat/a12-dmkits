package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.EntityListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A wildcard field operand is a flattened list, even without a {@code Having} filter. Reading it as a
 * plain field silently loses both the star and the per-repetition quantifier semantics.
 */
class WildcardFieldFillQuantifierReadTest {

    private static final DslRenderer RENDERER = new DslRenderer();

    @Test
    void preservesWildcardAndMixedOperandsForEveryFieldFillQuantifier() {
        BoundModel model = calibrationModel();

        for (FillQuantifier quantifier : FillQuantifier.values()) {
            assertWildcardOperands(model, quantifier, false);
            assertWildcardOperands(model, quantifier, true);
        }
    }

    @Test
    void aPlainSingleFieldPresenceStillReadsAsPresence() {
        BoundModel model = calibrationModel();
        FieldRef control = model.field("/Calibration/Control");
        Rule rule = rule(model, "PlainControl", control.filled());

        Condition converted = converted(KernelAdapter.of(model.add(rule)).read("/Calibration/PlainControl"));

        assertInstanceOf(FieldPresenceCondition.class, converted);
    }

    private static void assertWildcardOperands(BoundModel model, FillQuantifier quantifier, boolean mixed) {
        FieldRef control = model.field("/Calibration/Control");
        FieldListRef samples = model.flatten("/Calibration/Samples*/Reading");
        FieldOperand[] rest = mixed ? new FieldOperand[] {control} : new FieldOperand[0];
        Condition fieldFill = quantifier.apply(samples, rest);
        String ruleName = quantifier.name() + (mixed ? "Mixed" : "Wildcard");
        Condition authored = control.filled().and(fieldFill);
        Rule rule = rule(model, ruleName, authored);

        Condition converted = converted(KernelAdapter.of(model.add(rule)).read("/Calibration/" + ruleName));
        EntityListPredicate readFieldFill =
                assertInstanceOf(EntityListPredicate.class, assertInstanceOf(LogicCondition.class, converted).right());

        assertEquals(RENDERER.render(authored), RENDERER.render(converted), quantifier + " must retain its star");
        assertEquals(mixed ? 2 : 1, readFieldFill.operands().size());
        assertInstanceOf(FieldListRef.class, readFieldFill.operands().get(0));
        if (mixed) {
            assertInstanceOf(FieldRef.class, readFieldFill.operands().get(1));
        }
    }

    private static Rule rule(BoundModel model, String name, Condition condition) {
        return model.rule(model.group("/Calibration")).name(name)
                .errorField(model.field("/Calibration/Control")).when(condition)
                .code("CAL").severity(Severity.ERROR).message(en("calibration failed")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted rule, got " + result).condition();
    }

    private static BoundModel calibrationModel() {
        return BoundModel.ofJson(DocumentModelDraft.named("calibration").locale("en_US")
                .rootGroup("Calibration", root -> root
                        .numberField("Control", ignored -> { })
                        .group("Samples", samples -> samples.repeatable(100)
                                .numberField("Reading", ignored -> { })))
                .build().toJson());
    }

    private enum FillQuantifier {
        ALL,
        NONE,
        ANY,
        NOT_ALL,
        NOT_COLLECTIVE,
        NOT_EXACTLY_ONE,
        MORE_THAN_ONE;

        Condition apply(FieldOperand first, FieldOperand... rest) {
            return switch (this) {
                case ALL -> Dsl.allFieldsFilled(first, rest);
                case NONE -> Dsl.noFieldFilled(first, rest);
                case ANY -> Dsl.atLeastOneFieldFilled(first, rest);
                case NOT_ALL -> Dsl.notAllFieldsFilled(first, rest);
                case NOT_COLLECTIVE -> Dsl.fieldsNotCollectivelyFilled(first, rest);
                case NOT_EXACTLY_ONE -> Dsl.notExactlyOneFieldFilled(first, rest);
                case MORE_THAN_ONE -> Dsl.moreThanOneFieldFilled(first, rest);
            };
        }
    }
}
