package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Instrument 7 of the constraint-mining discipline: {@code docs/GRAMMAR-LEDGER.md} must classify
 * <em>exactly</em> the parser productions of the kernel's condition grammar
 * ({@code GrammarOnly_ConditionParser.g4}). Keywords have two-way parity and constructs have reverse
 * parity, but a <em>syntax shape</em> (an optional clause, an alternative form, a new production) can
 * appear without a new keyword or enum member — this guardian closes that blind spot.
 *
 * <p><b>Opt-in</b> like the other sibling-gated harnesses: reads the grammar from the consult-only
 * kernel source checkout ({@code ../a12-kernel}, overridable via {@code -Da12.kernel.src.dir=…}) and
 * skips when absent. Productions are extracted comment-stripped, so the grammar's javadoc prose
 * (which contains {@code checker class: …} lines) cannot fake a rule.
 */
class GrammarProductionLedgerTest {

    private static final String KERNEL_SRC_DIR = System.getProperty("a12.kernel.src.dir", "../a12-kernel");
    private static final String GRAMMAR = "kernel-tool/kernel-core-parser/src/main/antlr4/"
            + "com/mgmtp/a12/kernel/core/parser/internal/grammar/gen/GrammarOnly_ConditionParser.g4";

    /** An ANTLR rule definition: a production name at line start, colon on the same or a following line. */
    private static final Pattern PRODUCTION = Pattern.compile("(?m)^([A-Za-z][A-Za-z0-9_]*)\\s*:");

    /** A backticked production name inside a ledger row's first cell (rows may pair several names). */
    private static final Pattern BACKTICKED_NAME = Pattern.compile("`([A-Za-z][A-Za-z0-9_]*)`");

    @Test
    void ledgerCoversTheGrammarProductionsExactly() throws IOException {
        Set<String> productions = grammarProductions();
        Set<String> ledger = ledgerProductions();

        Set<String> missing = new LinkedHashSet<>(productions);
        missing.removeAll(ledger);
        Set<String> stale = new LinkedHashSet<>(ledger);
        stale.removeAll(productions);

        assertTrue(missing.isEmpty() && stale.isEmpty(),
                "GRAMMAR-LEDGER drift — unclassified productions (a kernel grammar change?): " + missing
                        + "; stale ledger rows (production gone): " + stale);
    }

    @Test
    void rehearsal_aKernelAddedProductionWouldFail() throws IOException {
        Set<String> withAddition = new LinkedHashSet<>(grammarProductions());
        withAddition.add("brandNewCondition");
        assertFalse(ledgerProductions().containsAll(withAddition),
                "the rehearsal production must NOT be covered — otherwise this guardian cannot fail");
    }

    private static Set<String> grammarProductions() throws IOException {
        Path grammar = Path.of(KERNEL_SRC_DIR, GRAMMAR);
        assumeTrue(Files.isReadable(grammar), "kernel source checkout not present — skipping grammar parity");

        String src = Files.readString(grammar);
        src = src.replaceAll("(?s)/\\*.*?\\*/", "");   // block comments (incl. the rule javadocs)
        src = src.replaceAll("//[^\n]*", "");          // line comments
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PRODUCTION.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            if (!name.equals("options")) {             // the options block; 'parser grammar' has no colon
                names.add(name);
            }
        }
        assertTrue(names.size() > 100, "suspiciously few productions extracted (" + names.size()
                + ") — did the grammar layout change?");
        return names;
    }

    /**
     * The CLASSIFICATION column, which nothing read until 2026-09-12.
     *
     * <p>The guard above checks that the ledger names exactly the grammar's productions, and stops at the
     * first cell. Everything downstream rests on the second: the portable checker's acceptance matrix takes
     * its scope from "70 rendered, 23 plumbing, 3 entry, 2 directive — the 98 this increment owns — and 21
     * legacy, the named non-goal". Retyping one production from {@code rendered} to {@code legacy} moved a
     * production out of scope with the whole suite green, because no test read the column.
     *
     * <p>There is no artifact to derive the column FROM — which production is legacy is a judgement — so what
     * is pinned is the distribution. A deliberate reclassification updates these numbers and says why in the
     * same commit; a silent one fails here. The vocabulary is asserted too, so a typo becomes a new class
     * rather than a miscount.
     */
    @Test
    void everyProductionCarriesAKnownClassificationAndTheScopeSplitHoldsAtItsMeasuredSizes() throws IOException {
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("docs/GRAMMAR-LEDGER.md"))) {
            if (!line.startsWith("| `")) {
                continue;
            }
            String[] cells = line.split("\\|", -1);
            Matcher m = BACKTICKED_NAME.matcher(cells[1]);
            while (m.find()) {
                actual.merge(cells[2].trim(), 1, Integer::sum);
            }
        }

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("rendered", 70);
        expected.put("plumbing", 23);
        expected.put("entry", 3);
        expected.put("directive", 2);
        expected.put("legacy", 21);
        assertEquals(expected, actual,
                "docs/GRAMMAR-LEDGER.md's classification split, measured 2026-09-12 against kernel 30.8.1 and "
                        + "carried by the portable checker's acceptance matrix as its scope. 98 in scope, 21 "
                        + "the named non-goal. Changing a production's class is a scope decision — update "
                        + "these numbers and the matrix deliberately, in the commit that makes it");
    }

    private static Set<String> ledgerProductions() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(Path.of("docs/GRAMMAR-LEDGER.md"))) {
            if (!line.startsWith("| `")) {
                continue;                                       // classification/legend rows
            }
            String firstCell = line.substring(1, line.indexOf('|', 1));
            Matcher m = BACKTICKED_NAME.matcher(firstCell);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }
}
