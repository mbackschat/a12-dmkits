package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The operator catalog (proposal §9) is the language-neutral spine: a bundled JSON resource the
 * builder, renderer, docs, and LLM context all read from. These are structural self-checks — the
 * grammar-parity coverage test (catalog ↔ lexer terminals) and the CI example validation (N7) are
 * separate, later anchors.
 */
class OperatorCatalogTest {

    private static final OperatorCatalog CATALOG = OperatorCatalog.load();

    @Test
    void loadsSeededEntries() {
        assertFalse(CATALOG.entries().isEmpty(), "catalog should load at least the seed entries");
    }

    @Test
    void discoveryQueriesExploreTheCatalog() {
        // by keyword — either language resolves to the one operator
        assertEquals("FieldFilled", CATALOG.byKeyword("FieldFilled").orElseThrow().id());
        assertEquals("FieldFilled", CATALOG.byKeyword("FeldAngegeben").orElseThrow().id(), "the DE keyword resolves too");
        assertTrue(CATALOG.byKeyword("NotAKeyword").isEmpty());

        // by kind — a family of operators
        assertTrue(CATALOG.byKind(OperatorKind.PREDICATE).stream().anyMatch(e -> e.id().equals("FieldFilled")));
        assertTrue(CATALOG.byKind(OperatorKind.PREDICATE).stream().allMatch(e -> e.kind() == OperatorKind.PREDICATE));

        // by return type
        assertTrue(CATALOG.byReturns("boolean").stream().anyMatch(e -> e.id().equals("And")));
        assertTrue(CATALOG.byReturns("boolean").stream().allMatch(e -> e.returns().equals("boolean")));
    }

    @Test
    void operatorIdsAreUnique() {
        long distinct = CATALOG.entries().stream().map(OperatorEntry::id).distinct().count();
        assertEquals(CATALOG.size(), distinct, "operator ids must be unique");
    }

    @Test
    void everyEntryIsWellFormed() {
        for (OperatorEntry e : CATALOG.entries()) {
            assertFalse(isBlank(e.id()), "entry missing id");
            assertNotNull(e.kind(), () -> e.id() + ": missing kind");
            assertNotNull(e.keywords(), () -> e.id() + ": missing keywords");
            assertFalse(isBlank(e.keywords().en()), () -> e.id() + ": missing en keyword");
            assertFalse(isBlank(e.keywords().de()), () -> e.id() + ": missing de keyword");
            assertFalse(isBlank(e.returns()), () -> e.id() + ": missing returns");
            assertFalse(isBlank(e.meaning()), () -> e.id() + ": missing meaning");
        }
    }

    /**
     * The stub-entries policy (proposal §9): a {@code SUPPORTED} operator must carry a runnable example so
     * it can't silently escape the kernel-validation harness — a condition {@code validExample} (N7) or,
     * for a <b>computation-only</b> operator with no condition form (the {@code DateRange} constructor), a
     * {@code computationExample} (its N7 peer). Only {@code PLANNED} stubs are exempt.
     */
    @Test
    void everySupportedEntryHasARunnableExample() {
        for (OperatorEntry e : CATALOG.entries()) {
            if (!e.supported()) {
                continue;
            }
            boolean hasCondition = e.validExample() != null && !isBlank(e.validExample().dsl());
            boolean hasComputation = e.computationExample() != null
                    && !isBlank(e.computationExample().operation()) && !isBlank(e.computationExample().computedField());
            assertTrue(hasCondition || hasComputation,
                    () -> e.id() + ": SUPPORTED entry needs a validExample (condition) or a computationExample");
        }
    }

    /**
     * A PLANNED stub (if any) is described but example-free. The {@code DateRange} <em>constructor</em> is
     * the one built-but-condition-inexpressible operator: SUPPORTED via a {@code computationExample}, with
     * no {@code validExample} (a constructed date range has no condition use site).
     */
    @Test
    void plannedStubsCarryNoExampleAndDateRangeIsComputationOnly() {
        for (OperatorEntry e : CATALOG.entries()) {
            if (!e.supported()) {
                assertNull(e.validExample(), () -> e.id() + ": a PLANNED stub carries no validExample");
                assertNull(e.computationExample(), () -> e.id() + ": a PLANNED stub carries no computationExample");
            }
        }
        OperatorEntry dateRange = CATALOG.byId("DateRange").orElseThrow();
        assertTrue(dateRange.supported(), "the DateRange constructor is built (2026-06-08)");
        assertEquals("DateRange", dateRange.keywords().en());
        assertEquals("DatumBereich", dateRange.keywords().de());
        assertNull(dateRange.validExample(), "DateRange has no condition form — no validExample");
        assertNotNull(dateRange.computationExample(), "DateRange is verified via a computationExample");
    }

    @Test
    void exposesVerifiedBilingualKeywords() {
        OperatorEntry and = CATALOG.byId("And").orElseThrow();
        assertEquals("And", and.keywords().en());
        assertEquals("Und", and.keywords().de());

        OperatorEntry length = CATALOG.byId("Length").orElseThrow();
        assertEquals("Laenge", length.keywords().de());

        OperatorEntry fieldFilled = CATALOG.byId("FieldFilled").orElseThrow();
        assertEquals(OperatorKind.PREDICATE, fieldFilled.kind());
        assertEquals("FeldAngegeben", fieldFilled.keywords().de());
    }

    /**
     * The {@code opposite} pairings (FEATURE-GAPS G3) serve the kernel's "no generic negation" model — each
     * predicate names the partner(s) to express its inverse. They must be (a) <b>resolvable</b> (every named
     * id is a real catalog entry — a dangling pointer would mislead an agent) and (b) <b>symmetric</b> (if A
     * names B as an opposite, B names A) so "express the inverse" is one reliable hop in either direction.
     */
    @Test
    void oppositePairingsAreResolvableAndSymmetric() {
        for (OperatorEntry e : CATALOG.entries()) {
            for (String oppId : e.opposite()) {
                OperatorEntry partner = CATALOG.byId(oppId).orElseThrow(() ->
                        new AssertionError(e.id() + ": opposite '" + oppId + "' is not a catalog operator"));
                assertTrue(partner.opposite().contains(e.id()),
                        () -> "opposite must be symmetric: " + e.id() + " names " + oppId
                                + " but " + oppId + " does not name " + e.id());
            }
        }
    }

    /** Spot-check the central negation pairs the BA-doc leans on, so the data is actually authored, not empty. */
    @Test
    void exposesTheCentralNegationPairs() {
        assertOpposite("FieldFilled", "FieldNotFilled");
        assertOpposite("AllFieldsFilled", "NotAllFieldsFilled");
        assertOpposite("NoFieldFilled", "AtLeastOneFieldFilled");
        assertOpposite("PatternMatched", "PatternViolated");
        assertOpposite("Equal", "NotEqual");
        assertOpposite("LessThan", "GreaterThanOrEqual");
        assertOpposite("GreaterThan", "LessThanOrEqual");
    }

    private static void assertOpposite(String a, String b) {
        assertTrue(CATALOG.byId(a).orElseThrow().opposite().contains(b),
                () -> a + " should name " + b + " as its opposite");
        assertTrue(CATALOG.byId(b).orElseThrow().opposite().contains(a),
                () -> b + " should name " + a + " as its opposite");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
