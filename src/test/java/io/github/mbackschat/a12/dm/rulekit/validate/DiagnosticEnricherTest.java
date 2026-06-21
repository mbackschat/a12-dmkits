package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;

/**
 * {@link DiagnosticEnricher} catalog-join vs structural-hint precedence. The headline is probe finding
 * F06-2: {@code MVK_NO_WILDCARD} is a <em>shared</em> structural code (CurrentRepetition, RepetitionNotUnique,
 * aggregates, a model-scope group reference all trigger it), so the enricher must attach the generic,
 * operator-independent {@link StructuralKernelFix} hint — NOT the {@code CurrentRepetition} operator's
 * "put the error field inside Items" {@code invalidExample} fix that the operator-keyed catalog-join used to
 * stamp on every occurrence.
 */
class DiagnosticEnricherTest {

    private static final DiagnosticEnricher ENRICHER = new DiagnosticEnricher(OperatorCatalog.load());

    private static Diagnostic raw(String code) {
        return Diagnostic.precheck(Optional.of(code), code + " (raw kernel message)",
                new Diagnostic.Where((String) null, Optional.empty()), Optional.empty(), Optional.empty());
    }

    @Test
    void sharedStructuralCodeGetsTheGenericFixNotAnOperatorSpecificOne() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_NO_WILDCARD"));

        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("--group") || fix.contains("flatten"),
                () -> "the generic structural fix should point at flatten/--group, got: " + fix);
        assertFalse(fix.contains("inside Items") || fix.contains("CurrentRepetition needs"),
                () -> "must NOT carry CurrentRepetition's misattributed operator-specific fix, got: " + fix);
    }

    @Test
    void anOperatorSpecificCodeStillGetsItsCatalogFix() {
        // the catalog-join still works for codes a single operator owns — RepetitionNotUnique documents
        // MVK_REPEATABLE_GROUP_MISSING in its invalidExample, so its curated fix must still attach.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_REPEATABLE_GROUP_MISSING"));
        assertTrue(enriched.fix().filter(f -> !f.isBlank()).isPresent(),
                () -> "an operator-owned code keeps its catalog fix, got: " + enriched);
    }
}
