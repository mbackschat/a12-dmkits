package io.github.mbackschat.a12.dm.adapter;

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
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 ingest tail — the {@code NumberOfFilledFields} aggregate over a single flattened repetition list
 * (kernel {@code IEntityListOperation}, e.g. counting filled reps of an {@code IndexFields} index field).
 * The count node already permits a lone repetition list; this adds the {@code Dsl} single-scope factory
 * and wires the converter. (The {@code NumberOfFilledGroups} single-wildcard-group form is deferred: it
 * needs the {@code CountAggregate} two-or-more-groups invariant corrected plus kernel re-verification.)
 */
class FilledCountIngestTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel order() {
        return BoundModel.ofJson(read("models/order.dm.json"));
    }

    @Test
    void roundTripsNumberOfFilledFieldsOverARepetitionList() {
        BoundModel m = order();
        // [CustomerName] filled And NumberOfFilledFields(/Order/Items*/Count) > 0
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.numberOfFilledFields(m.flatten("/Order/Items*/Count")).gt(0));

        Rule r = m.rule(m.group("/Order")).name("FilledCountRule").errorField(m.field("/Order/CustomerName"))
                .when(c).code("C").severity(Severity.ERROR).message(en("x")).build();
        KernelAdapter adapter = KernelAdapter.of(m.add(r));

        assertTrue(RENDER.render(c).contains("NumberOfFilledFields(/Order/Items*/Count)"), RENDER.render(c));
        assertEquals(RENDER.render(c), RENDER.render(converted(adapter.ingest("/Order/FilledCountRule"))));
    }

    @Test
    void numberOfFilledFieldsIsKernelValid() {
        BoundModel m = order();
        // the kernel requires the error field to appear in the condition (MVK_ERROR_FIELD_NOT_REFERENCED)
        Condition c = m.field("/Order/CustomerName").filled()
                .and(Dsl.numberOfFilledFields(m.flatten("/Order/Items*/Count")).gt(0));
        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "FilledCountRule", "../CustomerName", RENDER.render(c), "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }

    private static String read(String path) {
        try (InputStream in = FilledCountIngestTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
