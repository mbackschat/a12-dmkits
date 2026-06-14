package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.PatternParam;

/**
 * Structural self-checks for the validation-idiom catalog (FEATURE-GAPS G2) — the peer of
 * {@link OperatorCatalogTest}. That each pattern actually scaffolds a kernel-valid rule is the separate
 * no-rot gate ({@code PatternCatalogKernelTest}); here we lock the metadata is well-formed and self-consistent.
 */
class PatternCatalogTest {

    private static final PatternCatalog CATALOG = PatternCatalog.load();

    @Test
    void loadsTheBuiltInPatterns() {
        assertFalse(CATALOG.entries().isEmpty(), "the catalog should load the built-in patterns");
        assertEquals(BuiltInPattern.values().length, CATALOG.size());
    }

    @Test
    void everyPatternIsWellFormed() {
        for (PatternEntry p : CATALOG.entries()) {
            assertFalse(isBlank(p.id()), "pattern missing id");
            assertFalse(isBlank(p.summary()), () -> p.id() + ": missing summary");
            assertFalse(isBlank(p.polarityNote()), () -> p.id() + ": missing polarity note");
            assertFalse(isBlank(p.skeleton()), () -> p.id() + ": missing skeleton");
            assertFalse(p.params().isEmpty(), () -> p.id() + ": needs at least one parameter");
            for (PatternParam param : p.params()) {
                assertFalse(isBlank(param.name()), () -> p.id() + ": a parameter is missing its name");
                assertFalse(isBlank(param.doc()), () -> p.id() + ": parameter " + param.name() + " is undocumented");
            }
        }
    }

    /** The skeleton is the agent-facing illustration — it must mention every parameter, so none is orphaned. */
    @Test
    void everyParameterAppearsInTheSkeleton() {
        for (PatternEntry p : CATALOG.entries()) {
            for (PatternParam param : p.params()) {
                assertTrue(p.skeleton().contains("${" + param.name() + "}"),
                        () -> p.id() + ": skeleton does not reference parameter ${" + param.name() + "}");
            }
        }
    }

    /** The CI no-rot example must supply every parameter, so {@code PatternCatalogKernelTest} can instantiate it. */
    @Test
    void everyExampleSuppliesEveryParameter() {
        for (PatternEntry p : CATALOG.entries()) {
            for (PatternParam param : p.params()) {
                assertTrue(p.example().containsKey(param.name()),
                        () -> p.id() + ": example is missing parameter " + param.name());
            }
        }
    }

    @Test
    void idsAreUnique() {
        long distinct = CATALOG.entries().stream().map(PatternEntry::id).distinct().count();
        assertEquals(CATALOG.size(), distinct, "pattern ids must be unique");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
