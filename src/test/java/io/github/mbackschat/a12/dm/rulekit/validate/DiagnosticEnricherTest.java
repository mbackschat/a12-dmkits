package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;

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

    @Test
    void aCrossOperatorCodeGetsTheUnionNotAnArbitraryFirstOperatorsFix() {
        // ROOT CAUSE (the deeper flaw): byInvalidError did operatorsForError(code).findFirst(), so a code
        // documented by >1 operator was stamped with whichever was FIRST in operators.json. MVK_NO_FIELDS_ALLOWED
        // is documented by GroupFilled AND NumberOfFilledGroups with DIFFERENT correctives, so
        // NumberOfFilledGroups(field) got GroupFilled's "use FieldFilled" — wrong. The sound join attaches the
        // UNION, so BOTH correctives are present and neither construct is mis-stamped.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_NO_FIELDS_ALLOWED"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("FieldFilled"),
                () -> "carries GroupFilled's corrective, got: " + fix);
        assertTrue(fix.contains("NumberOfFilledFields"),
                () -> "carries NumberOfFilledGroups's corrective (the one the old findFirst dropped), got: " + fix);
    }

    @Test
    void theDecimalScaleCodeGetsTheTwoContextHintNotTheComparisonOnlyCatalogFix() {
        // probe 07 (price a policy): re-scaling a Sum operand broke the computed field with
        // MVK_INVALID_COMPARE_DEC_PLACES, but the catalog-join stamped the COMPARISON operators'
        // ("round one with RoundDown") fix — wrong for the computed-field-scale context. A CROSS-CONTEXT code:
        // StructuralKernelFix supplies the two-context corrective (match the computed field's scale; or, for
        // == / !=, use the exact derived-scale rule / allowDifferingDecimals), and it wins over the join.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_INVALID_COMPARE_DEC_PLACES"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("computed field") && fix.contains("ONE `apply`"),
                () -> "names the computed-field-scale corrective, got: " + fix);
        assertTrue(fix.contains("DERIVED") && fix.contains("SMALLER-scale")
                        && fix.contains("allowDifferingDecimals"),
                () -> "names the derived-scale/capability == / != corrective, got: " + fix);
    }

    @Test
    void rawTypeValueUseGetsThePresenceCorrective() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_INVALID_RAW_TYPE"));

        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("noValueValidation") && fix.contains("PRESENCE"),
                () -> "the raw-type fix should steer to presence checks, got: " + fix);
        assertTrue(enriched.explain().orElse("").contains("never interned"),
                () -> "the explain should carry the pseudo-value mechanism, got: " + enriched.explain());
    }

    @Test
    void rawTypeLengthShapeGetsTheEliminatedDeclarationCorrective() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_INVALID_LENGTH_OF_RAW_TYPE"));

        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("Length(f) > <constant>") && fix.contains("never fires"),
                () -> "the raw-type Length fix should name the sole allowed shape AND warn it is eliminated, got: " + fix);
    }

    @Test
    void noCrossOperatorCodeSilentlyDropsACorrective() {
        // GUARD (anti-whack-a-mole, catalog-derived so it covers siblings we never probed): for EVERY kernel
        // code documented by >1 operator whose invalidExample fixes DISAGREE, the enriched fix must carry
        // EVERY distinct corrective (the union) — unless a StructuralKernelFix code-level override replaces it.
        // This fails the build if anyone reverts the sound join to the old findFirst (which drops all but one).
        OperatorCatalog catalog = OperatorCatalog.load();
        Map<String, Set<String>> fixesByCode = new HashMap<>();
        for (OperatorEntry e : catalog.entries()) {
            OperatorEntry.InvalidExample ix = e.invalidExample();
            if (ix == null || ix.error() == null || ix.fix() == null || ix.fix().isBlank()) {
                continue;
            }
            fixesByCode.computeIfAbsent(ix.error(), k -> new HashSet<>()).add(ix.fix());
        }
        for (Map.Entry<String, Set<String>> byCode : fixesByCode.entrySet()) {
            if (byCode.getValue().size() < 2 || StructuralKernelFix.forCode(byCode.getKey()).isPresent()) {
                continue; // single corrective (findFirst was already unambiguous) or a code-level override wins
            }
            String enrichedFix = ENRICHER.enrich(raw(byCode.getKey())).fix().orElse("");
            for (String corrective : byCode.getValue()) {
                assertTrue(enrichedFix.contains(corrective),
                        () -> "shared code " + byCode.getKey() + " drops a corrective from the union (the old "
                                + "findFirst mis-stamp): " + corrective + "\n  enriched: " + enrichedFix);
            }
        }
    }
}
