package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.context.RuleExplanation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * F9 explain on an read (converted-foreign) rule — the read-direction payoff. A rule's text is
 * parsed by the kernel, converted to a rulekit AST (F10), and then explained (F9) with no special
 * casing: read ASTs are ordinary rulekit conditions, so the same {@code explain} mechanism that
 * serves built rules projects the foreign one's operators, references, iteration, and gloss.
 */
class ExplainReadRuleTest {

    @Test
    void explainsAForeignAggregateRuleAfterRead() {
        BoundModel base = BoundModel.ofJson(read("models/order.dm.json"));
        Rule rule = base.rule(base.group("/Order")).name("BigOrderRule")
                .errorField(base.field("/Order/CustomerName"))
                .when(base.field("/Order/CustomerName").filled()
                        .and(Dsl.sum(base.flatten("/Order/Items*/Count")).gt(100)))
                .code("BIG").severity(Severity.ERROR).message(en("Order too large.")).build();
        BoundModel model = base.add(rule);

        // read the foreign rule back to a rulekit AST, then explain it
        ReadResult read = KernelAdapter.of(model).read("/Order/BigOrderRule");
        Condition condition = assertInstanceOf(ReadResult.Converted.class, read).condition();
        RuleExplanation explanation = new ModelContext(model).explain(condition);

        // operators: the presence predicate and the aggregate are both surfaced, with catalog meaning
        assertTrue(explanation.operators().stream().anyMatch(o -> o.keyword().equals("FieldFilled")),
                () -> "expected FieldFilled, got: " + explanation.operators());
        RuleExplanation.Operator sum = explanation.operators().stream()
                .filter(o -> o.id().equals("Sum")).findFirst()
                .orElseThrow(() -> new AssertionError("expected the Sum aggregate, got: " + explanation.operators()));
        assertFalse(sum.meaning().isBlank(), "the Sum operator should carry its catalog meaning");

        // references: the aggregated field and the presence field, with model kinds
        assertTrue(explanation.references().stream().anyMatch(r -> r.path().endsWith("/Count")),
                () -> "expected the aggregated Count field, got: " + explanation.references());

        // iteration: the aggregate ranges over the repeatable Items group
        assertTrue(explanation.iterates());
        assertEquals(Optional.of("/Order/Items"), explanation.iterationGroup());

        // a one-line gloss + JSON projection for an agent
        assertFalse(explanation.gloss().isBlank());
        assertTrue(explanation.toJson().contains("\"operators\""), explanation.toJson());
    }

    private static String read(String path) {
        try (InputStream in = ExplainReadRuleTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
