package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every A12 code the portable checker REPORTS was measured against the real kernel by a laws test.
 *
 * <p><b>The claim a reported code makes.</b> A {@code Finding} carrying {@code MVK_X} says <em>A12 draws
 * {@code MVK_X} on this declaration</em> — a statement about the kernel, not about the checker. When nothing
 * ever put that shape to the kernel, the statement rests on a reading of kernel source or on a sibling code's
 * behaviour transferred sideways, and the failure mode is the one direction this project forbids: reporting
 * where A12 accepts. Delegation can only under-report and is safe; a verdict we invent is invisible
 * precisely because the kernel never gets asked (CLAUDE.md, "A client-side refusal needs a measured kernel
 * basis").
 *
 * <p><b>Why a guard rather than the discipline.</b> The discipline already says measure first, and it has
 * held — all 66 codes the checker reported when this was written are measured (2026-09-13). But the checker
 * gains codes in batches, each new family arriving with its own grid, and the one that would slip is a code
 * added to an EXISTING family beside codes whose measurements are already there: the file reads as covered,
 * the mirror goes green against the checker's own output, and no run anywhere asks the kernel. Nothing else
 * in the estate can see that, because a mirror measures the checker and a laws test measures the kernel, and
 * only their intersection carries this fact.
 *
 * <p><b>What it proves, and what it does not.</b> It proves a code has SOME kernel measurement, never that
 * the measurement covers the shape this finding is emitted on. A code measured on one carrier and reported on
 * another passes here — that is the scope-transfer class, which no scan can decide and which the witness-grid
 * discipline owns instead. The weaker claim is still the one with teeth, because the shape it rules out —
 * a code nobody ever put to the kernel at all — leaves no other trace.
 *
 * <p><b>Both denominators are derived.</b> The reported set is read off the checker's own sources and the
 * measured set off every {@code laws} directory git tracks, so a new checker family and a new laws tier both
 * arrive on their own. An authored list would only ever hold the codes someone remembered.
 *
 * <p><b>What counts as reported is {@link CheckerReportedCodes}', not this guard's.</b> It used to be a local
 * scan taking every string literal, on the argument that over-counting is harmless for this question. It is
 * not: the four codes this checker holds only as members of {@code Check.kt}'s phase and suppression lists
 * read as reported, and {@code MVK_ITERATION_RESTRICTION_IN_A12_MODEL} — a member no family emits, and
 * {@code erased} in the ledger for a shape rulekit cannot render — has no laws measurement and could not
 * honestly be given one. This guard passed only because its pattern could not match that code's interior
 * digits; correcting the pattern alone would have turned it red on honest content (measured 2026-09-15).
 *
 * <p>{@code RK_*} and {@code CK_*} are out of scope by construction: those are this project's own corrective
 * and checker-verdict vocabularies, not A12 verdicts, so no laws test could carry them.
 */
class CheckerCodeBasisGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * A code a laws test NAMES, in an expectation or in the javadoc recording what the grid measured.
     *
     * <p>Digits belong to the code wherever they fall: the trailing run separates
     * {@code MVK_DUPLICATE_PARAM1} from {@code MVK_DUPLICATE_PARAM2}, and an interior one is the whole
     * difference between {@code MVK_NAME_STARTS_WITH_XML} and the {@code _A12_DV} carrier of the same law.
     */
    private static final Pattern MEASURED = Pattern.compile("MVK_[A-Z0-9_]+");

    @Test
    void everyA12CodeTheCheckerReportsWasMeasuredAgainstTheKernel() {
        Set<String> measured = measuredCodes();
        Map<String, Set<String>> unmeasured = new TreeMap<>();
        CheckerReportedCodes.bySource().forEach((code, sources) -> {
            if (!measured.contains(code)) {
                unmeasured.put(code, sources);
            }
        });
        assertEquals(Map.of(), unmeasured,
                "the portable checker reports an A12 code that no laws test measures, so it states a kernel "
                        + "verdict nothing ever put to the kernel. Measure the shape first — a laws test "
                        + "under validate/laws (accept/reject) or adapter/laws (fires/does-not-fire) — and "
                        + "report the code only once the grid says A12 draws it.");
    }

    /**
     * …and the same claim for the UNPREFIXED error-text vocabulary, which had no guard at all.
     *
     * <p>Its own test rather than a widened denominator above, because the two halves are recognised
     * differently: a prefix is decidable from the token, an unprefixed code needs the kernel's enum. Folding
     * them would make the prefixed half — 200-odd codes with a working guard — abort whenever the kernel is
     * off the classpath.
     */
    @Test
    void everyErrorTextCodeTheCheckerReportsWasMeasuredAgainstTheKernel() {
        Set<String> vocabulary = KernelErrorVocabulary.errorTextCodes();
        Set<String> measured = measuredCodes(vocabulary);
        Map<String, Set<String>> reported = CheckerReportedCodes.bySource(vocabulary::contains);
        // The floor, on the REPORTED side rather than the measured one: an empty denominator here passes
        // silently, and it is the likelier break — the vocabulary is read reflectively and the scan is a
        // membership test, so either can go quiet without any other symptom. 26 reported at 2026-09-15.
        assertTrue(reported.size() >= 15,
                "the scan found only " + reported.size() + " error-text codes in the checker's sources");
        Map<String, Set<String>> unmeasured = new TreeMap<>();
        reported.forEach((code, sources) -> {
            if (!measured.contains(code)) {
                unmeasured.put(code, sources);
            }
        });
        assertEquals(Map.of(), unmeasured,
                "the portable checker reports an error-text code that no laws test measures. These carry the "
                        + "same kernel claim as the prefixed ones and are easier to state by accident, "
                        + "because the word alone does not say it is a verdict.");
    }

    /**
     * …and the LAWS half of the scan reaches its estate, so an empty result above means clean rather than
     * broken.
     *
     * <p>The floor answers "did the scan happen", not "how big is the set", so it sits well below the live
     * count (243 measured at 2026-09-15) on purpose. The reported half is {@link CheckerReportedCodesTest}'s
     * floor, which is where that reader's own controls live.
     */
    @Test
    void theScanReachesTheLawsEstate() {
        assertTrue(measuredCodes().size() >= 120,
                "the scan found only " + measuredCodes().size() + " measured codes in the laws estate");
    }

    /** Every A12 code named by a laws test, in either tier — the derived measured set. */
    private static Set<String> measuredCodes() {
        Set<String> measured = new TreeSet<>();
        for (Path source : CHECKOUT.testSources()) {
            if (CHECKOUT.relative(source).contains("/laws/")) {
                MEASURED.matcher(read(source)).results().forEach(result -> measured.add(result.group()));
            }
        }
        return measured;
    }

    /**
     * The same walk over a vocabulary whose members are not recognisable from shape.
     *
     * <p>Over-matching here is the safe direction and is why the token pattern is loose: a laws test naming
     * one of these words in a fixture rather than in an expectation only makes the guard weaker, while a
     * pattern too tight to find a genuine expectation would fail honest content.
     */
    private static Set<String> measuredCodes(Set<String> vocabulary) {
        Set<String> measured = new TreeSet<>();
        for (Path source : CHECKOUT.testSources()) {
            if (CHECKOUT.relative(source).contains("/laws/")) {
                TOKEN.matcher(read(source)).results()
                        .filter(result -> vocabulary.contains(result.group()))
                        .forEach(result -> measured.add(result.group()));
            }
        }
        return measured;
    }

    /** Any screaming-case word a laws test writes; the vocabulary decides which ones are codes. */
    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{2,}");

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
