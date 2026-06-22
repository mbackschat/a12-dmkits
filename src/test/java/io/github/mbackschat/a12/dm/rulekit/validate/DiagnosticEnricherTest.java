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
    void unexpectedTokenGetsTheBooleanCasingSyntaxHint() {
        // 05/06 probe: `[X] == true` fails MVK_UNEXPECTED_TOKEN, which the kernel raises with NO message of
        // its own — so the enricher must attach the syntax hint that leads with the capitalized boolean
        // literals (True/False), the most common cause.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_UNEXPECTED_TOKEN"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("True") && fix.contains("False"),
                () -> "the syntax hint names the capitalized boolean literals True/False, got: " + fix);
        // probe 08 Finding 3: a field used as a comparison VALUE without brackets (`Approved != True`) is also
        // MVK_UNEXPECTED_TOKEN — the hint must name the bracket-the-operand cause, not just literals/keywords.
        assertTrue(fix.contains("BRACKETED") && fix.contains("[Approved]"),
                () -> "the syntax hint names the bracket-the-operand cause, got: " + fix);
    }

    @Test
    void invalidEntityGetsTheAbsolutePathHint() {
        // probe 08: a same-group/relative reference that doesn't bind fails MVK_INVALID_ENTITY ("the group or
        // field 'X' is not valid") with no hint to use an absolute path — the enricher must supply one.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_INVALID_ENTITY"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("ABSOLUTE path") || fix.contains("/Order/Items/Sku"),
                () -> "the hint points at the absolute path, got: " + fix);
    }

    @Test
    void enumLocaleCoverageGapGetsThePerLocaleLabelHint() {
        // probe 02: an enum value labeled in some declared locales but not all fails the opaque
        // MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES ("the display value and the XML value were not specified
        // together") — the enricher must name the per-locale label-coverage cause + the add-locale trigger.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("labels") && fix.contains("locale") && fix.contains("add-locale"),
                () -> "the hint names the per-locale enum-label gap + the add-locale trigger, got: " + fix);
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
