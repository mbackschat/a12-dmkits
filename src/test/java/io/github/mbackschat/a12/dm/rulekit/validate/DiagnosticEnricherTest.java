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
        return raw(code, code + " (raw kernel message)");
    }

    private static Diagnostic raw(String code, String summary) {
        return Diagnostic.precheck(Optional.of(code), summary,
                new Diagnostic.Where((String) null, Optional.empty()), Optional.empty(), Optional.empty());
    }

    @Test
    void aNamedSecondaryOperatorRejectionGetsItsExecutableCorrective() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_WRONG_DATE_FORMAT_FOR_OP",
                "At 'HoursFromTime': Wrong date format at 'Today'."));

        assertTrue(enriched.fix().orElse("").contains("time or date-time"),
                () -> "the named HoursFromTime gate needs its own corrective: " + enriched.fix());
        assertTrue(enriched.explain().orElse("").contains("hour-of-day"),
                () -> "the named operator should own the explanation: " + enriched.explain());
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
        assertTrue(fix.contains("`And`/`and`/`AND`"),
                () -> "KF76's admitted connective variants must survive the corrective: " + fix);
        assertFalse(fix.contains("PascalCase"));
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
    void aRepetitionRuleNestedInsideTheRowsGetsTheParentScopeFix() {
        // RepetitionNotUnique's one invalid example documents its duplicate-node restriction, so this distinct
        // structural rejection has no catalog fix. The code-level hint must explain the parent-group scope.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_REPEATABLE_GROUP_MISSING"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("PARENT") && fix.contains("--group"),
                () -> "the hint moves the rule to the repeated group's parent, got: " + fix);
    }

    /**
     * The same code is drawn by a key whose group is not repeatable AT ALL, where moving the rule group cannot
     * help (KF186 — three shapes collapse into this class). A hint that names only the nesting cause sends that
     * author to a repair measured not to work.
     */
    @Test
    void theCollapsedRepeatableGroupCodeNamesTheNonRepeatableKeyCauseToo() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_REPEATABLE_GROUP_MISSING"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("not repeatable"),
                () -> "the hint must name the second cause — a key in a non-repeatable group — got: " + fix);
    }

    /**
     * The kernel's own text says "There must be more than one group", which is the WRONG repair when the sole
     * operand is a repeatable group inside an iterating rule: adding a second group then draws
     * MVK_NEG_CONDITION_IN_ITERATION (KF185). The corrective must lead with the star.
     */
    @Test
    void theGroupCountArityCodeLeadsWithTheStarNotWithAddingASecondGroup() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_PARAMSIZE_INVALIDGN"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("*"),
                () -> "the hint must offer the starred single-group form, got: " + fix);
        assertTrue(fix.contains("MVK_NEG_CONDITION_IN_ITERATION"),
                () -> "the corrective must warn that adding a second group can draw the iteration class, got: " + fix);
    }

    @Test
    void repetitionKeysOnParallelPathsGetTheSinglePathFix() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_NOT_IN_SAME_PATH"));
        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("one repetition path") && fix.contains("separate rules"),
                () -> "the hint keeps keys on one repetition path, got: " + fix);
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

    /**
     * KF182 — the PROJECTED half of the calculated-field-reference ban. No operator documents this code, so the
     * catalog-join can reach nothing and the kernel's own message ("must not specify a semantic index or a
     * category access") names the defect without naming a repair. The two facts an author needs are that
     * projecting a <em>different</em> field is accepted, and that removing only the projection leaves the plain
     * self-reference behind — otherwise the obvious edit trades one rejection for another.
     */
    @Test
    void theProjectedCalculatedFieldReadGetsItsOwnCorrective() {
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD"));

        String fix = enriched.fix().orElse("");
        assertTrue(fix.contains("PROJECTION") && fix.contains("precondition"),
                () -> "names what to drop and both slots, got: " + fix);
        assertTrue(fix.contains("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"),
                () -> "warns that dropping only the projection leaves the plain self-reference, got: " + fix);
        assertTrue(fix.contains("DIFFERENT field"),
                () -> "names the accepted alternative — projecting another field, got: " + fix);
        assertTrue(enriched.explain().orElse("").contains("pre-empts"),
                () -> "the explain carries the measured pre-emption, got: " + enriched.explain());
    }

    /** KF181 — the scale gate's corrective must warn that a self-reference can surface behind it. */
    @Test
    void theDecimalScaleHintWarnsThatASelfReferenceCanSurfaceBehindIt() {
        String fix = ENRICHER.enrich(raw("MVK_INVALID_COMPARE_DEC_PLACES")).fix().orElse("");
        assertTrue(fix.contains("SECOND rejection") && fix.contains("its own computed field"),
                () -> "names the two-repair sequence, got: " + fix);
    }

    /**
     * KF269 — the POWER is a third context for this code, and the one whose scale an author cannot guess.
     *
     * <p>The two contexts above both hand the author a scale they can see: a computed field's declaration, or two
     * operands they wrote. A power's is DERIVED and is 0 only from a scale-0 base under a nonnegative literal
     * exponent — never the base's own scale — so {@code [UnitPrice] ^ 2} (scale 2) reports its side as "?" and
     * nothing in the message says why. The catalog's {@code Arithmetic} corrective carries that rule, but this
     * code is cross-context, so {@code StructuralKernelFix} wins at the enricher and the catalog text never
     * reaches a rejection. Measured 2026-09-07: without this third context the author is told to round a side
     * whose scale they had no reason to think was unknown.
     */
    @Test
    void theDecimalScaleHintNamesThePowersDerivedScaleAsItsThirdContext() {
        String fix = ENRICHER.enrich(raw("MVK_INVALID_COMPARE_DEC_PLACES")).fix().orElse("");
        assertTrue(fix.contains("power"),
                () -> "names the power context at all, got: " + fix);
        assertTrue(fix.contains("scale-0 base") && fix.contains("literal exponent"),
                () -> "names the ONE shape that derives a known scale, got: " + fix);
        assertTrue(fix.contains("ORDERING") || fix.contains("< <= > >="),
                () -> "names the exempt comparison family, which is the cheapest repair, got: " + fix);
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
    void aSoleCatalogDocumenterIsNotEvidenceOfOwnership() {
        // The catalog is a PARTIAL map of code → operators, so "exactly one entry documents this code" is equally
        // consistent with an UNDOCUMENTED SIBLING having raised it. A sole-documenter shortcut is therefore not a
        // shortcut but a guess.
        //
        // The specimen moved on 2026-09-07, and why is the point. This test was written on MVK_NOT_SORTABLE,
        // documented for MaxValue and not MinValue, so a real `MinValue(...)` rejection was explained as "The
        // largest of the listed values". Closing G77 gave MinValue its own corrective for that code, which fixes
        // that rejection at the SOURCE — and thereby retires MVK_NOT_SORTABLE as a specimen, since the union join
        // now hedges it as multi-construct instead. MVK_STRING_ENUM_AND_NON_STRING_ENUM is documented only by
        // NumberOfDifferentValues, before and after that change, so the invariant is still measured on a code
        // where it is live rather than on one the fix made vacuous.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_STRING_ENUM_AND_NON_STRING_ENUM"));

        assertTrue(enriched.explain().isEmpty(),
                () -> "a lone documenter must not be treated as the established owner, got: " + enriched.explain());
        assertTrue(enriched.fix().orElse("").contains("if that is what you used"),
                () -> "the corrective must be hedged, since it names an operator nobody established: " + enriched.fix());
    }

    /** The invariant: an operator-specific fix/explain only after ONE owner is established. */
    @Test
    void anUnestablishedOwnerYieldsNoExplainAtAll() {
        // The union fixed the `fix` but kept a firstMeaning() `explain`, so an ambiguous join still asserted an
        // owner. Reproduced by review with LessThan/LessOrEqual: a LessThan rejection was explained as
        // "less than or equal". Here the summary names no operator, so ownership is unestablished.
        //
        // This comment used to justify the operator-free summary by claiming the kernel's default bundle renders
        // MVK_NO_CONST as "Kein Fehler definiert!". That is true only of the base STUB properties file; the
        // populated German bundle does name the operator (see DiagnosticEnricher's javadoc). The case below is
        // still worth locking on its own terms — plenty of real templates substitute a path or a digit count
        // rather than an operator — but it is a constructed shape, not that bundle's behaviour.
        Diagnostic enriched = ENRICHER.enrich(raw("MVK_NO_CONST"));

        assertTrue(enriched.explain().isEmpty(),
                () -> "no owner established → must not assert one via explain, got: " + enriched.explain());
        assertTrue(enriched.fix().orElse("").contains("more than one construct"),
                () -> "should still offer the enumerated code-level corrective: " + enriched.fix());
    }

    @Test
    void structuredOperatorEvidenceEstablishesOwnershipWithoutReadingTheMessage() {
        // where.operator is the AST operator id — language-independent, unlike the message. The summary here is
        // deliberately operator-free, so only the structured evidence can resolve it.
        Diagnostic rejected = Diagnostic.precheck(Optional.of("MVK_NO_CONST"),
                "Kein Fehler definiert!",
                new Diagnostic.Where(null, Optional.of("RoundUp"), Optional.empty()),
                Optional.empty(), Optional.empty());

        Diagnostic enriched = ENRICHER.enrich(rejected);

        assertTrue(enriched.fix().orElse("").contains("RoundUp("),
                () -> "must carry RoundUp's own corrective: " + enriched.fix());
        assertTrue(enriched.explain().orElse("").contains("plus infinity"),
                () -> "explain must be RoundUp's meaning: " + enriched.explain());
    }

    @Test
    void anOperatorScopedMessageNarrowsTheUnionToTheOperatorTheKernelNamed() {
        // MVK_NO_CONST is documented by SEVEN operators with differing correctives (the four operand-list
        // numeric wrappers + the three time-component extractors — KERNEL-FINDINGS kf167), so the sound union
        // would concatenate all seven AND answer with an arbitrary documenter's meaning. But the kernel names
        // the construct in the summary, so the join narrows to it. The summary below is the REAL kernel text,
        // missing separator included.
        Diagnostic rejected = Diagnostic.precheck(Optional.of("MVK_NO_CONST"),
                "Rule with path '/Order/CheckRule': At 'Abs'It is not allowed to use constants. [MVK_NO_CONST]",
                new Diagnostic.Where((String) null, Optional.empty()), Optional.empty(), Optional.empty());

        Diagnostic enriched = ENRICHER.enrich(rejected);

        String fix = enriched.fix().orElse("");
        assertFalse(fix.contains("more than one construct"),
                () -> "an operator-scoped message must narrow, not fall back to the union: " + fix);
        assertTrue(fix.contains("Abs([UnitPrice] - 1)"),
                () -> "must carry Abs's own corrective: " + fix);
        assertTrue(enriched.explain().orElse("").contains("absolute value"),
                () -> "explain must be Abs's meaning, not another documenter's: " + enriched.explain());
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
            java.util.Set<String> codes = new java.util.HashSet<>();
            if (e.invalidExample() != null && e.invalidExample().error() != null) {
                codes.add(e.invalidExample().error());
            }
            e.correctives().stream().map(io.github.mbackschat.a12.dm.rulekit.catalog.Corrective::code)
                    .forEach(codes::add);
            for (String code : codes) {
                fixesByCode.computeIfAbsent(code, k -> new HashSet<>())
                        .addAll(catalog.rejectionFixes(e.id(), code));
            }
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
