package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCode;

/**
 * A RATCHET on {@code MVK_*} STRING LITERALS in the kits' main source. Each one is a kernel code spelled as
 * prose, which costs two things the typed {@link KernelCode} gives back: the compiler cannot check it, so a
 * typo is a code that silently never matches anything; and the set of codes we refuse on cannot be
 * enumerated, because "is this citation a refusal?" is undecidable by pattern while the ways to write a
 * refusal are open-ended.
 *
 * <p>This guard exists so the migration onto the enum can be incremental and still be enforced from the
 * first file: the count per file may go DOWN and never up, and a file absent from the ledger below may carry
 * none at all.
 *
 * <p><b>The downward half is what keeps it honest.</b> A stale allowance is indistinguishable from a real
 * one, so a file that improves must lower its number in the same change — the guard says which, and by how
 * much. When every entry reaches zero, this class goes away, along with the legacy statement scan that
 * {@code ClientRefusalKernelBasisCensusTest} still needs while literals remain.
 */
class KernelCodeLiteralRatchetTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Where a kernel code may be authored: the kits a caller reaches before any kernel is on the classpath. */
    private static final List<String> KIT_SOURCE_ROOTS =
            List.of("core/src/main", "modelkit/src/main", "rulekit/src/main", "edit/src/main");

    /** A {@code MVK_} code, wherever it appears — applied only to STRING-LITERAL content. */
    private static final Pattern CODE = Pattern.compile("\\bMVK_[A-Z0-9_]+");

    /**
     * The remaining literals, per file. Seeded from a measurement, and every entry is a debt: lower it as
     * each file moves to {@link KernelCode}, and delete the entry at zero.
     *
     * <p>Re-seeded 2026-09-20 against this tree. The numbers arrived with the migration from a branch that
     * had not seen every hint this repository carries, so {@code StructuralKernelFix}'s allowance was short
     * by the codes its newer entries name in prose — an allowance measured on a different estate is not a
     * ratchet, it is a guess that happens to fail. It ratchets downward from here.
     *
     * <p><b>{@code LocalizedText} is the one entry that is not simply unfinished, so its reason is written
     * here rather than left to look like debt.</b> Its two refusals are thrown from a builder's
     * {@code build()} as {@code IllegalStateException} — the type this estate uses for a builder in an
     * inconsistent state, which {@code rulekit.dsl.RuleAuthoringTest} asserts — while
     * {@code Refusals.of} returns an {@code IllegalArgumentException}. Routing them through it would change
     * which exception a caller catches, for a gain that is only in how the code is spelled, so it wants its
     * own decision about the right type and not a rider on a mechanical sweep. The other entries are
     * {@code INTERPRET_ONLY} files in the census's sense: they describe codes rather than refuse on them.
     */
    private static final Map<String, Integer> REMAINING = Map.ofEntries(
            Map.entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/LocalizedText.java", 2),
            Map.entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/lint/ComputationLinter.java", 1),
            Map.entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/DiagnosticEnricher.java", 1),
            Map.entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RawLengthDiagnostic.java", 1),
            Map.entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RkCode.java", 2),
            Map.entry("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/StructuralKernelFix.java", 21)
    );

    @Test
    void noFileCarriesMoreKernelCodeLiteralsThanItsAllowance() throws IOException {
        Map<String, String> offenders = new LinkedHashMap<>();
        Map<String, Integer> actual = countPerFile();
        for (Map.Entry<String, Integer> file : actual.entrySet()) {
            int allowed = REMAINING.getOrDefault(file.getKey(), 0);
            if (file.getValue() > allowed) {
                offenders.put(file.getKey(), "has " + file.getValue() + " MVK_ string literal(s), allowed "
                        + allowed + " — use KernelCode instead; the enum is the vocabulary");
            }
        }
        assertEquals(Map.of(), offenders,
                () -> "new MVK_ string literal(s). A kernel code is a KernelCode constant, not prose:\n  "
                        + offenders);
    }

    /** The ratchet's teeth: an allowance above what the file actually carries must be lowered, not kept. */
    @Test
    void everyAllowanceIsFullyUsed() throws IOException {
        Map<String, String> stale = new LinkedHashMap<>();
        Map<String, Integer> actual = countPerFile();
        for (Map.Entry<String, Integer> allowance : REMAINING.entrySet()) {
            int found = actual.getOrDefault(allowance.getKey(), 0);
            if (found < allowance.getValue()) {
                stale.put(allowance.getKey(), "allowance " + allowance.getValue() + ", actual " + found
                        + " — lower it to " + found + (found == 0 ? " (delete the entry)" : ""));
            }
        }
        assertEquals(Map.of(), stale, () -> "the ratchet slipped: an allowance nothing uses is "
                + "indistinguishable from a real one, so lower it in the change that earned it:\n  " + stale);
    }

    /** Liveness: the scan must actually see the estate, or both assertions above are vacuous. */
    @Test
    void theScanSeesTheKitsAndRecognisesTheShape() throws IOException {
        int total = countPerFile().values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(total >= 1, "no MVK_ literal found anywhere — if the migration is complete, delete this "
                + "class and the census's regex scanner with it; otherwise the scan has stopped matching");
        assertEquals(1, matches("throw new IllegalArgumentException(\"x (MVK_NO_DATE)\");"),
                "the scan no longer recognises a code in a message");
        assertEquals(0, matches("return KernelCode.MVK_NO_DATE;"),
                "a negative control: the TYPED form is the goal, and must never be counted as debt");
        assertEquals(0, matches("Refusals.of(KernelCode.MVK_NO_DATE, \"a\" + x + \"b\");"),
                "…including between two literals, where a naive pattern starts at the CLOSING quote of the"
                        + " first and swallows the typed constant on its way to the second");
        assertEquals(0, matches("// MVK_NO_DATE in a comment"), "comments are prose, not code");
        assertEquals(1, matches("char q = '\"'; String s = \"x (MVK_NO_DATE)\";"),
                "a CHAR literal holding a quote must not desynchronise the scan — it does occur in this estate");
        assertEquals(1, matches("String s = \"\"\"\n  a (MVK_NO_DATE)\n  \"\"\";"),
                "a text block is one literal, not two empty ones plus whatever follows");
        assertEquals(1, matches("String s = \"see http://x for (MVK_NO_DATE)\";"),
                "a // INSIDE a literal must not truncate it — which stripping comments beforehand did");
        assertEquals(1, matches("String s = \"don't (MVK_NO_DATE)\";"),
                "an apostrophe INSIDE a literal is text, not the start of a char literal");
        assertEquals(1, matches("char c = '\\''; String s = \"x (MVK_NO_DATE)\";"),
                "an escaped quote inside a char literal must not end it early");
        assertEquals(1, matches("String s = \"a\\\\\"; String t = \"(MVK_NO_DATE)\";"),
                "a literal ending in an escaped backslash closes where it closes — the \\-skip must not"
                        + " jump over the terminator");
        assertEquals(1, matches("int n = a / b; String s = \"(MVK_NO_DATE)\";"),
                "a division slash is not a comment opener");
        assertEquals(1, matches("/* (MVK_NO_DATE) */ String s = \"(MVK_NO_NUMBER)\";"),
                "a code in a BLOCK comment is prose; the literal after it still counts");
        assertEquals(1, matches("String s = \"unterminated (MVK_NO_DATE)"),
                "an unterminated literal at end of input terminates the scan rather than looping");
    }

    /**
     * String literals must be TOKENISED, not matched, and the tokeniser has to know every construct that can
     * hold a quote — otherwise it desynchronises and every count after that point in the file is wrong in an
     * unpredictable direction.
     *
     * <p>Three constructs do, and all three are reachable in this estate: a CHAR literal ({@code '"'}), a TEXT
     * BLOCK, and a comment. Comments are skipped here rather than stripped beforehand, because stripping
     * {@code //} without knowing whether it sits inside a string truncates any literal containing one.
     *
     * <p>A regex cannot express this: {@code "[^"]*MVK_…} can begin at a literal's CLOSING quote and run
     * through the code between two literals to the next opening one, so {@code Refusals.of(KernelCode.MVK_X,
     * "…")} — the typed form this guard exists to reward — counts as debt.
     */
    private static int matches(String source) {
        int count = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    while (i < source.length() && source.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    int close = source.indexOf("*/", i + 2);
                    if (close < 0) {
                        break;
                    }
                    i = close + 1;
                    continue;
                }
            }
            if (c == '\'') {
                i++;
                while (i < source.length() && source.charAt(i) != '\'') {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                continue;
            }
            if (c != '"') {
                continue;
            }
            String delimiter = source.startsWith("\"\"\"", i) ? "\"\"\"" : "\"";
            int start = i + delimiter.length();
            int end = start;
            while (end < source.length() && !source.startsWith(delimiter, end)) {
                end += source.charAt(end) == '\\' ? 2 : 1;
            }
            if (CODE.matcher(source.substring(start, Math.min(end, source.length()))).find()) {
                count++;
            }
            i = Math.min(end + delimiter.length() - 1, source.length() - 1);
        }
        return count;
    }

    private static Map<String, Integer> countPerFile() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String root : KIT_SOURCE_ROOTS) {
            Path base = CHECKOUT.resolve(root);
            for (Path file : CHECKOUT.tracked(root)) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                    continue;
                }
                int n = matches(Files.readString(file));
                if (n > 0) {
                    counts.put(root + "/" + base.relativize(file), n);
                }
            }
        }
        return counts;
    }

}
