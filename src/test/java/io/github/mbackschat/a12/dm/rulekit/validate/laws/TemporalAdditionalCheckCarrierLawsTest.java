package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DateFragmentFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DateRangeFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * Kernel-admission laws for the opt-in pre-1900 check on its two carriers BESIDES {@code DateType} — the
 * kernel declares {@code youngerThan1900Check} on {@code DateFragmentType} and {@code DateRangeType} too, and
 * {@link TemporalDeclarationConfigAdmissionLawsTest} closed the grid only on the third.
 *
 * <p><b>The finding is that the three carriers do not agree.</b> DATEFRAGMENT reproduces DATE exactly — the
 * declared format must carry a YEAR, else {@code MVK_ADDITIONAL_CHECK_INVALID}. DATERANGE is <b>exempt from
 * that gate on every admitted pair</b>, year-free pairs included, which is the cell that makes a uniform
 * "the temporal kinds all require a year" rule refuse legal models. What the flag then DOES on a year-free
 * range is runtime behaviour and belongs to {@code adapter.laws.TemporalAdditionalCheckCarrierDiffTest}; this
 * class only settles admission.
 *
 * <p><b>Why the axis is the complete admitted vocabulary.</b> Same reason as the sibling class: the claim is
 * about the format's COMPONENTS, and a grid whose refused cells all share one string cannot separate a
 * component rule from a string allowlist. So the fragment grid runs the whole published vocabulary and the
 * range grid runs all eight admitted {@code (format, separator)} pairs. Both are registered completeness
 * claimants of {@code TemporalFormatVocabularyGuardTest}, so a short axis fails the build rather than
 * narrowing the basis silently.
 *
 * <p>Own domain (library lending records).
 */
class TemporalAdditionalCheckCarrierLawsTest {

    private static final String ADMITTED = "ADMITTED";
    private static final String ADDITIONAL_CHECK_INVALID = "MVK_ADDITIONAL_CHECK_INVALID";

    // VOCABULARY-GRID-START — the complete admitted temporal format vocabulary (docs/TEMPORAL-FORMAT-VOCABULARY.md).
    /** Every format the kernel admits on a date-bearing field; the denominator of the fragment grid below. */
    private static final List<String> FORMATS = List.of(
            "HH:mm:ss", "MM", "MM-dd", "MMdd", "dd.MM.yyyy", "ddMM",
            "yyyy", "yyyy-MM", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd");
    // VOCABULARY-GRID-END

    /**
     * The formats that admit the check on a FRAGMENT declaration, listed as measured rather than derived from a
     * component predicate — deriving them makes the test agree with the implementation instead of the kernel.
     * That this equals {@code TemporalDeclarationConfigAdmissionLawsTest}'s DateType set is the finding, not an
     * assumption: the kernel converts a fragment declaration into a date type carrying the flag, and the two
     * lists are written out separately so a future divergence shows up as a diff rather than as agreement.
     */
    private static final Set<String> FRAGMENT_ADMITS_THE_GUARD = new LinkedHashSet<>(List.of(
            "dd.MM.yyyy", "yyyy", "yyyy-MM", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd"));

    /**
     * The eight admitted DATERANGE {@code (format, separator)} pairs — the range's declaration allowlist is the
     * PAIR, never the format alone, so this is its own denominator and not a projection of {@link #FORMATS}.
     * Four of the eight are year-free, which is what gives the exemption grid cells on both sides.
     */
    private record Pair(String format, String separator) {
    }

    private static final List<Pair> RANGE_PAIRS = List.of(
            new Pair("dd.MM.yyyy", "-"), new Pair("dd.MM", "-"),
            new Pair("yyyy-MM-dd", "/"), new Pair("yyyy", "/"), new Pair("yyyy-MM", "/"),
            new Pair("MM", "/"), new Pair("MM-dd", "/"), new Pair("MM", ""));

    /** A fragment declaration reproduces the DateType gate exactly: the format must carry a year. */
    @Test
    void aFragmentDeclarationGatesTheCheckOnTheFormatsYearComponentJustAsADateDoes() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String format : FORMATS) {
            actual.put(format, verdict(fragment(f -> f.fragmentFormat(format).rejectBefore1900())));
            expected.put(format,
                    FRAGMENT_ADMITS_THE_GUARD.contains(format) ? ADMITTED : ADDITIONAL_CHECK_INVALID);
        }
        assertEquals(expected, actual,
                "the fragment kind is not exempt: a year-free fragment format refuses the check, so authoring "
                        + "it needs the same corrective a DATE declaration gets");
    }

    /** The same formats with no check — the positive control, so the grid above measures the check. */
    @Test
    void everyAdmittedFormatIsLegalOnABareFragmentDeclaration() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String format : FORMATS) {
            actual.put(format, verdict(fragment(f -> f.fragmentFormat(format))));
            expected.put(format, ADMITTED);
        }
        assertEquals(expected, actual, "the positive control: the grid above measures no format-only refusal");
    }

    /**
     * The asymmetry. Every admitted range pair takes the check, the four year-free ones included — so the gate
     * that refuses a year-free DATE and a year-free DATEFRAGMENT does not reach the range kind at all.
     */
    @Test
    void aRangeDeclarationTakesTheCheckOnEveryAdmittedPairYearFreeOnesIncluded() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Pair pair : RANGE_PAIRS) {
            String cell = pair.format() + "|" + pair.separator();
            actual.put(cell, verdict(range(r ->
                    r.format(pair.format()).separator(pair.separator()).rejectBefore1900())));
            expected.put(cell, ADMITTED);
        }
        assertEquals(expected, actual,
                "a checker that enforces the year co-requirement uniformly across the temporal kinds refuses "
                        + "these four year-free range declarations, which the kernel admits");
    }

    /** The range pairs with no check — the control that keeps the grid above about the check, not the pair. */
    @Test
    void everyAdmittedRangePairIsLegalWithoutTheCheck() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Pair pair : RANGE_PAIRS) {
            String cell = pair.format() + "|" + pair.separator();
            actual.put(cell, verdict(range(r -> r.format(pair.format()).separator(pair.separator()))));
            expected.put(cell, ADMITTED);
        }
        assertEquals(expected, actual, "the positive control for the exemption grid");
    }

    private static String fragment(Consumer<DateFragmentFieldBuilder> cfg) {
        return DocumentModelDraft.named("lending-fragment").locale("en_US")
                .rootGroup("Loan", g -> g.dateFragmentField("DueIn", cfg::accept))
                .build().toJson();
    }

    private static String range(Consumer<DateRangeFieldBuilder> cfg) {
        return DocumentModelDraft.named("lending-range").locale("en_US")
                .rootGroup("Loan", g -> g.dateRangeField("LoanWindow", cfg::accept))
                .build().toJson();
    }

    /**
     * {@link #ADMITTED}, or the distinct MVK codes the kernel's consistency gate reported — the same idiom
     * {@link TemporalDeclarationConfigAdmissionLawsTest} uses, because {@code build()} serializes any config
     * and decides nothing; the verdict has to come from {@code checkConsistency}.
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
