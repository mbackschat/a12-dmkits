package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
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
 * <p><b>Only a string LITERAL counts as reported.</b> Prose naming a code is a different claim and usually
 * the opposite one — this estate's KDoc routinely names codes the checker deliberately does NOT emit (a dead
 * gate, a code that fires for no reachable shape), and all 13 such mentions are measured anyway. Scanning
 * prose also needs a heuristic for what is a code: {@code MVK_VALUE_*} in {@code EnumValueLabels.kt} is a
 * wildcard over a code family and names no code at all, and a guard that fails honest content gets disabled.
 * The checker builds no code by concatenation (verified 2026-09-13), so the literal scan misses nothing it
 * reports.
 *
 * <p>{@code RK_*} and {@code CK_*} are out of scope by construction: those are this project's own corrective
 * and checker-verdict vocabularies, not A12 verdicts, so no laws test could carry them.
 */
class CheckerCodeBasisGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * A code the checker REPORTS — the trailing digits belong to it, since {@code MVK_DUPLICATE_PARAM1} and
     * {@code MVK_DUPLICATE_PARAM2} are two different laws.
     */
    private static final Pattern REPORTED = Pattern.compile("\"(MVK_[A-Z_]+[0-9]*)\"");

    /** A code a laws test NAMES, in an expectation or in the javadoc recording what the grid measured. */
    private static final Pattern MEASURED = Pattern.compile("MVK_[A-Z_]+[0-9]*");

    private static final String CHECKER_MAIN_SOURCE = "consistency/src/commonMain";

    @Test
    void everyA12CodeTheCheckerReportsWasMeasuredAgainstTheKernel() {
        Set<String> measured = measuredCodes();
        Map<String, Set<String>> unmeasured = new TreeMap<>();
        for (Path source : checkerSources()) {
            Matcher matcher = REPORTED.matcher(read(source));
            while (matcher.find()) {
                if (!measured.contains(matcher.group(1))) {
                    unmeasured.computeIfAbsent(matcher.group(1), code -> new TreeSet<>())
                            .add(CHECKOUT.relative(source));
                }
            }
        }
        assertEquals(Map.of(), unmeasured,
                "the portable checker reports an A12 code that no laws test measures, so it states a kernel "
                        + "verdict nothing ever put to the kernel. Measure the shape first — a laws test "
                        + "under validate/laws (accept/reject) or adapter/laws (fires/does-not-fire) — and "
                        + "report the code only once the grid says A12 draws it.");
    }

    /**
     * …and both halves of the scan reach their estate, so an empty result above means clean rather than
     * broken.
     *
     * <p>The floors answer "did the scan happen", not "how big is each set", so they sit well below the live
     * counts (66 reported, 181 measured at 2026-09-13) on purpose.
     */
    @Test
    void theScanReachesBothEstates() {
        long reported = checkerSources().stream()
                .flatMap(source -> REPORTED.matcher(read(source)).results())
                .map(result -> result.group(1))
                .distinct()
                .count();
        assertTrue(reported >= 40, "the scan found only " + reported + " reported codes in the checker");
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

    private static List<Path> checkerSources() {
        return CHECKOUT.tracked(CHECKER_MAIN_SOURCE).stream()
                .filter(path -> path.getFileName().toString().endsWith(".kt"))
                .toList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
