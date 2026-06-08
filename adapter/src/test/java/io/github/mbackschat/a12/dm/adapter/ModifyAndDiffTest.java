package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.lint.RuleDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F8 modify-by-re-express + diff over an <b>read</b> rule — the loop closing: a foreign rule is
 * parsed and converted (F10), re-expressed as an ordinary rulekit AST, diffed for review (F8 #2,
 * with lossiness disclosed), and the result re-validated against the real kernel. No adapter-specific
 * AST machinery — the modify is plain composition/`transform` on the converted condition.
 */
class ModifyAndDiffTest {

    private static final String RULE = "/Subscription/Addons/FeeCapRule";
    private static final String FEE = "/Subscription/Addons/MonthlyFee";

    @Test
    void readReExpressDiffValidate() {
        BoundModel base = BoundModel.ofJson(read("models/subscription.dm.json"));
        Rule original = base.rule(base.group("/Subscription/Addons")).name("FeeCapRule")
                .errorField(base.field(FEE)).when(base.num(FEE).gt(1000))
                .code("FEE").severity(Severity.ERROR).message(en("Too high."), de("Zu hoch.")).build();
        BoundModel model = base.add(original);
        KernelAdapter adapter = KernelAdapter.of(model);

        // read the foreign rule back to a rulekit AST
        Condition current = assertInstanceOf(ReadResult.Converted.class, adapter.read(RULE)).condition();
        // re-express: tighten the rule by composing in a presence guard (modify = compose, finding 16)
        Condition modified = current.and(base.num(FEE).filled());

        // diff for review — discloses lossiness up front
        RenderedDiff diff = adapter.diff(RULE, modified);
        assertTrue(diff.changed(), () -> diff.unified());
        assertTrue(diff.before().contains("1000") && !diff.before().contains("FieldFilled"),
                () -> "before should be the original comparison: " + diff.before());
        assertTrue(diff.after().contains("FieldFilled") && diff.after().contains("1000"),
                () -> "after should add the presence guard: " + diff.after());

        // close the loop: the re-expressed rule is still kernel-valid
        Rule modifiedRule = base.rule(base.group("/Subscription/Addons")).name("FeeCapRule2")
                .errorField(base.field(FEE)).when(modified)
                .code("FEE").severity(Severity.ERROR).message(en("Too high."), de("Zu hoch.")).build();
        ValidationResult vr = new RuleDiagnostics().diagnose(model, modifiedRule);
        assertTrue(vr.ok(), () -> "re-expressed rule should validate, got: " + vr.diagnostics());

        // write-back (F8 #3): apply the reviewed change, persisted as a new binding (F0)
        BoundModel applied = model.replaceCondition(RULE, modified);
        assertTrue(applied.toJson().contains("FieldFilled(MonthlyFee)"),
                () -> "the applied model carries the re-expressed condition: " + applied.toJson());
        // and it round-trips: re-reading the applied rule yields the modified AST
        Condition reread = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.of(applied).read(RULE)).condition();
        assertEquals(new DslRenderer(ConditionLanguage.EN).render(modified),
                new DslRenderer(ConditionLanguage.EN).render(reread));
    }

    private static String read(String path) {
        try (InputStream in = ModifyAndDiffTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
