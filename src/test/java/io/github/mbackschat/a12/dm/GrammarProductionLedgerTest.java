package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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
