package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;

/**
 * N3 <b>reverse</b> parity anchor and the kernel-evolution early-warning (the "foresee new operators"
 * piece). The kernel's parsetree-api carries construct-name enums — its own machine-readable operator
 * inventory. This test asserts every member of every such enum is either <b>covered</b> by the
 * rulekit catalog (its {@code name()} or {@code getRuntimeName()} matches a catalog id / EN / DE
 * keyword) or <b>explicitly excluded</b> with a reason. So a member the kernel <i>adds</i> in a future
 * release lands as a violation — "catalog it or exclude it" — instead of silently going unmodeled.
 *
 * <p>It is self-cleaning, which is what makes it a true alignment guard: a stale exclusion (a member
 * that became covered, or that the kernel removed/renamed) also fails. Adapter-tier because the
 * enums are {@code a12internal} (proposal D15/N8); complements the forward {@code KeywordParityTest}
 * (catalog → lexer terminals) in core.
 *
 * <p>The exclusions double as a coverage-backlog cross-check: most are operator families rulekit
 * hasn't modeled yet (date extraction, tolerance comparisons, op-list math, …), a few are legacy
 * German-only aliases or out-of-scope multi-form (Vordruck/USB) constructs.
 */
class KernelConstructParityTest {

    private static final String PKG = "com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.";

    /** Every construct-name enum in the parsetree-api {@code types} package (kernel 30.8.1). */
    private static final List<String> ENUMS = List.of(
            "BoolConstantOperationName", "CalcOp", "ComparisonOperation",
            "DateAddOperationName", "DateDiffOperationName", "DateExtractionOperationName",
            "DateRangeExtractionOperationName", "EntityListAndValueListConditionName",
            "EntityListConditionName", "EntityListOperationName", "FieldIdOperationName",
            "GroupOperationName", "LogOp", "OpListOperationName", "PointInTimeOperationName",
            "RootGroupConditionName", "ValidityConditionName", "ValueListConditionName");

    /**
     * Kernel construct {@code Enum.MEMBER} → why it is NOT a rulekit catalog construct. Each must be a
     * real, currently-uncovered member (the test fails on stale entries). Categories: symbolic operator
     * (rendered as a glyph, modeled differently), not-yet-modeled (coverage backlog), legacy German-only
     * alias, or out-of-scope multi-form construct.
     */
    private static final Map<String, String> EXCLUDED = excluded();

    @Test
    void everyKernelConstructIsCatalogedOrExplicitlyExcluded() throws Exception {
        Set<String> known = catalogKnownNames();
        List<String> newOrUnhandled = new ArrayList<>();   // uncovered AND not excluded → kernel evolution / gap
        List<String> staleExclusions = new ArrayList<>();  // excluded but now covered / no longer a member

        Set<String> seenKeys = new TreeSet<>();
        for (String simple : ENUMS) {
            Class<?> enumClass = Class.forName(PKG + simple);
            Method runtimeName = methodOrNull(enumClass, "getRuntimeName");
            for (Object constant : enumClass.getEnumConstants()) {
                String member = ((Enum<?>) constant).name();
                String key = simple + "." + member;
                seenKeys.add(key);

                String rn = runtimeName == null ? null : String.valueOf(runtimeName.invoke(constant));
                boolean covered = isCovered(member, rn, known);
                boolean excluded = EXCLUDED.containsKey(key);

                if (covered && excluded) {
                    staleExclusions.add(key + " — now covered by the catalog; drop the exclusion");
                } else if (!covered && !excluded) {
                    newOrUnhandled.add(key);
                }
            }
        }
        for (String key : EXCLUDED.keySet()) {
            if (!seenKeys.contains(key)) {
                staleExclusions.add(key + " — no longer a kernel enum member; drop the exclusion");
            }
        }

        assertTrue(newOrUnhandled.isEmpty(), () -> "kernel construct(s) neither cataloged nor excluded "
                + "(a new/renamed operator? catalog it, or add to EXCLUDED with a reason): " + newOrUnhandled);
        assertTrue(staleExclusions.isEmpty(), () -> "stale EXCLUDED entries (the exclusion list must "
                + "track the live kernel): " + staleExclusions);
    }

    // --- dry-run negative-controls: rehearse a kernel change and prove this guardian fires (task #4) ---

    @Test
    void rehearsal_aKernelAddingANewConstructIsFlagged() {
        Set<String> known = catalogKnownNames();
        // A hypothetical operator the kernel might add in a future release: matches no catalog name,
        // and is not in EXCLUDED → the guardian above classes it as `newOrUnhandled` and fails.
        String fabricated = "SomeBrandNewKernelOperatorV99";
        assertTrue(!isCovered(fabricated, "EinNeuerKernelOperatorV99", known)
                        && !EXCLUDED.containsKey("EntityListConditionName." + fabricated),
                "a fabricated new construct must be neither covered nor excluded — so a real kernel "
                        + "addition would fail everyKernelConstructIsCatalogedOrExplicitlyExcluded");
        // …and a real construct is still recognized, so the check isn't vacuously rejecting everything.
        assertTrue(isCovered("AllFieldsFilled", "AlleFelderAngegeben", known),
                "a cataloged construct must read as covered");
    }

    @Test
    void rehearsal_theCatalogMatchSetIsNonEmpty() {
        // Anti-rot: if the catalog failed to load, `known` would be empty and every construct would
        // read as a (false) violation. A populated set proves the covered-check can actually match.
        assertTrue(catalogKnownNames().size() > 50, "the catalog match set should be populated");
    }

    /** Whether a kernel construct's {@code name()} or {@code getRuntimeName()} matches a catalog name. */
    private static boolean isCovered(String member, String runtimeName, Set<String> known) {
        return known.contains(member) || (runtimeName != null && known.contains(runtimeName));
    }

