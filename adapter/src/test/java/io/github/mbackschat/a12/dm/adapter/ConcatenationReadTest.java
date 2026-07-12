package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

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
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — string concatenation {@code [Full] != [First] + " " + [Last]} on the right of a
 * consistency rule (a stored composite string vs the parts it should equal). The kernel emits {@code +}
 * as a string-typed {@code ICompositeOperation} (CalcOp ADD); the converter flattens it into a
 * {@code Concatenation} node. Own domain: a person whose stored {@code FullName} must equal first +
 * space + last.
 */
class ConcatenationReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String personJson() {
        return DocumentModelDraft.named("person").locale("en_US")
                .rootGroup("Person", p -> p
                        .stringField("FirstName", s -> { })
                        .stringField("LastName", s -> { })
                        .stringField("FullName", s -> { }))
                .build().toJson();
    }

    @Test
    void roundTripsAThreePartConcatenation() {
        BoundModel m = BoundModel.ofJson(personJson());
        StringExpr full = m.str("/Person/FirstName").asString()
                .plus(" ").plus(m.str("/Person/LastName").asString());
        Condition cond = m.str("/Person/FullName").ne(full);

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FullNameRule", cond)));
        assertEquals(RENDER.render(cond), RENDER.render(converted(adapter.read("/Person/FullNameRule"))));
    }

    @Test
    void concatenationIsKernelValid() {
        ValidationResult r = new RuleValidator().check(personJson(),
                new DraftRule("/Person", "FullNameRule", "../FullName",
                        "AllFieldsFilled(FirstName, LastName) And [/Person/FullName] != [/Person/FirstName] + \" \" + [/Person/LastName]",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, Condition cond) {
        return m.rule(m.group("/Person")).name(name).errorField(m.field("/Person/FullName")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
