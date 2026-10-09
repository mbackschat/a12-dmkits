package io.github.mbackschat.a12.dm.adapter.profile;

import io.github.mbackschat.a12.dm.adapter.TestResources;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.profile.ConditionComplexity.Construct;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * {@link ConditionComplexity} — the shape/cost walk that powers {@code model profile}. The third sibling
 * of {@link OperatorUsage}/{@link ConditionReferences}: node count, nesting depth, boolean fan-out, an
 * operator histogram (with multiplicity), and the performance-sensitive {@link Construct} tags.
 */
class ConditionComplexityTest {

    @Test
    void anIndexedCellIsCountedInsideEveryFieldOnlyCarrier() {
        BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("shop").locale("en_US")
                .rootGroup("Order", order -> order.field("Title", FieldKind.STRING)
                        .group("Items", items -> items.repeatable(5).indexField("Sku")
                                .field("Sku", FieldKind.STRING).field("Label", FieldKind.STRING)))
                .build().toJson());
        IndexedFieldValue cell = model.field("/Order/Items/Label").semanticIndex("S-1").index();
        for (Condition condition : java.util.List.of(
                Dsl.matches(cell, "[A-Z]+"),
                new ValueListCondition(cell, true, java.util.List.of(new StringLiteral("A"))),
                Dsl.length(cell).gt(1),
                Dsl.atLeastOneFieldValueIncludedInValueList(java.util.List.of("A"), cell,
                        model.field("/Order/Title")))) {
            ConditionComplexity complexity = ConditionComplexity.of(condition);
            assertTrue(complexity.constructs().contains(Construct.SEMANTIC_INDEX));
            assertEquals(1, complexity.operatorHistogram().get("SemanticIndex"));
        }
    }

    /** A keyed `ValueAsDate` source is a semantic index both walks see, as the keyed coercion's is (KF531). */
    @Test
    void aKeyedValueAsDateSourceIsCountedAsASemanticIndex() {
        BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("claims").locale("en_US")
                .rootGroup("Register", register -> register.dateField("Limit", d -> d.format("yyyy-MM-dd"))
                        .group("Claims", claims -> claims.repeatable(5).indexField("Code")
                                .field("Code", FieldKind.STRING)
                                .dateField("ReportedAbout", d -> d.format("yyyy-MM-dd")
                                        .datePrecision(io.github.mbackschat.a12.dm.modelkit.DatePrecision.MONTH_OPTIONAL))))
                .build().toJson());
        Condition keyed = model.valueAsDateLastDay(model.date("/Register/Claims/ReportedAbout").semanticIndex("C1"))
                .after(model.date("/Register/Limit"));
        ConditionComplexity complexity = ConditionComplexity.of(keyed);
        assertTrue(complexity.constructs().contains(Construct.SEMANTIC_INDEX));
        assertEquals(1, complexity.operatorHistogram().get("SemanticIndex"));
        assertTrue(OperatorUsage.of(keyed).contains("SemanticIndex"));
    }

    private static final BoundModel m = BoundModel.ofJson(TestResources.text("models/subscription.dm.json"));
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
}
