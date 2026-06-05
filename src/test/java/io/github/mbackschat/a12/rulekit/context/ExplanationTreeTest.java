package io.github.mbackschat.a12.rulekit.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.Dsl;

/**
 * F9 explain — the structured {@code tree} projection (2026-06-05, normalized same day). The flat
 * {@code operators[]} list loses the rule's <em>shape</em> (And/Or nesting, which operator wraps
 * which); the tree preserves it by walking the sealed {@link Condition} AST. Each node is
 * {@code {operator, text, children}} — {@code operator} a <b>backlink</b> into the {@code operators}
 * glossary (so meanings live there once, not duplicated per node), {@code text} the rendered subtree.
 * Operator-bearing operands (functions/aggregates) become child nodes; value leaves (fields,
 * literals) stay in the parent's {@code text}.
 */
class ExplanationTreeTest {

    @Test
    void treeMirrorsTheLogicalStructureAndNestsFunctionOperands() {
        BoundModel m = Fixtures.subscription();
        // FieldFilled(MonthlyFee) And Sum(Addons*/MonthlyFee) > 100.00  — a guard And'd with an
        // aggregate comparison: the And nests two leaves, and the '>' leaf nests the Sum function.
        Condition guard = Dsl.fieldFilled(m.field("/Subscription/Addons/MonthlyFee"));
        Condition agg = Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee")).gt("100.00");
        Condition c = guard.and(agg);

        RuleExplanation ex = new ModelContext(m).explain(c);
        RuleExplanation.Node root = ex.tree();

        // the root is the And, with its two operands as children, in order
        assertEquals("And", root.operator());
        assertEquals(2, root.children().size(), () -> "children: " + root.children());
        assertEquals(ex.text(), root.text(), "the root node renders the whole condition");

        RuleExplanation.Node left = root.children().get(0);
        assertEquals("FieldFilled", left.operator());
        assertTrue(left.children().isEmpty(), "a field-presence leaf has no operator-bearing operands");

        RuleExplanation.Node right = root.children().get(1);
        assertEquals("GreaterThan", right.operator());
        // the literal 100.00 stays in text; the Sum aggregate becomes a child node
        assertEquals(1, right.children().size(), () -> "comparison children: " + right.children());
        assertEquals("Sum", right.children().get(0).operator());

        // backlink invariant: every operator in the tree resolves into the operators glossary
        java.util.Set<String> glossary = ex.operators().stream()
                .map(RuleExplanation.Operator::id).collect(java.util.stream.Collectors.toSet());
        allOperators(root).forEach(op -> assertTrue(glossary.contains(op),
                () -> "tree operator '" + op + "' missing from operators glossary " + glossary));
        // and the glossary actually carries the descriptions (once)
        assertTrue(ex.operators().stream().anyMatch(o -> o.id().equals("And") && !o.meaning().isBlank()),
                () -> "glossary meanings: " + ex.operators());

        // the tree round-trips into the explain JSON, with operator backlinks (not inlined meanings)
        String json = ex.toJson();
        assertTrue(json.contains("\"tree\"") && json.contains("\"operator\" : \"Sum\""), json);
    }

    private static java.util.stream.Stream<String> allOperators(RuleExplanation.Node n) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(n.operator()),
                n.children().stream().flatMap(ExplanationTreeTest::allOperators));
    }
}
