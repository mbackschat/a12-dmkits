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
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read of a semantic index {@code [field For X]} on a <b>non-number</b> field (DG15). The kernel places
 * no kind restriction on the indexed field (its flagship example is an ENUM); the converter had modeled
 * only a number field, leaving string/enum/date/… Opaque. Each now converts through its own kind's
 * comparison machinery, and `forIndex` is exposed on the non-number refs.
 *
 * <p>Own domain: an insurance policy whose {@code Coverages} rows are keyed by {@code Code}, reading a
 * coverage's status (enum), tier (string), or start date (date) in the row keyed {@code "AUTO"}.
 */
class SemanticIndexNonNumberReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String policyJson() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNumber", s -> { })
                        .dateField("PolicyStart", d -> d.format("yyyy-MM-dd"))
                        .group("Coverages", c -> c
                                .repeatable(20).indexField("Code")
                                .stringField("Code", s -> { })
                                .stringField("Tier", s -> { })
                                .enumField("Status", e -> e.value("ACTIVE").value("LAPSED"))
                                .dateField("StartDate", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    @Test
    void nonNumberSemanticIndexesAreKernelValid() {
        String model = policyJson();
        var cases = new String[] {
            "FieldFilled(PolicyNumber) And [Coverages/Tier For \"AUTO\"] == \"GOLD\"",      // string
            "FieldFilled(PolicyNumber) And [Coverages/Status For \"AUTO\"] == \"ACTIVE\"",  // enum
            "FieldFilled(PolicyNumber) And [Coverages/StartDate For \"AUTO\"] < [PolicyStart]",  // date
        };
        for (String dsl : cases) {
            ValidationResult r = new RuleValidator().check(model,
                    new DraftRule("/Policy", "R", "../PolicyNumber", dsl, "C", "ERROR"));
            assertTrue(r.ok(), () -> "expected kernel acceptance for: " + dsl + " — got: " + r.diagnostics());
        }
    }

    @Test
    void roundTripsNonNumberSemanticIndexes() {
        BoundModel m = BoundModel.ofJson(policyJson());
        Condition str = m.str("/Policy/PolicyNumber").filled()
                .and(m.str("/Policy/Coverages/Tier").forIndex("AUTO").eq("GOLD"));
        Condition enm = m.str("/Policy/PolicyNumber").filled()
                .and(m.enumOf("/Policy/Coverages/Status").forIndex("AUTO").is("ACTIVE"));
        Condition dat = m.str("/Policy/PolicyNumber").filled()
                .and(m.date("/Policy/Coverages/StartDate").forIndex("AUTO").before(m.date("/Policy/PolicyStart")));

        assertTrue(RENDER.render(str).contains("[/Policy/Coverages/Tier For \"AUTO\"]"), RENDER.render(str));
        assertTrue(RENDER.render(enm).contains("[/Policy/Coverages/Status For \"AUTO\"]"), RENDER.render(enm));
        assertTrue(RENDER.render(dat).contains("[/Policy/Coverages/StartDate For \"AUTO\"]"), RENDER.render(dat));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "StrIdx", "/Policy/PolicyNumber", str))
                .add(rule(m, "EnumIdx", "/Policy/PolicyNumber", enm))
                .add(rule(m, "DateIdx", "/Policy/PolicyNumber", dat)));

        assertEquals(RENDER.render(str), RENDER.render(converted(adapter.read("/Policy/StrIdx"))));
        assertEquals(RENDER.render(enm), RENDER.render(converted(adapter.read("/Policy/EnumIdx"))));
        assertEquals(RENDER.render(dat), RENDER.render(converted(adapter.read("/Policy/DateIdx"))));
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Policy")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