    /** Catalog ids + EN + DE keywords — the names a kernel construct can match to count as covered. */
    private static Set<String> catalogKnownNames() {
        Set<String> names = new TreeSet<>();
        for (OperatorEntry e : OperatorCatalog.load().entries()) {
            names.add(e.id());
            names.add(e.keywords().en());
            names.add(e.keywords().de());
        }
        return names;
    }

    private static Method methodOrNull(Class<?> c, String name) {
        try {
            return c.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Map<String, String> excluded() {
        Map<String, String> m = new LinkedHashMap<>();
        // Symbolic operators — rendered as a glyph, modeled by a differently-named catalog entry (or not at all).
        put(m, "arithmetic operator rendered as a symbol (+ - * / ^), not a named catalog construct",
                "CalcOp.ADD", "CalcOp.MULTIPLY", "CalcOp.SUBTRACT", "CalcOp.DIVIDE", "CalcOp.POWER");
        put(m, "comparison rendered as a symbol; modeled as catalog GreaterThan/NotEqual/LessThan/Equal/…",
                "ComparisonOperation.GT", "ComparisonOperation.NE", "ComparisonOperation.LT",
                "ComparisonOperation.EQ", "ComparisonOperation.GE", "ComparisonOperation.LE");
        put(m, "pattern comparison; modeled as catalog PatternMatched/PatternViolated",
                "ComparisonOperation.WM", "ComparisonOperation.NM");
        put(m, "tolerance comparison (DiffersWithToleranceRange…) — NOT YET MODELED (coverage backlog)",
                "ComparisonOperation.NE1", "ComparisonOperation.NE2", "ComparisonOperation.NE5", "ComparisonOperation.NE10");
        // Not-yet-modeled operator families (coverage backlog — WORKSPACE-CORPUS-ANALYSIS).
        put(m, "time-of-day date-add — not yet modeled (date family covers years/months/days)",
                "DateAddOperationName.AddHours", "DateAddOperationName.AddMinutes", "DateAddOperationName.AddSeconds");
        put(m, "sub-day date-diff — not yet modeled",
                "DateDiffOperationName.DifferenceInSeconds", "DateDiffOperationName.DifferenceInMinutes",
                "DateDiffOperationName.DifferenceInHours");
        put(m, "time-of-day + datetime extraction — not yet modeled (the date→number extractions are built)",
                "DateExtractionOperationName.HoursFromTime", "DateExtractionOperationName.MinutesFromTime",
                "DateExtractionOperationName.SecondsFromTime", "DateExtractionOperationName.DateFromDateTime",
                "DateExtractionOperationName.TimeFromDateTime");
        put(m, "date-range bound extraction — not yet modeled",
                "DateRangeExtractionOperationName.StartOfDateRange", "DateRangeExtractionOperationName.EndOfDateRange");
        put(m, "value-list date-range overlap — not yet modeled", "EntityListAndValueListConditionName.AtLeastOneDateRangeOverlaps");
        put(m, "value-consistency condition — not yet modeled", "EntityListAndValueListConditionName.ValueNotConsistent");
        put(m, "count-of-value aggregate — not yet modeled", "EntityListOperationName.NumberOfValueInFields");
        put(m, "operand-list min/max — not yet modeled (distinct from field-list MaxValue/MinValue)",
                "OpListOperationName.Max", "OpListOperationName.Min");
        put(m, "operand-list abs function — not yet modeled", "OpListOperationName.Abs");
        put(m, "field-level rounding/abs functions — not yet modeled",
                "FieldIdOperationName.RoundAccountingValue", "FieldIdOperationName.RoundUpValue",
                "FieldIdOperationName.RoundDownValue", "FieldIdOperationName.AbsValue");
        put(m, "value-coercion functions — not yet modeled (string coercions RangeAsString/FieldValueAsString are built)",
                "FieldIdOperationName.RangeAsNumber", "FieldIdOperationName.FieldValueAsNumber",
                "FieldIdOperationName.ValueAsDate");
        put(m, "base-year point-in-time — not yet modeled (Today/Now are modeled)", "PointInTimeOperationName.BaseYear");
        put(m, "bare field reference — structural in rulekit (typed handles), not a catalog construct",
                "FieldIdOperationName.FieldValue");
        // Legacy German-only aliases (superseded by an English-named construct, or never modeled).
        put(m, "legacy German-only construct (rows not ascending) — not modeled", "EntityListConditionName.ZeilenNichtAufsteigend");
        put(m, "legacy German-only construct (rows gapless) — not modeled", "EntityListConditionName.ZeilenLueckenlos");
        put(m, "legacy German alias of RepetitionNotUnique — superseded", "EntityListConditionName.ZeileNichtEindeutig");
        put(m, "legacy German-only construct (max filled row index, ~SemanticIndex) — not modeled",
                "EntityListOperationName.MaximalAngegebenerZeilenindex");
        put(m, "legacy German form-number op — out of single-document-model scope",
                "FieldIdOperationName.AktuelleVordruckNummer");
        // Out of rulekit's single-document-model scope — multi-form (Vordruck) / USB conditions.
        put(m, "root-group / multi-form (Vordruck, USB) condition — out of single-document-model scope",
                "RootGroupConditionName.MindestensEinVordruckAngegeben", "RootGroupConditionName.KeinVordruckAngegeben",
                "RootGroupConditionName.VordruckNichtFortlaufend", "RootGroupConditionName.UsbNichtFortlaufend");
        return m;
    }

    private static void put(Map<String, String> m, String reason, String... keys) {
        for (String key : keys) {
            m.put(key, reason);
        }
    }
}
