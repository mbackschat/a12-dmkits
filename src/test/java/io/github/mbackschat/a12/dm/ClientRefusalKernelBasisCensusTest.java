package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Class guard for the standing rule that <b>a client-side refusal needs a measured kernel basis</b>: every
 * {@code MVK_*} code the kits cite while REFUSING input must be a code the kernel was actually observed to
 * emit, in a law or a claim probe.
 *
 * <p>Delegation can only under-report and is safe; a refusal we invent can be wrong, and it is invisible
 * precisely because the kernel never gets asked. Citing a code makes the refusal read as measured whether or
 * not anyone measured it, so the citation is what this guard turns into a checkable claim.
 *
 * <p><b>What "measured" means here, exactly:</b> the code token appears in the non-comment text of a law
 * file, or a claim probe recorded it on a REJECTED verdict. That is weaker than "a law ASSERTS it" — a code
 * named in law-test code without being asserted would pass — so read this guard as answering "did anyone go
 * to the kernel about this code at all", not "is this refusal's rule locked". The second question belongs to
 * the per-gate denominator tests named below.
 *
 * <p>Its reach is the citation, not the operand set behind it — a code measured on a string and then applied
 * to every other kind passes here and is caught one layer in, by the gate's own denominator test (the
 * kind gate's is {@code rulekit.dsl.TemporalRefusalDenominatorTest}, the format gate's
 * {@code rulekit.dsl.UntypedTemporalExtractionGateTest}). The division is: this one asks whether the code
 * exists at the kernel at all, a per-gate denominator test whether that gate's refusal is no broader than
 * the rows behind it. There is one of the first and one per gate of the second.
 */
class ClientRefusalKernelBasisCensusTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /**
     * Where a refusal may be authored: the kits a caller reaches before any kernel is on the classpath, plus
     * the boundary that maps a parsed AST into them.
     *
     * <p><b>`core` is here because the refusal helper and a live refusal both sit in it</b> —
     * `KernelPatternPolicy.requireAccepted` throws citing `MVK_INVALID_PATTERN` — and `interpreter-bridge`
     * because its unmappable-construct throws name the code the typed API states by erasure. Neither was
     * scanned while the list named only the three kits, so those refusals were outside the census entirely.
     *
     * <p>`cli` is deliberately NOT here: its citations are help text, diagnostic prose, and the
     * `@SuppressWarning(...)` annotation it builds — naming a code, never refusing on one.
     */
    private static final List<String> REFUSING_SOURCE_ROOTS = List.of(
            "core/src/main", "rulekit/src/main", "modelkit/src/main", "edit/src/main",
            "interpreter-bridge/src/main");

    /** Where the kernel itself answers: the two law tiers, plus the catalog's kernel-run claim probes. */
    private static final List<String> LAW_ROOTS = List.of(
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws",
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws");

    private static final String CLAIM_PROBES = "src/test/resources/catalog/operator-claim-probes.json";

    private static final Pattern KERNEL_CODE = Pattern.compile("\\bMVK_[A-Z0-9_]+\\b");

    /**
     * A TYPED citation — how a refusal names its code once that code is a {@link KernelCode} constant.
     *
     * <p>This is the denominator, and it is exact where matching the SHAPE of a refusal statement cannot be:
     * a refusal may be thrown directly, built in a helper for a caller to throw, handed to a local
     * {@code fail}, returned as a value, or carried on an enum constant, and that list has no end. Naming a
     * constant does: outside the files that INTERPRET kernel codes, referencing one is acting on it.
     */
    private static final Pattern TYPED_CITATION = Pattern.compile("\\bKernelCode\\.(MVK_[A-Z0-9_]+)");

    /**
     * Files that READ kernel codes rather than refuse on them — the enricher's fix table, the lint prose,
     * the vocabulary itself. The exclusion is TOTAL: neither scan runs on them, because whether a file
     * authors refusals is a fact about the file, not about which spelling a citation happens to use. A file
     * here that starts refusing must come off the list rather than be half-scanned. The scanner's own negative control exists to keep these out; with codes typed,
     * they are the only reason a file-scoped scan needs any exclusion at all.
     *
     * <p>Keyed by PATH, not by file name. Five roots are scanned and they already share two names
     * ({@code Annotation.java}, {@code Severity.java}), so a name-keyed exclusion would silently spread to a
     * same-named file in another module — and an exclusion is exactly where a hidden refusal would live.
     */
    private static final java.util.Set<String> INTERPRET_ONLY = java.util.Set.of(
            "core/src/main/java/io/github/mbackschat/a12/dm/core/KernelCode.java",
            "core/src/main/java/io/github/mbackschat/a12/dm/core/Refusals.java",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/StructuralKernelFix.java",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RkCode.java",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/DiagnosticEnricher.java",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RuleValidator.java",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RawLengthDiagnostic.java",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/lint/Linter.java",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/lint/ComputationLinter.java");

    /**
     * Where a LEGACY refusal statement starts — the string-literal form, still present while
     * {@code KernelCodeLiteralRatchetTest} counts it down. Its extent is found by
     * {@link #refusalStatements}, not by this pattern, because a refusal is routinely built in a helper the
     * caller throws and its own end cannot be matched by a regex at all. It goes when the ratchet reaches
     * zero, and this class keeps only the typed scan.
     */
    private static final Pattern REFUSAL_START =
            Pattern.compile("(?:throw|return)\\s+new\\s+\\w*Exception\\s*\\(");

    /**
     * Deliberate over-strictness, which the rule permits only with its reason written down — so a row states
     * that reason rather than silencing the guard. A refusal that is NOT deliberate belongs at the kernel,
     * not here.
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "MVK_LANGUAGE_DUPLICATE",
            "rulekit is stricter than the tier it runs in, on purpose. The metamodel emits this code in the "
                    + "model-as-document tier only; loading DM-JSON tolerates duplicate locales silently and "
                    + "one text wins, which the kernel confirms in validate.laws.RuleSlotLawsTest. "
                    + "LocalizedText.checkDeclared refuses the duplicate rather than letting a message go "
                    + "quietly missing (KERNEL-FINDINGS KF158; MVK-LEDGER).");

    @Test
    void everyCitedRefusalCodeWasMeasuredAtTheKernel() throws IOException {
        Map<String, java.util.Set<String>> cited = citedByRefusals();
        Set<String> measured = measuredAtTheKernel();

        List<String> unbacked = cited.keySet().stream()
                .filter(code -> !measured.contains(code))
                .filter(code -> !ALLOWLIST.containsKey(code))
                .toList();
        List<String> stale = ALLOWLIST.keySet().stream()
                .filter(code -> !cited.containsKey(code) || measured.contains(code))
                .toList();

        assertEquals(List.of(), unbacked, () -> "refusal(s) citing a kernel code nothing measured. Add the "
                + "row that makes the kernel say it — a KernelLaws reject in validate/laws, an adapter law, "
                + "or a REJECTED claim probe — or stop citing a code and delegate:\n  "
                + unbacked.stream().map(code -> code + "  " + cited.get(code)).reduce((a, b) -> a + "\n  " + b)
                        .orElse(""));
        assertEquals(List.of(), stale, () -> "stale allowlist row(s) — the refusal went away or is measured "
                + "now, so remove the row:\n  " + String.join("\n  ", stale));
    }

    /**
     * THE DERIVATION, and what replaced an arbitrary {@code >= 20} coverage floor. A code the kits name in a
     * refusal must be one the ledger admits they act on — {@code pre-checked}, or {@code advisory} for the
     * one channel that warns instead of refusing. The buckets live on {@link KernelCode} and are asserted
     * equal to the ledger's column by {@code MvkLedgerTest}, so this closes the loop: the conformance claim
     * is checked against the implementation rather than maintained by hand beside it.
     *
     * <p>The old floor was half of what the scan already saw, so a scan that silently halved still passed.
     * This has no such slack: it is a set difference, and every member of it is a real disagreement.
     *
     * <p><b>The converse is deliberately NOT asserted.</b> A bucket of {@code pre-checked} means the kits
     * refuse the SHAPE, which they may do while citing rulekit's own {@code RK_*} code (a rule with no error
     * code is {@code RK_INCOMPLETE_RULE}) or by making it unrepresentable. "Refuses the shape" is therefore
     * wider than "names this code", and equality would demand citations the kits have no reason to make.
     */
    @Test
    void everyCodeNamedInARefusalIsOneTheLedgerSaysWeActOn() throws IOException {
        Map<String, String> mismatched = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, java.util.Set<String>> cited : citedByRefusals().entrySet()) {
            KernelCode code = KernelCode.of(cited.getKey()).orElse(null);
            if (code == null) {
                mismatched.put(cited.getKey(), "not in the KernelCode vocabulary at all — " + cited.getValue());
            } else if (!code.isPreChecked() && !code.buckets().contains(KernelCode.Bucket.ADVISORY)) {
                mismatched.put(cited.getKey(), "refused at " + cited.getValue() + ", but the ledger buckets it "
                        + code.buckets() + " — a bucket that says the kernel decides, about a code we refuse on");
            }
        }
        assertEquals(Map.of(), mismatched, () -> "the ledger's conformance column disagrees with what the kits"
                + " actually refuse. Correct the bucket in docs/MVK-LEDGER.md AND on the KernelCode constant"
                + " (MvkLedgerTest keeps them equal), or stop citing the code:\n  " + mismatched);
    }

    @Test
    void theScannerRecognizesARefusalAndIgnoresWhatIsNotOne() {
        assertEquals(List.of("MVK_NO_DATE"),
                codesIn("throw new IllegalArgumentException(\"x needs a date (MVK_NO_DATE)\");"),
                "the scanner no longer matches a refusal, which would make every assertion above vacuous");
        assertEquals(List.of("MVK_X"), codesIn("throw new IllegalStateException(\n  \"a (MVK_X)\"\n  + b);"),
                "…including one wrapped across lines, which is how most of them are written");

        assertEquals(List.of("MVK_Y"),
                codesIn("return new IllegalArgumentException(\"a; b (MVK_Y)\");"),
                "…and one BUILT to be thrown by the caller, whose message carries a semicolon of its own — "
                        + "the two shapes that made a live refusal invisible to the first version of this scan");

        assertEquals(List.of(), codesIn("return Optional.of(\"MVK_NO_DATE\");"),
                "a negative control: merely naming a code is not refusing on it, or the enricher's whole "
                        + "code table would read as invented refusals");
    }

    /** The codes one snippet's refusal statements cite — the scan itself, exercised on a literal. */
    private static List<String> codesIn(String snippet) {
        List<String> codes = new java.util.ArrayList<>();
        for (int[] span : refusalStatements(snippet)) {
            Matcher found = KERNEL_CODE.matcher(snippet.substring(span[0], span[1]));
            while (found.find()) {
                codes.add(found.group());
            }
        }
        return codes;
    }

    /**
     * The extent of each refusal statement: from {@code throw}/{@code return new …Exception(} to the
     * semicolon that ends it, skipping any inside a string or char literal.
     *
     * <p>Both halves are load-bearing and both were wrong in the first version. Ending at the first {@code ;}
     * truncates a message that contains one, which hid a live refusal whose text reads {@code "…; '"} before
     * its code; and matching only {@code throw new} misses a refusal built in a helper for the caller to
     * throw. A regex can express neither, since the terminator depends on lexical state.
     */
    private static List<int[]> refusalStatements(String text) {
        List<int[]> spans = new java.util.ArrayList<>();
        Matcher start = REFUSAL_START.matcher(text);
        while (start.find()) {
            boolean inString = false;
            boolean inChar = false;
            for (int i = start.end(); i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (inString) {
                    inString = c != '"';
                } else if (inChar) {
                    inChar = c != '\'';
                } else if (c == '"') {
                    inString = true;
                } else if (c == '\'') {
                    inChar = true;
                } else if (c == ';') {
                    spans.add(new int[] {start.start(), i});
                    break;
                }
            }
        }
        return spans;
    }

    /** Each kernel code cited in a refusal, with EVERY site that cites it — a guard has to name the locus. */
    private static Map<String, java.util.Set<String>> citedByRefusals() throws IOException {
        Map<String, java.util.Set<String>> cited = new LinkedHashMap<>();
        for (String root : REFUSING_SOURCE_ROOTS) {
            for (Path source : CHECKOUT.tracked(root)) {
                String fileName = source.getFileName().toString();
                if (!fileName.endsWith(".java") && !fileName.endsWith(".kt")) {
                    continue;
                }
                String path = CHECKOUT.relative(source);
                if (INTERPRET_ONLY.contains(path)) {
                    continue;
                }
                String text = SourceText.uncommented(source);
                Matcher typed = TYPED_CITATION.matcher(text);
                while (typed.find()) {
                    cited.computeIfAbsent(typed.group(1), code -> new java.util.LinkedHashSet<>())
                            .add(path + ":" + (text.substring(0, typed.start()).split("\n", -1).length));
                }
                for (int[] span : refusalStatements(text)) {
                    Matcher codes = KERNEL_CODE.matcher(text.substring(span[0], span[1]));
                    while (codes.find()) {
                        cited.computeIfAbsent(codes.group(), code -> new java.util.LinkedHashSet<>())
                                .add(path + ":" + (text.substring(0, span[0]).split("\n", -1).length));
                    }
                }
            }
        }
        return cited;
    }

    /** Every kernel code a law asserts, or a claim probe observed on a REJECTED verdict. */
    private static Set<String> measuredAtTheKernel() throws IOException {
        Set<String> measured = new LinkedHashSet<>();
        for (String root : LAW_ROOTS) {
            for (Path law : CHECKOUT.tracked(root)) {
                Matcher codes = KERNEL_CODE.matcher(SourceText.uncommented(law));
                while (codes.find()) {
                    measured.add(codes.group());
                }
            }
        }
        JsonNode probes = new ObjectMapper().readTree(CHECKOUT.resolve(CLAIM_PROBES).toFile());
        for (JsonNode claim : probes.path("claims")) {
            for (String side : List.of("trigger", "control")) {
                JsonNode run = claim.path(side);
                if ("REJECTED".equals(run.path("verdict").asText()) && run.hasNonNull("code")) {
                    measured.add(run.get("code").asText());
                }
            }
        }
        return measured;
    }
}
