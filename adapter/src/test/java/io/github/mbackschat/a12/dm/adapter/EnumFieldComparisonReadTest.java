package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — enum field-to-field comparison ({@code [A] == [B]} / {@code !=}). The kernel emits
 * these as an {@code IComparisonCondition} with a {@code FieldValue} right operand; the converter now
 * wires them to {@code EnumRef.is(EnumRef)} / {@code isNot(EnumRef)} (the enum peer of the existing
 * string field-to-field path). Own domain: an approval whose requested vs granted status share a
 * typeDef enum — authored via modelkit so the two fields are guaranteed type-compatible.
 */
class EnumFieldComparisonReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String approvalsJson() {
        return DocumentModelDraft.named("approvals").locale("en_US")
                .typeDef("Status", t -> t.enumeration(e -> e.value("DRAFT").value("FINAL")))
                .rootGroup("Approval", a -> a
                        .typedefField("Requested", "Status")
                        .typedefField("Granted", "Status"))
                .build().toJson();
    }

    @Test
    void roundTripsEnumFieldToFieldComparison() {
        BoundModel m = BoundModel.ofJson(approvalsJson());
        Condition eq = m.enumOf("/Approval/Requested").is(m.enumOf("/Approval/Granted"));
        Condition ne = m.enumOf("/Approval/Requested").isNot(m.enumOf("/Approval/Granted"));

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "EqRule", eq)).add(rule(m, "NeRule", ne)));
        assertEquals(RENDER.render(eq), RENDER.render(converted(adapter.read("/Approval/EqRule"))));
        assertEquals(RENDER.render(ne), RENDER.render(converted(adapter.read("/Approval/NeRule"))));
    }

    @Test
    void enumFieldToFieldComparisonIsKernelValid() {
        ValidationResult r = new RuleValidator().check(approvalsJson(),
                new DraftRule("/Approval", "EqRule", "../Requested",
                        "[/Approval/Requested] == [/Approval/Granted]", "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, Condition cond) {
        return m.rule(m.group("/Approval")).name(name).errorField(m.field("/Approval/Requested")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
