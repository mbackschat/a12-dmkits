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
 * F10 read tail — the <b>date</b> variant of {@code MaxValue}/{@code MinValue}: a date field compared
 * against the latest/earliest date over a repetition list (e.g. MaxValue of the flatten of {@code Contracts*}
 * then {@code StartDate}). The kernel reuses the {@code MaxValue}/{@code MinValue} keywords for dates; the converter routes the
 * date-typed aggregate through a {@code DateListAggregate} (wrapped as a {@code DateExpr}) instead of the
 * numeric {@code ListAggregate}. Own domain: an employee with a roll-up of contract start dates.
 */
class DateAggregateReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String employeeJson() {
        return DocumentModelDraft.named("employee").locale("en_US")
                .rootGroup("Employee", e -> e
                        .dateField("LatestStart", d -> d.format("yyyy-MM-dd"))
                        .dateField("EarliestStart", d -> d.format("yyyy-MM-dd"))
                        .group("Contracts", g -> g
                                .repeatable(20)
                                .dateField("StartDate", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    @Test
    void roundTripsLatestAndEarliestDateAggregates() {
        BoundModel m = BoundModel.ofJson(employeeJson());
        Condition latest = m.date("/Employee/LatestStart")
                .ne(Dsl.latestOf(m.flatten("/Employee/Contracts*/StartDate")));
        Condition earliest = m.date("/Employee/EarliestStart")
                .ne(Dsl.earliestOf(m.flatten("/Employee/Contracts*/StartDate")));

        KernelAdapter adapter = KernelAdapter.of(m
                .add(rule(m, "LatestRule", "/Employee/LatestStart", latest))
                .add(rule(m, "EarliestRule", "/Employee/EarliestStart", earliest)));
        assertEquals(RENDER.render(latest), RENDER.render(converted(adapter.read("/Employee/LatestRule"))));
        assertEquals(RENDER.render(earliest), RENDER.render(converted(adapter.read("/Employee/EarliestRule"))));
    }

    @Test
    void aDateFieldComparedAgainstADateAggregateIsKernelValid() {
        ValidationResult r = new RuleValidator().check(employeeJson(),
                new DraftRule("/Employee", "LatestRule", "../LatestStart",
                        "FieldFilled(LatestStart) And [/Employee/LatestStart] != MaxValue(Contracts*/StartDate)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, String errorField, Condition cond) {
        return m.rule(m.group("/Employee")).name(name).errorField(m.field(errorField)).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
