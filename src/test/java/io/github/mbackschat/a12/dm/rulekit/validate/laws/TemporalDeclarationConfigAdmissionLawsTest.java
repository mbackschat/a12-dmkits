package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.StructuralKernelFix;

/**
 * Kernel-admission laws — <b>which DateType CONFIG values a declared format admits</b>, over the whole
 * published format vocabulary.
 *
 * <p>This is the class behind {@code DG51}, enumerated rather than sampled. A {@code DateType} carries exactly
 * two config axes that can disagree with its declared format — {@code youngerThan1900Check} and
 * {@code datePrecision} — so the grid is finite and worth closing in one pass instead of refusing the one pair
 * that happened to be measured first.
 *
 * <p><b>The two axes read the format differently, which is the whole finding</b> and the reason a single
 * sampled pair could not have established either: the pre-1900 guard gates on the YEAR component alone, while
 * a partial precision gates on the format being a complete date <em>and nothing further</em>. The two gates
 * therefore disagree in both directions — {@code yyyy-MM} takes the guard and refuses the precision, and the
 * instant format {@code yyyy-MM-dd'T'HH:mm:ss} does the same despite carrying all three date components. A
 * refusal keyed on "the format is incomplete" over-rejects the first; one keyed on "the three components are
 * present" admits the second, which the kernel rejects.
 *
 * <p><b>Why the axis is every admitted format and not one spelling per component profile.</b> Both claims are
 * about the format's COMPONENTS, and a grid whose admitted cells all share one string cannot tell a component
 * rule from a string allowlist — the confound that has cost this estate two short sets already. So the axis is
 * the complete admitted vocabulary from {@code docs/TEMPORAL-FORMAT-VOCABULARY.md}, which puts three separate
 * complete-date spellings and four separator variants on both sides of each boundary. This class is a
 * registered completeness claimant of {@code TemporalFormatVocabularyGuardTest} for that reason: were the axis
 * to fall short of the kernel's admitted set, the basis under any refusal would silently narrow with it.
 *
 * <p>Own domain (library lending records).
 */
class TemporalDeclarationConfigAdmissionLawsTest {

    private static final String ADMITTED = "ADMITTED";
    private static final String ADDITIONAL_CHECK_INVALID = "MVK_ADDITIONAL_CHECK_INVALID";
    private static final String OPTIONAL_DATE_TYPE_INVALID = "MVK_OPTIONAL_DATE_TYPE_INVALID";

