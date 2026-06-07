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
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 ingest tail — {@code FirstFilledValue(…)} over a repetition list, both result types: a number scope
 * yields a number, a date scope yields a date (Corpus-1's largest residual bucket after the comparison-RHS
 * work). The converter routes the number variant through {@code NumberExpr} and the date variant through
 * {@code DateExpr}, sharing one {@code FirstFilledValue} node whose kind follows its scope. Own domain: an
 * employee whose stored "first contract" roll-ups must equal the first specified contract row.
 */
class FirstFilledValueIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String employeeJson() {
        return DocumentModelDraft.named("employee").locale("en_US")
                .rootGroup("Employee", e -> e
                        .numberField("FirstContractValue", n -> { })
                        .dateField("FirstContractDate", d -> d.format("yyyy-MM-dd"))
                        .stringField("FirstContractCode", s -> { })
                        .enumField("FirstContractType", t -> t.value("FULL").value("PART"))
                        .group("Contracts", g -> g
                                .repeatable(20)
                                .numberField("Value", n -> { })
                                .dateField("StartDate", d -> d.format("yyyy-MM-dd"))
                                .stringField("Code", s -> { })
                                .enumField("Type", t -> t.value("FULL").value("PART"))))
                .build().toJson();
    }

    @Test
    void roundTripsNumberAndDateFirstFilledValue() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition num = m.num("/Employee/FirstContractValue")
                .ne(Dsl.firstFilledNumber(m.flatten("/Employee/Contracts*/Value")));
        Condition date = m.date("/Employee/FirstContractDate")
                .ne(Dsl.firstFilledDate(m.flatten("/Employee/Contracts*/StartDate")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstValueRule", "/Employee/FirstContractValue", num))
                .add(rule(m, "FirstDateRule", "/Employee/FirstContractDate", date)));
        assertEquals(RENDER.render(num), RENDER.render(converted(adapter.ingest("/Employee/FirstValueRule"))));
        assertEquals(RENDER.render(date), RENDER.render(converted(adapter.ingest("/Employee/FirstDateRule"))));
    }

    @Test
    void roundTripsADateFirstFilledValueOnTheLeftOfAComparison() {
        // Corpus shape: FirstFilledValue(dates*) > [dateField] — the date-valued aggregate is the LEFT
        // operand (was mis-routed to the number builder and rejected: "needs a NUMBER field list").
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition full = m.date("/Employee/FirstContractDate").filled()
                .and(Dsl.firstFilledDate(m.flatten("/Employee/Contracts*/StartDate"))
                        .after(m.date("/Employee/FirstContractDate")));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FirstDateLeftRule", "/Employee/FirstContractDate", full)));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.ingest("/Employee/FirstDateLeftRule"))));
    }

    @Test
    void roundTripsStringFirstFilledValueAsComparandAndConcatPart() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        // string FirstFilledValue on the right of a string comparison
        Condition cmp = m.str("/Employee/FirstContractCode")
                .ne(Dsl.firstFilledString(m.flatten("/Employee/Contracts*/Code")));
        // string FirstFilledValue as a concatenation part
        Condition concat = m.str("/Employee/FirstContractCode")
                .ne(Dsl.firstFilledString(m.flatten("/Employee/Contracts*/Code")).plus("-X"));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "FirstCodeRule", "/Employee/FirstContractCode", cmp))
                .add(rule(m, "FirstCodeConcatRule", "/Employee/FirstContractCode", concat)));
        assertEquals(RENDER.render(cmp), RENDER.render(converted(adapter.ingest("/Employee/FirstCodeRule"))));
        assertEquals(RENDER.render(concat), RENDER.render(converted(adapter.ingest("/Employee/FirstCodeConcatRule"))));
    }

    @Test
    void roundTripsEnumFirstFilledValue() {
        // Corpus shape: [enumField] != FirstFilledValue(enums*) — the enum-valued FirstFilledValue on the right.
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition full = m.enumOf("/Employee/FirstContractType").filled()
                .and(m.enumOf("/Employee/FirstContractType")
                        .isNot(Dsl.firstFilledEnum(m.flatten("/Employee/Contracts*/Type"))));
        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FirstTypeRule", "/Employee/FirstContractType", full)));
        assertEquals(RENDER.render(full), RENDER.render(converted(adapter.ingest("/Employee/FirstTypeRule"))));
    }

    @Test
    void firstFilledValueIsKernelValid() {
        ValidationResult r = new RuleValidator().check(employeeJson(),
                new DraftRule("/Employee", "FirstValueRule", "../FirstContractValue",
                        "FieldFilled(FirstContractValue) And [/Employee/FirstContractValue] != FirstFilledValue(Contracts*/Value)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Employee")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }
}
