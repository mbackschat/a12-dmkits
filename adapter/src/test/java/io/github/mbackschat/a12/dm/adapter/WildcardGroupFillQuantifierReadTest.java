package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListPredicate;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupPresenceCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The legal starred forms of the group-count quantifiers are collection scans. Typed read-back must
 * preserve that star instead of canonicalizing the single operand to scalar group presence.
 */
class WildcardGroupFillQuantifierReadTest {

    private static final DslRenderer RENDERER = new DslRenderer();

    @Test
    void preservesStarredNoGroupFilledAndAtLeastOneGroupFilled() {
        BoundModel m = calibrationModel();
        // tag::group-count-quantifiers[]
        GroupListRef samples = m.flattenGroup("/Calibration/Samples*");
        Condition noRows = Dsl.noGroupFilled(samples);
        Condition someRows = Dsl.atLeastOneGroupFilled(samples);
        // end::group-count-quantifiers[]

        assertWildcardOperand(m, "NoSamples", noRows, samples);
        assertWildcardOperand(m, "SomeSamples", someRows, samples);
    }

    @Test
    void aPlainSingleNoGroupFilledStillCanonicalizesToScalarPresence() {
        BoundModel model = calibrationModel();
        Condition authored = model.field("/Calibration/Control").filled()
                .and(model.group("/Calibration/Details").notFilled());
        Rule rule = rule(model, "NoDetails", authored);

        Condition converted = converted(KernelAdapter.of(model.add(rule)).read("/Calibration/NoDetails"));
        GroupPresenceCondition presence =
                assertInstanceOf(GroupPresenceCondition.class, assertInstanceOf(LogicCondition.class, converted).right());

        assertFalse(presence.filled());
        assertEquals(RENDERER.render(authored), RENDERER.render(converted));
    }

    private static void assertWildcardOperand(
            BoundModel model, String name, Condition groupFill, GroupListRef expectedOperand) {
        Condition authored = model.field("/Calibration/Control").filled().and(groupFill);
        Rule rule = rule(model, name, authored);

        Condition converted = converted(KernelAdapter.of(model.add(rule)).read("/Calibration/" + name));
        GroupListPredicate readGroupFill =
                assertInstanceOf(GroupListPredicate.class, assertInstanceOf(LogicCondition.class, converted).right());

        assertEquals(RENDERER.render(authored), RENDERER.render(converted));
        assertEquals(1, readGroupFill.operands().size());
        assertEquals(expectedOperand, readGroupFill.operands().getFirst());
        assertEquals(expectedOperand.group(), readGroupFill.groups().getFirst());
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
                        .group("Details", details ->
                                details.numberField("Value", ignored -> { }))
                        .group("Samples", samples -> samples.repeatable(100)
                                .numberField("Reading", ignored -> { })))
                .build().toJson());
    }
}
