package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every A12 code the portable checker EMITS says so in its MVK-LEDGER row.
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
 * <p><b>What counts as emitted, and why the scan is narrow here.</b> A code literal inside the argument list
 * of a {@code Finding(...)} construction, or held in a {@code val} the file then passes to one. The
 * neighbouring {@link CheckerCodeBasisGuardTest} deliberately takes every string literal, because for its
 * question — was this code ever measured — over-counting is harmless. Here it would not be: the checker holds
 * codes it never reports, {@code SUPPRESSIBLE} being the worked case, and a guard that demands a
 * checker-reported row for a code the checker only compares against fails honest content.
 *
 * <p><b>A file-local {@code finding(…)} helper is a construction too (widened 2026-09-14, measured).</b> Many
 * families declare {@code private fun finding(code, explain, at): Finding} and never write the constructor
 * again, so a scan keyed on the capitalised name saw none of their codes — eighteen of them, including codes
 * this repository's own progress reporting then counted as UNREPORTED work still to do. The helper is
 * followed only where the file DECLARES one, which keeps the narrowness the paragraph above argues for: a
 * call to something the file does not define as returning a {@code Finding} is still not a report.
 *
 * <p><b>One direction only.</b> A row claiming a report the checker no longer emits is a different defect,
 * and no scan of this kind can see it without over-counting — measured 2026-09-14 at 71 false positives over
 * the 306-row ledger when tried. The drift measured here is one-way in practice — a family lands and the row
 * lags — so this locks the direction that actually happens.
 */
class CheckerLedgerDispositionGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String CHECKER_MAIN_SOURCE = "consistency/src/commonMain";

    private static final String LEDGER = "docs/MVK-LEDGER.md";

    /** The fact this guard requires, spelled as the ledger already spells it. */
    private static final String REPORTED = "checker-reported";

    private static final Pattern CODE_LITERAL = Pattern.compile("\"(MVK_[A-Z_]+[0-9]*)\"");

    /** A code parked in a local name — {@code private const val X = "MVK_…"} — that a {@code Finding} then takes. */
    private static final Pattern CODE_CONSTANT =
            Pattern.compile("val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*String\\s*)?=\\s*\"(MVK_[A-Z_]+[0-9]*)\"");

    /** A ledger row: the code, then the whole disposition cell, however many buckets it names. */
    private static final Pattern LEDGER_ROW = Pattern.compile("^\\|\\s*`([A-Z_0-9]+)`\\s*\\|([^|]*)\\|", Pattern.MULTILINE);

    @Test
    void everyCodeTheCheckerEmitsSaysSoInItsLedgerRow() {
        Map<String, String> rows = ledgerRows();
        Map<String, Set<String>> undisclosed = new TreeMap<>();
        emittedCodes().forEach((code, sources) -> {
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
                "the portable checker emits these A12 codes and their MVK-LEDGER rows do not say so. Add "
                        + "`checker-reported` to the disposition cell — beside whatever bucket is already "
                        + "there, which classifies the typed library surface and is a different question — "
                        + "and say in the row what the family reports and what it stays silent on. The "
                        + "delegated rows are read as the list of codes still to do, so a stale one sends a "
                        + "later session to re-derive finished work.");
    }

    /** …and the scan reaches the checker, so an empty result above means clean rather than broken. */
    @Test
    void theScanReachesTheChecker() {
        assertTrue(emittedCodes().size() >= 40,
                "the scan found only " + emittedCodes().size() + " emitted codes in the checker");
        assertTrue(ledgerRows().size() >= 200, "the scan found only " + ledgerRows().size() + " ledger rows");
    }

    /** Every A12 code a {@code Finding} construction carries, mapped to the sources that build it. */
    private static Map<String, Set<String>> emittedCodes() {
        Map<String, Set<String>> emitted = new TreeMap<>();
        for (Path source : checkerSources()) {
            String text = withoutComments(read(source));
            Map<String, String> constants = new HashMap<>();
            Matcher declared = CODE_CONSTANT.matcher(text);
            while (declared.find()) {
                constants.put(declared.group(1), declared.group(2));
            }
            for (String argument : findingArguments(text)) {
                Matcher literal = CODE_LITERAL.matcher(argument);
                while (literal.find()) {
                    emitted.computeIfAbsent(literal.group(1), code -> new TreeSet<>()).add(CHECKOUT.relative(source));
                }
                // Outside the strings: a code name reached through `$SUPPRESSIBLE` in a MESSAGE is the
                // opposite claim — that text tells an author which code the one being reported is not.
                String code = withoutStrings(argument);
                constants.forEach((name, named) -> {
                    if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code).find()) {
                        emitted.computeIfAbsent(named, key -> new TreeSet<>()).add(CHECKOUT.relative(source));
                    }
                });
            }
        }
        return emitted;
    }

    /** The argument list of every {@code Finding(} construction, taken to its balancing parenthesis. */
    private static List<String> findingArguments(String text) {
        List<String> arguments = new java.util.ArrayList<>();
        // A file that declares its own `finding(…): Finding` helper writes the constructor once and calls the
        // helper everywhere after, so following the helper is what makes those files visible at all. The
        // declaration itself is excluded, or its parameter list would read as an argument list.
        boolean helper = Pattern.compile("fun\\s+finding\\(").matcher(text).find();
        Matcher call = Pattern.compile(helper ? "(?<!fun )\\b[Ff]inding\\(" : "\\bFinding\\(").matcher(text);
        while (call.find()) {
            int depth = 1;
            int at = call.end();
            while (at < text.length() && depth > 0) {
                char c = text.charAt(at++);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            arguments.add(text.substring(call.end(), at));
        }
        return arguments;
    }

    /**
     * The source with its comments removed.
     *
     * <p>A KDoc naming a code is the opposite claim in this estate — the tables saying which gate answers
     * ahead of the one a family reports are written that way — so a scan that kept them would demand a
     * checker-reported row for every neighbouring class the checker deliberately leaves alone.
     */
    /** The text with every string literal removed, raw ones included. */
    private static String withoutStrings(String text) {
        return text.replaceAll("(?s)\"\"\".*?\"\"\"", "").replaceAll("\"(\\\\.|[^\"\\\\])*\"", "");
    }

    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static Map<String, String> ledgerRows() {
        Map<String, String> rows = new TreeMap<>();
        Matcher row = LEDGER_ROW.matcher(read(CHECKOUT.resolve(LEDGER)));
        while (row.find()) {
            rows.put(row.group(1), row.group(2));
        }
        return rows;
    }

    private static List<Path> checkerSources() {
        return CHECKOUT.tracked(CHECKER_MAIN_SOURCE).stream()
                .filter(path -> path.getFileName().toString().endsWith(".kt"))
                .filter(path -> !path.getFileName().toString().equals("KernelCorrectives.kt"))
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
