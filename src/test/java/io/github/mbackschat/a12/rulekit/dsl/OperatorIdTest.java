package io.github.mbackschat.a12.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * The D14 invariant (proposal §8/§17): every operator-applying AST node carries its catalog
 * operator-id, and every id reachable from the DSL surface resolves to a catalog entry. This is
 * what makes explain (F9) and the text→AST converter (F10) cheap projections — and it forbids
 * keyword knowledge from living anywhere but the catalog.
 */
class OperatorIdTest {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();
    private static final FieldRef NUM = new FieldRef("/Order/Quantity", ValueKind.NUMBER);
    private static final FieldRef STR = new FieldRef("/Order/CustomerName", ValueKind.STRING);

    @Test
    void conditionNodesCarryTheirCatalogOperatorId() {
        assertEquals("FieldFilled", new FieldPresenceCondition(NUM, true).operatorId());
        assertEquals("FieldNotFilled", new FieldPresenceCondition(NUM, false).operatorId());
        assertEquals("AllFieldsFilled",
                new FieldListPredicate(Quantifier.ALL_FIELDS_FILLED, List.of(NUM, STR)).operatorId());
        assertEquals("LessThanOrEqual",
                new ComparisonCondition(NUM.value(), CompareOp.LE, NumberLiteral.of(0)).operatorId());
        Condition and = new FieldPresenceCondition(NUM, true).and(new FieldPresenceCondition(STR, true));
        assertEquals("And", and.operatorId());
        Condition or = new FieldPresenceCondition(NUM, true).or(new FieldPresenceCondition(STR, true));
        assertEquals("Or", or.operatorId());
    }

    @Test
    void operatorBearingCalculationNodesCarryTheirCatalogOperatorId() {
        assertEquals("Length", new LengthOf(STR).operatorId());
    }

    @Test
    void everyDslReachableOperatorIdResolvesInTheCatalog() {
        // Conditions: presence, quantifiers, comparisons, logic. Calculations: keyword functions.
        // (FieldValue brackets, literals, and quotes are intrinsic syntax, not catalog keywords.)
        assertResolves("FieldFilled");
        assertResolves("FieldNotFilled");
        for (Quantifier q : Quantifier.values()) {
            assertResolves(q.operatorId());
        }
        for (CompareOp op : CompareOp.values()) {
            assertResolves(op.operatorId());
        }
        for (LogicCondition.Op op : LogicCondition.Op.values()) {
            assertResolves(op.operatorId());
        }
        assertResolves("Length");
    }

    @Test
    void fieldRefsDefaultToCanonicalPathStyle() {
        // Provenance hook (D14/F10): builder-made refs are CANONICAL (renderer decides the path
        // form per §18 fork 9); the ingest converter will construct refs carrying authored styles.
        assertEquals(PathStyle.CANONICAL, NUM.styleHint());
        assertEquals(PathStyle.RELATIVE,
                new FieldRef("/Order/Quantity", ValueKind.NUMBER, PathStyle.RELATIVE).styleHint());
    }

    private static void assertResolves(String operatorId) {
        assertTrue(CATALOG.byId(operatorId).isPresent(),
                "operator id '" + operatorId + "' has no catalog entry — D14 requires every "
                        + "DSL-reachable operator id to resolve in the catalog");
    }
}
