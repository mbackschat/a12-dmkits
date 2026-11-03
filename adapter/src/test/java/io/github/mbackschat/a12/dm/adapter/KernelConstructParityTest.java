package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
 * enums are {@code a12internal} (PROJECT-DESIGN D15/N8); complements the forward {@code KeywordParityTest}
 * (catalog → lexer terminals) in core.
 *
 * <p>The exclusions double as a coverage-backlog cross-check: most are operator families rulekit
 * hasn't modeled yet (date extraction, tolerance comparisons, op-list math, …), a few are legacy
 * German-only aliases or out-of-scope multi-form (Vordruck/USB) constructs.
 */
class KernelConstructParityTest {

    private static final String PKG = "com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.";

    /**
     * Enums of the {@code types} package that name no construct, each with its reason. Every other enum there is a
     * construct-name enum, so the set is discovered rather than listed: kernel 31.1.1 added
     * {@code SetOpOperationName} to a hand-written list this test used to carry, and the operator it names stayed
     * invisible here (KERNEL-FINDINGS KF656).
     */
    private static final Map<String, String> NOT_CONSTRUCT_ENUMS = Map.of(
            "OperationType", "the value type an operation yields, not a construct name");

    /** Every construct-name enum in the parsetree-api {@code types} package, read off the classpath. */
    private static final List<String> ENUMS = constructEnums();

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

    @Test
    void theEnumDiscoveryReadsThePackageAndItsExclusionsAreLive() {
        Set<String> all = new TreeSet<>(ENUMS);
        all.addAll(NOT_CONSTRUCT_ENUMS.keySet());
        assertTrue(enumsInTypesPackage().containsAll(all), "an excluded non-construct enum is no longer in the package");
        assertTrue(ENUMS.containsAll(List.of("CalcOp", "EntityListConditionName", "SetOpOperationName")),
                () -> "the discovery must reach the package's construct enums, kernel 31.1.1's included: " + ENUMS);
    }

    private static List<String> constructEnums() {
        return enumsInTypesPackage().stream().filter(name -> !NOT_CONSTRUCT_ENUMS.containsKey(name)).toList();
    }

    /** The top-level enums of the {@code types} package, from the jar or directory that holds {@code CalcOp}. */
    private static List<String> enumsInTypesPackage() {
        String dir = PKG.replace('.', '/');
        URL anchor = KernelConstructParityTest.class.getClassLoader().getResource(dir + "CalcOp.class");
        if (anchor == null) {
            throw new IllegalStateException("the kernel parsetree-api is not on the test classpath");
        }
        List<String> names = new ArrayList<>();
        try {
            if (anchor.getProtocol().equals("jar")) {
                try (JarFile jar = ((JarURLConnection) anchor.openConnection()).getJarFile()) {
                    jar.stream().map(JarEntry::getName).filter(entry -> entry.startsWith(dir))
                            .map(entry -> entry.substring(dir.length())).forEach(names::add);
                }
            } else {
                try (var files = Files.list(Path.of(anchor.toURI()).getParent())) {
                    files.map(file -> file.getFileName().toString()).forEach(names::add);
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("cannot list " + PKG, e);
        }
        return names.stream().filter(name -> name.endsWith(".class") && !name.contains("$") && !name.contains("/"))
                .map(name -> name.substring(0, name.length() - ".class".length()))
                .filter(simple -> loadClass(simple).isEnum()).sorted().toList();
    }

    private static Class<?> loadClass(String simple) {
        try {
            return Class.forName(PKG + simple);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
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
        // tolerance comparisons render as a worded keyword, so the enum member name (NEn) doesn't match the
        // catalog id — excluded-from-auto-match like the symbol comparisons above, but now MODELED:
        put(m, "tolerance comparison; modeled as catalog DiffersWithToleranceRange1/2/5/10 (CompareOp + Tolerance)",
                "ComparisonOperation.NE1", "ComparisonOperation.NE2", "ComparisonOperation.NE5", "ComparisonOperation.NE10");
        // Not-yet-modeled operator families (coverage backlog — WORKSPACE-CORPUS-ANALYSIS).
        // AddHours/Minutes/Seconds + DifferenceInHours/Minutes/Seconds are now cataloged (sub-day time arithmetic, family 2).
        // HoursFromTime/MinutesFromTime/SecondsFromTime + Date/TimeFromDateTime are now cataloged (TIME/DATE_TIME value kinds).
        // StartOfDateRange/EndOfDateRange are now cataloged (DateRangeBound).
        // AtLeastOneDateRangeOverlaps + DateRangesOverlap are now cataloged (reusing EntityListPredicate/FieldListEntityListCondition).
        put(m, "value-consistency condition — not yet modeled", "EntityListAndValueListConditionName.ValueNotConsistent");
        // NumberOfValueInFields (count-of-value aggregate) is now cataloged (family 6).
        // Abs/Min/Max (operand-list math, distinct from field-list MaxValue/MinValue) are now cataloged (OpListMath, family 5).
        // field-level RoundDownValue/RoundUpValue/RoundAccountingValue/AbsValue are now cataloged (FieldRounding).
        // FieldValueAsNumber/RangeAsNumber (family 4) and ValueAsDate (over a partially-known-date field) are now cataloged.
        // Today/Now/BaseYear are all cataloged now (BaseYear over a model with a base year).
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
