package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The {@code $} outer-iteration correlation, <b>authoring direction</b> (D13): a rule built through
 * the typed DSL with an outer-marked reference inside a {@code Having} filter renders to {@code [$path]}
 * and is accepted by the real kernel. (The <em>ingest</em> direction — reading a foreign {@code $} rule
 * back into the AST — stays {@link Ingest.Opaque} for now, guarded by {@code OuterCorrelationIngestTest};
 * faithful conversion there is the N8-sensitive follow-on increment.)
 */
class OuterCorrelationAuthoringTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    @Test
    void authoredOuterCorrelationRendersTheDollarAndIsKernelValid() {
        BoundModel m = BoundModel.ofJson(read("models/order.dm.json"));

        // "the count of items sharing the current row's overall Quantity sums above 5" — the inner
        // [Items/Count] is correlated to the outer [$/Order/Quantity] inside the Having filter.
        Condition c = m.field("/Order/CustomerName").filled().and(
                Dsl.sum(m.flatten("/Order/Items*/Count")
                        .having(m.num("/Order/Quantity").outer().eq(m.num("/Order/Items/Count")))).gt(5));

        String rendered = RENDER.render(c);
        assertTrue(rendered.contains("[$/Order/Quantity]"),
                () -> "the $ outer-correlation marker must render: " + rendered);

        ValidationResult r = new RuleValidator().check(read("models/order.dm.json"),
                new DraftRule("/Order", "OuterAuthored", "../CustomerName", rendered, "C", "ERROR"));
        assertTrue(r.ok(), () -> "the kernel must accept an authored $ correlation, got: " + r.diagnostics());
    }

    private static String read(String path) {
        try (InputStream in = OuterCorrelationAuthoringTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