    // VOCABULARY-GRID-START — the complete admitted temporal format vocabulary (docs/TEMPORAL-FORMAT-VOCABULARY.md).
    /** Every format the kernel admits on a date-bearing field; the denominator of both grids below. */
    private static final List<String> FORMATS = List.of(
            "HH:mm:ss", "MM", "MM-dd", "MMdd", "dd.MM.yyyy", "ddMM",
            "yyyy", "yyyy-MM", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd");
    // VOCABULARY-GRID-END

    /**
     * The formats that admit {@code rejectBefore1900}, listed as measured rather than computed from a component
     * predicate — deriving them from the predicate a refusal would use makes the test agree with the
     * implementation instead of with the kernel.
     */
    private static final Set<String> ADMITS_THE_PRE_1900_GUARD = new LinkedHashSet<>(List.of(
            "dd.MM.yyyy", "yyyy", "yyyy-MM", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd"));

    /**
     * The formats that admit a PARTIAL {@code datePrecision}, likewise listed as measured.
     *
     * <p>The absent member is the discriminating one: {@code yyyy-MM-dd'T'HH:mm:ss} carries all three date
     * components and is nonetheless refused, so the gate is a complete date <em>and nothing further</em>, not
     * "the three components are present". A refusal keyed on component presence would admit the stamp the
     * kernel rejects — and {@link #everyAdmittedFormatIsLegalWithoutAnyDateTypeConfig} shows that stamp is a
     * legal DATE declaration here, so the cell is reachable rather than hypothetical.
     */
    private static final Set<String> ADMITS_A_PARTIAL_PRECISION = new LinkedHashSet<>(List.of(
            "dd.MM.yyyy", "yyyy-MM-dd", "yyyyMMdd"));

    /**
     * The opt-in pre-1900 check against every admitted format. The gate is the YEAR component alone — a
     * year-bearing format admits it whether or not it also carries a month, a day or a clock part.
     */
    @Test
    void theOptInPre1900CheckRequiresTheDeclaredFormatToCarryAYear() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String format : FORMATS) {
            actual.put(format, verdict(guarded(format)));
            expected.put(format,
                    ADMITS_THE_PRE_1900_GUARD.contains(format) ? ADMITTED : ADDITIONAL_CHECK_INVALID);
        }
        assertEquals(expected, actual,
                "the gate is the YEAR component, not completeness: yyyy-MM and yyyy carry no day and are still "
                        + "admitted, so a refusal keyed on an incomplete format would over-reject them");
    }

    /**
     * The same formats with NO additional check, which is what makes the table above about the check rather
     * than about the declaration. Every admitted format is legal on a bare DateType.
     */
    @Test
    void everyAdmittedFormatIsLegalWithoutAnyDateTypeConfig() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String format : FORMATS) {
            actual.put(format, verdict(plain(format)));
            expected.put(format, ADMITTED);
        }
        assertEquals(expected, actual, "the positive control: neither grid measures a format-only refusal");
    }

    /**
     * The second config axis. A partial {@code datePrecision} lets the stored VALUE omit components as zeros
     * while the FORMAT stays complete, and the kernel enforces exactly that — but "complete" means one of the
     * three pure-date spellings, not "all three components appear": the instant format carries them and is
     * refused. The verdict does not depend on WHICH component the precision makes optional.
     */
    @Test
    void aPartialDatePrecisionRequiresTheDeclaredFormatToBeACompleteDate() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (DatePrecision precision : new DatePrecision[] {
                DatePrecision.DAY_OPTIONAL, DatePrecision.MONTH_OPTIONAL, DatePrecision.YEAR_OPTIONAL}) {
            for (String format : FORMATS) {
                String cell = precision + " · " + format;
                actual.put(cell, verdict(precise(format, precision)));
                expected.put(cell,
                        ADMITS_A_PARTIAL_PRECISION.contains(format) ? ADMITTED : OPTIONAL_DATE_TYPE_INVALID);
            }
        }
        assertEquals(expected, actual,
                "the precision axis gates on a complete PURE date, so its admitted set is a strict subset of "
                        + "the pre-1900 guard's — and a client-side refusal may key on nothing wider than this");
    }

    /**
     * {@code FULL} is the kernel default and is not emitted, so it must not inherit the partial-precision gate.
     * Without this cell "a partial precision needs a complete format" and "declaring a precision at all needs
     * one" are the same observation on every row above.
     */
    @Test
    void theDefaultFullPrecisionIsNotSubjectToThatGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String format : FORMATS) {
            actual.put(format, verdict(precise(format, DatePrecision.FULL)));
            expected.put(format, ADMITTED);
        }
        assertEquals(expected, actual, "FULL is the default and carries no format requirement");
    }

    /**
     * Both axes on one field. The kernel reports the conflicts it finds rather than stopping at the first, so a
     * format that fails both gates draws both codes — which is what a diagnostic for either has to survive.
     */
    @Test
    void theTwoAxesAreReportedIndependentlyWhenBothConflict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("yyyy-MM-dd", verdict(guardedAndPrecise("yyyy-MM-dd", DatePrecision.DAY_OPTIONAL)));
        actual.put("yyyy", verdict(guardedAndPrecise("yyyy", DatePrecision.DAY_OPTIONAL)));
        actual.put("MM-dd", verdict(guardedAndPrecise("MM-dd", DatePrecision.DAY_OPTIONAL)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("yyyy-MM-dd", ADMITTED);
        expected.put("yyyy", OPTIONAL_DATE_TYPE_INVALID);
        expected.put("MM-dd", ADDITIONAL_CHECK_INVALID + ", " + OPTIONAL_DATE_TYPE_INVALID);
        assertEquals(expected, actual,
                "a complete date satisfies both gates; a year-only format fails only the precision one; a "
                        + "year-free incomplete one fails both and the kernel names both");
    }

    /**
     * The refusal TEXT, pinned because the curated {@code StructuralKernelFix} hints for these two codes quote
     * it — and because it is the only part of the rejection that says which of a DateType's config axes the
     * reader got wrong.
     */
    @Test
    void eachRefusalSaysWhichConfigAxisTheFormatConflictsWith() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put(ADDITIONAL_CHECK_INVALID, message(guarded("MM-dd")));
        actual.put(OPTIONAL_DATE_TYPE_INVALID, message(precise("yyyy-MM", DatePrecision.DAY_OPTIONAL)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(ADDITIONAL_CHECK_INVALID, "model is not consistent: Field with path '/Loan/DueOn': "
                + "For date fields without a year, no 'additional check' may be specified. "
                + "[MVK_ADDITIONAL_CHECK_INVALID]");
        expected.put(OPTIONAL_DATE_TYPE_INVALID, "model is not consistent: Field with path '/Loan/DueOn': "
                + "A partial known date is not permitted for the format: 'JJJJ-MM'. "
                + "[MVK_OPTIONAL_DATE_TYPE_INVALID]");
        assertEquals(expected, actual, "the enricher hints quote these, so a kernel rewording must fail here");
    }

    /**
     * The trap inside that second message, and the reason its hint has to correct rather than merely explain:
     * the English rejection echoes the offending format back in <b>German pattern letters</b> — the model
     * declares {@code yyyy-MM} and is told {@code 'JJJJ-MM'} — so an author who copies the spelling out of the
     * diagnostic writes a format the kernel refuses outright.
     *
     * <p><b>It is a fixed letter substitution, not a localization.</b> The same model built with a
     * {@code de_DE} locale produces the identical spelling, which is what separates "the message renders the
     * format in the reader's locale" from "the message always renders German letters" — and it is the second.
     * A hint keyed on the reader's locale would therefore be wrong for every reader.
     */
    @Test
    void thatMessageEchoesTheFormatInGermanPatternLettersRegardlessOfLocale() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("en_US · yyyy-MM", echoedFormat("en_US", "yyyy-MM"));
        actual.put("de_DE · yyyy-MM", echoedFormat("de_DE", "yyyy-MM"));
        actual.put("en_US · yyyy", echoedFormat("en_US", "yyyy"));
        actual.put("en_US · MM-dd", echoedFormat("en_US", "MM-dd"));
        actual.put("en_US · MMdd", echoedFormat("en_US", "MMdd"));
        actual.put("en_US · HH:mm:ss", echoedFormat("en_US", "HH:mm:ss"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("en_US · yyyy-MM", "JJJJ-MM");
        expected.put("de_DE · yyyy-MM", "JJJJ-MM");
        expected.put("en_US · yyyy", "JJJJ");
        expected.put("en_US · MM-dd", "MM-TT");
        expected.put("en_US · MMdd", "MMTT");
        expected.put("en_US · HH:mm:ss", "HH:mm:ss");
        assertEquals(expected, actual,
                "y→J and d→T, both locales: the echoed spelling is never a format you may declare, which is "
                        + "exactly what makes it worth a corrective rather than an explanation");
    }

    /**
     * The corrective rides the real kernel rejection all the way to the surface a user meets.
     *
     * <p>Both codes are ORPHANS of the operator catalog-join — no operator's {@code invalidExample} documents
     * a field-declaration conflict — so without a curated {@link StructuralKernelFix} entry the rejection
     * arrives with an empty {@code fix} and {@code dmtool diagnostics <code>} dead-ends. This asserts the
     * enrichment through {@code KernelDiagnostics.enriched}, which is the exact call {@code model check} makes
     * on each notification of a {@code ModelConsistencyException}, and it checks the one claim each hint makes
     * that the kernel's own message does not: which formats WOULD be accepted.
     */
    @Test
    void bothRefusalsCarryACorrectiveThroughTheModelCheckPath() {
        Map<String, String> fixes = new LinkedHashMap<>();
        fixes.put(ADDITIONAL_CHECK_INVALID, enrichedFix(guarded("MM-dd"), ADDITIONAL_CHECK_INVALID));
        fixes.put(OPTIONAL_DATE_TYPE_INVALID,
                enrichedFix(precise("yyyy-MM", DatePrecision.DAY_OPTIONAL), OPTIONAL_DATE_TYPE_INVALID));

        assertTrue(fixes.get(ADDITIONAL_CHECK_INVALID).contains("`yyyy-MM`"),
                () -> "the pre-1900 corrective must name a year-bearing format that IS accepted, since the "
                        + "kernel's message names none: " + fixes.get(ADDITIONAL_CHECK_INVALID));
        assertTrue(fixes.get(OPTIONAL_DATE_TYPE_INVALID).contains("`yyyyMMdd`")
                        && fixes.get(OPTIONAL_DATE_TYPE_INVALID).contains("JJJJ-MM"),
                () -> "the precision corrective must name the accepted complete formats and warn off the German "
                        + "spelling the message echoes: " + fixes.get(OPTIONAL_DATE_TYPE_INVALID));
    }

    // ---- the instruments ----

    /** The enriched {@code fix} for the notification carrying {@code code}, or a message saying what was found. */
    private static String enrichedFix(String modelJson, String code) {
        ModelConsistencyException rejection =
                assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(modelJson));
        return rejection.notifications().stream()
                .map(n -> KernelDiagnostics.enriched(n, null))
                .filter(d -> d.code().filter(code::equals).isPresent())
                .map(Diagnostic::fix)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseGet(() -> "no enriched " + code + " fix in " + rejection.notifications());
    }


    /** The kernel's own sentence for the ERRORs the model draws. */
    private static String message(String modelJson) {
        try {
            BoundModel.ofJson(modelJson);
            return ADMITTED;
        } catch (ModelConsistencyException e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** The format spelling the partial-precision refusal quotes back, for a model in {@code locale}. */
    private static String echoedFormat(String locale, String format) {
        String json = DocumentModelDraft.named("lending-echo").locale(locale)
                .rootGroup("Loan", g -> g.dateField("DueOn",
                        d -> d.format(format).datePrecision(DatePrecision.DAY_OPTIONAL)))
                .build().toJson();
        Matcher quoted = Pattern.compile("format: '([^']*)'").matcher(message(json));
        return quoted.find() ? quoted.group(1) : message(json);
    }

    private static String guarded(String format) {
        return model("lending-guarded", d -> d.format(format).rejectBefore1900());
    }

    private static String plain(String format) {
        return model("lending-plain", d -> d.format(format));
    }

    private static String precise(String format, DatePrecision precision) {
        return model("lending-precise", d -> d.format(format).datePrecision(precision));
    }

    private static String guardedAndPrecise(String format, DatePrecision precision) {
        return model("lending-both", d -> d.format(format).datePrecision(precision).rejectBefore1900());
    }

    private static String model(
            String name, java.util.function.Consumer<io.github.mbackschat.a12.dm.modelkit.DateFieldBuilder> cfg) {
        return DocumentModelDraft.named(name).locale("en_US")
                .rootGroup("Loan", g -> g.dateField("DueOn", cfg::accept))
                .build().toJson();
    }

    /**
     * {@link #ADMITTED}, or the distinct MVK codes the kernel's consistency gate reported. Bound through
     * {@code BoundModel.ofJson}, which runs {@code checkConsistency} and throws on an ERROR — the idiom this
     * tier already uses, because {@code build()} serializes any config and decides nothing.
     */
    private static String verdict(String modelJson) {
        try {
            BoundModel.ofJson(modelJson);
            return ADMITTED;
        } catch (ModelConsistencyException e) {
            Matcher codes = Pattern.compile("(MVK_[A-Z_0-9]+)").matcher(String.valueOf(e.getMessage()));
            Set<String> found = new java.util.TreeSet<>();
            while (codes.find()) {
                found.add(codes.group(1));
            }
            return found.isEmpty() ? String.valueOf(e.getMessage()) : String.join(", ", found);
        }
    }
}
