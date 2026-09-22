package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The ledger's residue figure is DERIVED from its own rows, not remembered.
 *
 * <p><b>It is the project's own capacity signal, and it steers which family lands next.</b> The ledger states
 * how many of its rows still name a code the portable checker does not report, and a session picks its next
 * block off that number. When it lags, the work is chosen against a picture of the estate that is no longer
 * true — measured 2026-09-15, when it read 31 with three blocks already closed and the derived value at 26.
 *
 * <p><b>Why a guard rather than the "re-derive rather than cite this" note the paragraph already carries.</b>
 * That note is correct and was followed: each block that closed rewrote the number by hand, and the arithmetic
 * behind it lived only in the session that ran it. A figure nobody can recompute is not checkable, so the
 * paragraph now states the whole derivation — total rows, rows naming an unreported code, the two exclusions —
 * and this guard recomputes all four. A stale figure fails with the recomputed sentence, not with a diff.
 *
 * <p><b>The exclusions are two different kinds of fact, and only one is mechanical.</b> A disposition CELL
 * saying {@code unreachable from stored DM-JSON} or {@code never reported} is a fixed marker. The second
 * exclusion is row PROSE saying the code fires for no reachable shape, which has no marker at all — the
 * [containment record](../../../../../../../../docs/archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md) names that as
 * the figure's own caveat. So {@link #NEVER_FIRES} is a phrase list and is deliberately narrow: a row it
 * misses stays in the residue, which OVERSTATES the work remaining. That is the safe direction — the figure
 * is an upper bound by construction, and a guard erring the other way would hide capacity.
 *
 * <p><b>Two reported-code figures differ legitimately, so name the quantity before calling it drift
 * (measured 2026-09-18).</b> This guard's arithmetic yields <b>207</b>, meaning LEDGER ROWS whose code the
 * checker states, while {@code CheckerReportedCodes.all()} returns <b>215</b>, meaning every {@code MVK_}
 * code the checker's sources state. The 8-code gap is exactly the {@code NoMetaModelCheckErrorType}
 * codegen tier, which this ledger's own scope-boundary note places outside it: {@code
 * MVK_ENUM_VALUE_WITH_INVALID_CHARACTER}, {@code MVK_INDEX_FIELD_NO_VALUE_VALIDATION}, {@code
 * MVK_INVALID_INDEX_FIELD}, {@code MVK_INVALID_SORT_FIELD}, {@code MVK_MAX_VALUES_INVALID}, {@code
 * MVK_PATHS_INCONSISTENT}, {@code MVK_PATH_INVALID}, {@code MVK_SORT_FIELD_NO_VALUE_VALIDATION} — the same
 * 8 that {@code CodegenTierCodeDenominatorGuardTest} pins as its reported half. So 215 = 207 + 8 closes
 * exactly, and neither number is wrong.
 *
 * <p><b>What counts as reported is {@link CheckerReportedCodes}'</b>, the same reader
 * {@code CheckerLedgerDispositionGuardTest} and {@code CheckerCodeBasisGuardTest} ask — so a family landing
 * today moves this figure without anyone editing a list.
 */
class LedgerResidueFigureGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * The ledger, which now carries BOTH the rows and the prose figure derived from them.
     *
     * <p>The prose half moved here on 2026-09-19, when the portable-consistency implementation plan was
     * archived on completion. A figure derived from sources that keep changing is the one thing a frozen
     * record must not state: this guard would have begun failing on the next checker edit with no legitimate
     * remedy, because the only available fix would be editing the account of a finished program to track live
     * numbers. Stating it in the document that owns the rows it counts also removes the second copy.
     */
    private static final String LEDGER = "docs/MVK-LEDGER.md";

    /** A ledger row: the code, then its whole disposition cell. */
    private static final Pattern LEDGER_ROW =
            Pattern.compile("^\\|\\s*`(MVK_[A-Z0-9_]+)`\\s*\\|([^|]*)\\|");

    /** The disposition-cell markers that close a row outright — the mechanical exclusion. */
    private static final List<String> CELL_CLOSED =
            List.of("unreachable from stored DM-JSON", "never reported");

    /**
     * Row prose saying the code fires for no reachable shape.
     *
     * <p>Narrow on purpose (see the class note): every phrase here was read off a row that says it, and a
     * wording this misses leaves its row counted as open.
     */
    private static final Pattern NEVER_FIRES = Pattern.compile(
            "never reported|never gets to fire|never this code|not a fireable law"
                    + "|measured unreachable|derivation-only|never fires|no reachable",
            Pattern.CASE_INSENSITIVE);

    /** The stated residue, and the arithmetic the row must show for it. */
    private static final Pattern STATED_RESIDUE =
            Pattern.compile("(\\d+) open candidate rows once the measured-unreachable are removed");

    private static final Pattern STATED_ARITHMETIC = Pattern.compile(
            "of the (\\d+) rows, (\\d+) name a code the checker does not state, "
                    + "(\\d+) are excluded by their disposition CELL and (\\d+) by row PROSE");

    @Test
    void theLedgersResidueFigureMatchesWhatItsRowsAndTheCheckerNowSay() {
        Residue derived = derive();
        String ledger = read(LEDGER);
        Matcher residue = STATED_RESIDUE.matcher(ledger);
        assertTrue(residue.find(), () -> LEDGER + " no longer states a residue figure in the guarded form");
        Matcher arithmetic = STATED_ARITHMETIC.matcher(ledger);
        assertTrue(arithmetic.find(), () -> LEDGER + " no longer states the residue's arithmetic");

        assertEquals(derived.sentence() + ", leaving " + derived.open(), stated(residue, arithmetic),
                "the ledger's residue paragraph has drifted from its own rows; the derived sentence is expected");
    }

    /** A positive control on the reader: a residue nobody has closed is not zero, and the ledger is whole. */
    @Test
    void theDerivationReachesTheWholeLedgerAndLeavesWorkInIt() {
        Residue derived = derive();
        assertTrue(derived.total >= 300,
                () -> "the ledger scan reached only " + derived.total + " rows; the ledger holds ~306");
        assertTrue(derived.unreported > derived.cellClosed,
                () -> "more rows were excluded than were found unreported: " + derived.sentence());
        assertTrue(derived.open() > 0, "a zero residue is a reader that matched nothing, until one is measured");
    }

    private static String stated(Matcher residue, Matcher arithmetic) {
        return new Residue(
                Integer.parseInt(arithmetic.group(1)),
                Integer.parseInt(arithmetic.group(2)),
                Integer.parseInt(arithmetic.group(3)),
                Integer.parseInt(arithmetic.group(4)))
                .sentence() + ", leaving " + residue.group(1);
    }

    private static Residue derive() {
        var reported = CheckerReportedCodes.all();
        int total = 0;
        int unreported = 0;
        int cellClosed = 0;
        int proseClosed = 0;
        for (String row : read(LEDGER).lines().toList()) {
            Matcher matched = LEDGER_ROW.matcher(row);
            if (!matched.find()) {
                continue;
            }
            total++;
            if (reported.contains(matched.group(1))) {
                continue;
            }
            unreported++;
            if (CELL_CLOSED.stream().anyMatch(matched.group(2)::contains)) {
                cellClosed++;
            } else if (NEVER_FIRES.matcher(row).find()) {
                // The prose exclusion reads the WHOLE row, not the disposition cell: a row saying the code
                // fires for nothing says it in the prose column, which is why the cell test cannot find it.
                proseClosed++;
            }
        }
        return new Residue(total, unreported, cellClosed, proseClosed);
    }

    private record Residue(int total, int unreported, int cellClosed, int proseClosed) {

        int open() {
            return unreported - cellClosed - proseClosed;
        }

        String sentence() {
            return "of the " + total + " rows, " + unreported + " name a code the checker does not state, "
                    + cellClosed + " are excluded by their disposition CELL and " + proseClosed
                    + " by row PROSE";
        }
    }

    private static String read(String relative) {
        try {
            return Files.readString(CHECKOUT.resolve(relative));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + relative, unreadable);
        }
    }
}
