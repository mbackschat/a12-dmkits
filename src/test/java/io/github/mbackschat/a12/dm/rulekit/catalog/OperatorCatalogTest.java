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
     * The stub-entries policy (proposal §9): a {@code SUPPORTED} operator must carry a
     * {@code validExample} so it can't silently escape the N7 kernel-validation harness; only
     * {@code PLANNED} stubs are exempt.
     */
    @Test
    void everySupportedEntryHasAValidExample() {
        for (OperatorEntry e : CATALOG.entries()) {
            if (e.supported()) {
                assertNotNull(e.validExample(), () -> e.id() + ": SUPPORTED entry missing validExample");
                assertFalse(isBlank(e.validExample().dsl()), () -> e.id() + ": validExample has no dsl");
            }
        }
    }

    /** Stubs map the kernel operator space rulekit hasn't built — described, but example-free. */
    @Test
    void plannedStubsAreCataloguedButCarryNoExample() {
        assertTrue(CATALOG.entries().stream().anyMatch(e -> !e.supported()),
                "the catalog should map known-but-unbuilt operators as PLANNED stubs");

        OperatorEntry dateRange = CATALOG.byId("DateRange").orElseThrow();
        assertFalse(dateRange.supported(), "DateRange is not built yet — it should be a PLANNED stub");
        assertEquals("DateRange", dateRange.keywords().en());
        assertEquals("DatumBereich", dateRange.keywords().de());
        assertNull(dateRange.validExample(), "a PLANNED stub carries no validExample");
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
