package io.github.mbackschat.a12.dm.adapter.profile;

import io.github.mbackschat.a12.dm.rulekit.dsl.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.Construct;

/**
 * {@link ConditionComplexity} — the shape/cost walk that powers {@code model profile}. The third sibling
 * of {@link OperatorUsage}/{@link ConditionReferences}: node count, nesting depth, boolean fan-out, an
 * operator histogram (with multiplicity), and the performance-sensitive {@link Construct} tags.
 */
class ConditionComplexityTest {

    private static final BoundModel m = BoundModel.ofJson(readResource("models/subscription.dm.json"));
    private static final String FEE = "/Subscription/Addons/MonthlyFee";
    private static final String BASE = "/Subscription/Billing/BaseFee";

    @Test
    void aLeafPresenceIsOneNodeDepthOne() {
        ConditionComplexity c = ConditionComplexity.of(m.num(FEE).filled());
        assertEquals(1, c.nodeCount());
        assertEquals(1, c.depth());
        assertEquals(1, c.maxBooleanArity());
        assertTrue(c.constructs().isEmpty());
    }

    @Test
    void aComparisonCountsOperandLeaves() {
        // ComparisonCondition + FieldValue + NumberLiteral = 3 nodes; depth = compare(1) + operand(1)
        ConditionComplexity c = ConditionComplexity.of(m.num(FEE).lt(1000));
        assertEquals(3, c.nodeCount());
        assertEquals(2, c.depth());
    }

    @Test
    void aConjunctionChainReportsItsFanOut() {
        Condition three = m.num(FEE).lt(1000).and(m.num(BASE).gt(0)).and(m.num(FEE).gt(0));
        ConditionComplexity c = ConditionComplexity.of(three);
        assertEquals(3, c.maxBooleanArity());
        assertEquals(4, c.depth());              // AND(AND(cmp,cmp),cmp): 1+1+2
        assertEquals(2, c.operatorHistogram().get("And")); // two And nodes
    }

    @Test
    void mixedAndOrDoesNotOvercountArity() {
        Condition mixed = m.num(FEE).lt(1000).and(m.num(BASE).gt(0)).or(m.num(FEE).gt(0));
        assertEquals(2, ConditionComplexity.of(mixed).maxBooleanArity());
    }

    @Test
    void theOperatorHistogramCountsMultiplicity() {
        Condition twoLessThans = m.num(FEE).lt(1000).and(m.num(BASE).lt(1000));
        // whatever the '<' operator id is, it appears twice
        var hist = ConditionComplexity.of(twoLessThans).operatorHistogram();
        int lessThans = hist.values().stream().mapToInt(Integer::intValue).sum() - hist.get("And");
        assertEquals(2, lessThans);
    }

    @Test
    void aValueListIsTaggedAndCountsItsValues() {
        ConditionComplexity c = ConditionComplexity.of(m.str("/Subscription/Addons/Name").in("Backup", "Support"));
        assertTrue(c.constructs().contains(Construct.VALUE_LIST));
        assertEquals(3, c.nodeCount()); // the condition + two string-literal values
    }

    @Test
    void aRepetitionUniquenessIsTagged() {
        Condition c = Dsl.repetitionNotUnique(m.field("/Subscription/Addons/Name"));
        assertTrue(ConditionComplexity.of(c).constructs().contains(Construct.UNIQUENESS));
    }

    @Test
    void anAggregateIsTagged() {
        Condition c = Dsl.sum(m.flatten("/Subscription/Addons*/MonthlyFee")).gt("100.00");
        ConditionComplexity cx = ConditionComplexity.of(c);
        assertTrue(cx.constructs().contains(Construct.AGGREGATE));
        assertFalse(cx.constructs().contains(Construct.QUANTIFIER));
        assertTrue(cx.operatorHistogram().containsKey("Sum"));
    }

    private static String readResource(String path) {
        try (var input = ConditionComplexityTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
