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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every A12 code the portable checker REPORTS says so in its MVK-LEDGER row.
 *
 * <p><b>The column this protects is an input, not a summary.</b> Family selection reads the ledger's
 * disposition to find the codes still left to A12 — the ranking of {@code kernel-delegated} rows by how much
 * measurement already sits against them. A row that still says delegated after its family landed puts a
 * finished code at the top of that list, and the cost is a session spent re-deriving work already done.
 * Measured 2026-09-14: <b>33 rows</b> had drifted that way, several of whose own prose already said the
 * checker reported them while the cell beside it did not.
 *
 * <p><b>The buckets are not one axis, which is why this is easy to forget.</b> {@code erased},
 * {@code pre-checked} and {@code n/a-structural} classify the TYPED LIBRARY surface (MVK-LEDGER, "the buckets
 * classify the typed library surface"), and {@code kernel-delegated} classifies what is left to F4. Whether
 * the portable checker reports the code is a THIRD fact, orthogonal to all of them — which is why the ledger's
 * compound cells (`pre-checked / checker-reported`, `erased + kernel-delegated`) read as they do. This guard
 * asks only for that third fact, never for a particular bucket beside it.
 *
 * <p><b>What counts as reported is {@link CheckerReportedCodes}', not this guard's.</b> It used to be this
 * guard's, and it was wrong: the scan followed a helper literally named {@code finding(…)} and so read 99 of
 * the 193 codes this checker states, which let <b>nine</b> stale rows through — two of them still saying
 * {@code kernel-delegated} for classes {@code ErrorTargetIteration.kt} had been reporting for two days, and
 * five for a family whose own row prose already said the checker reported it (measured 2026-09-15). The
 * reader now carries that question for the guards that ask it, with the interior-digit and membership-set
 * traps documented where they can be re-derived.
 *
 * <p><b>One direction only.</b> A row claiming a report the checker no longer emits is a different defect,
 * and no scan of this kind can see it without over-counting — measured 2026-09-14 at 71 false positives over
 * the 306-row ledger when tried. The drift measured here is one-way in practice — a family lands and the row
 * lags — so this locks the direction that actually happens.
 */
class CheckerLedgerDispositionGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String LEDGER = "docs/MVK-LEDGER.md";

    /** The fact this guard requires, spelled as the ledger already spells it. */
    private static final String REPORTED = "checker-reported";

    /** A ledger row: the code, then the whole disposition cell, however many buckets it names. */
    private static final Pattern LEDGER_ROW =
            Pattern.compile("^\\|\\s*`([A-Z_0-9]+)`\\s*\\|([^|]*)\\|", Pattern.MULTILINE);

    @Test
    void everyCodeTheCheckerReportsSaysSoInItsLedgerRow() {
        Map<String, String> rows = ledgerRows();
        Map<String, Set<String>> undisclosed = new TreeMap<>();
        CheckerReportedCodes.bySource().forEach((code, sources) -> {
            // A code with no row at all is OUTSIDE this ledger rather than missing from it: the ledger holds
            // the 300 `ParserErrorTypesEnum` codes, and the checker also reports codegen-tier ones such as
            // `MVK_ENUM_VALUE_WITH_INVALID_CHARACTER` that its own scope boundary excludes. Row EXISTENCE is
            // `MvkLedgerTest`'s question and is guarded there; this one only asks what a row says.
            String cell = rows.get(code);
            if (cell != null && !cell.contains(REPORTED)) {
                undisclosed.put(code, sources);
            }
        });
        assertEquals(Map.of(), undisclosed,
                "the portable checker reports these A12 codes and their MVK-LEDGER rows do not say so. Add "
                        + "`checker-reported` to the disposition cell — beside whatever bucket is already "
                        + "there, which classifies the typed library surface and is a different question — "
                        + "and say in the row what the family reports and what it stays silent on. The "
                        + "delegated rows are read as the list of codes still to do, so a stale one sends a "
                        + "later session to re-derive finished work.");
    }

    /** …and the ledger half of the scan reaches its estate, the checker half being the reader's own control. */
    @Test
    void theScanReachesTheLedger() {
        assertTrue(ledgerRows().size() >= 200, "the scan found only " + ledgerRows().size() + " ledger rows");
    }

    @Test
    void everyReportedCodeHasAStageAndRaisingSite() {
        assertEquals(CheckerReportedCodes.bySource().keySet(), stageRows("codes", "MVK_[A-Z_0-9]+").keySet());
    }

    @Test
    void everyFamilyHasAStageAndReplacementPartition() {
        String source = read(CHECKOUT.resolve(
                "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency/Check.kt"));
        String declaration = source.substring(source.indexOf("enum class CheckFamily {"));
        declaration = declaration.substring(0, declaration.indexOf("\n}"));
        Set<String> families = new TreeSet<>();
        Matcher constant = Pattern.compile("^    ([A-Z_][A-Z_0-9]*),?$", Pattern.MULTILINE).matcher(declaration);
        while (constant.find()) families.add(constant.group(1));
        assertTrue(families.contains("CONDITION_GRAMMAR"), "family enumeration missed a known member");
        assertEquals(families, stageRows("families", "[A-Z_][A-Z_0-9]*").keySet());
    }

    private static Map<String, String> stageRows(String section, String keyPattern) {
        String ledger = read(CHECKOUT.resolve(LEDGER));
        String begin = "<!-- checker-stage-" + section + ":start -->";
        String end = "<!-- checker-stage-" + section + ":end -->";
        int start = ledger.indexOf(begin);
        int stop = ledger.indexOf(end);
        assertTrue(start >= 0 && stop > start, "missing stage-map section: " + section);
        Matcher row = Pattern.compile("^\\| ([^|]+) \\| `(" + keyPattern + ")` \\| ([^|]+) \\|$",
                Pattern.MULTILINE).matcher(ledger.substring(start, stop));
        Map<String, String> result = new TreeMap<>();
        while (row.find()) {
            assertTrue(!row.group(1).isBlank() && !row.group(3).isBlank(), "empty stage or site: " + row.group());
            assertTrue(result.put(row.group(2), row.group(3)) == null, "duplicate stage row: " + row.group(2));
        }
        return result;
    }

    private static Map<String, String> ledgerRows() {
        Map<String, String> rows = new TreeMap<>();
        Matcher row = LEDGER_ROW.matcher(read(CHECKOUT.resolve(LEDGER)));
        while (row.find()) {
            rows.put(row.group(1), row.group(2));
        }
        return rows;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
