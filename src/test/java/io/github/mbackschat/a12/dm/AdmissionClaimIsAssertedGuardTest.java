package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A differential that CLAIMS the kernel ADMITS the model it executes must assert that claim. The claim may not
 * ride on {@code BoundModel.ofJson}, and the reason is a sequencing trap worth stating precisely.
 *
 * <p><b>The shape, measured 2026-08-19.</b> {@code DateRangeFirstFilledProfileDiffTest}'s KDoc said all eight
 * declaration profiles "pass {@code checkConsistency}", and two findings docs repeated it, while no line of that
 * test ran the gate. Nothing was red, because <em>a missing precondition cannot fail</em> — which is what makes
 * this class invisible to a review that reads assertions. The file did call {@code BoundModel.ofJson}, which
 * genuinely gates ({@code ModelLoader.loadAndCheck}: deserialize → expand → {@code checkConsistency}, throwing on
 * ERROR) — but it gates the BASE, and {@code RuntimeLaws.withRule} / {@code withComputation} /
 * {@code withTieredComputation} splice the artifact under test in AFTERWARDS, as raw JSON text. So the executed
 * model is never the gated one, and the author sees a gate call in their own code.
 *
 * <p><b>Why a source scan rather than a gate in the harness.</b> Gating every splice was implemented and
 * measured: it fired on exactly the seven tests whose SUBJECT is a refusal and on nothing else across 2,776
 * tests — so the estate's models really are admitted — but it cost <b>+23 min</b> on {@code :adapter:test}
 * (5 min → 28 min), because the MVK gate re-walks the whole model on every call and the lane builds well over a
 * thousand distinct models. A permanent 5× on the main lane buys nothing that this scan does not, at zero
 * runtime cost. Detail: TESTING-SPEC "the admission gate".
 */
class AdmissionClaimIsAssertedGuardTest {

    /** The raw-JSON splice helpers: everything they insert lands downstream of any gate that already ran. */
    private static final Pattern SPLICE =
            Pattern.compile("with(Rule|RuleMessages|Computation|TieredComputation)\\s*\\(");

    /** Prose that claims kernel ADMISSION of the model under test (as opposed to naming a value's validity). */
    private static final Pattern CLAIM = Pattern.compile(
            "checkConsistency|kernel-admitted|passes the MVK gate|the kernel admits th(is|e) (model|computation)");

    /**
     * A route that actually runs the gate ON THE VALUE IT IS GIVEN. {@code BoundModel.ofJson} is deliberately
     * ABSENT: it gates, but in the splice idiom it gates the base rather than the executed model, so accepting it
     * here would re-admit the exact defect this guard exists to catch.
     */
    private static final Pattern ROUTE =
            Pattern.compile("consistencyMessages\\s*\\(|RuleValidator|diagnose\\s*\\(|loadAndCheck");

    @Test
    void everySpliceUsingTestThatClaimsTheGateAlsoAssertsIt() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path source : testSources()) {
            String text = Files.readString(source);
            if (!SPLICE.matcher(text).find()) {
                continue;
            }
            scanned++;
            if (claimsTheGateWithoutAssertingIt(text)) {
                offenders.add(repoRoot().relativize(source).toString());
            }
        }
        assertTrue(scanned > 200, "the scan found only " + scanned + " splice-using test sources — it is not "
                + "reaching the estate, so its green means nothing");
        assertEquals(List.of(), offenders, "these tests claim the kernel admits the model they execute but never "
                + "run the gate on it. Assert it (RuntimeLaws.consistencyMessages(model) is empty for an admitted "
                + "model) or drop the claim; BoundModel.ofJson does NOT back it, because the splice happens after "
                + "that gate. See TESTING-SPEC \"the admission gate\"");
    }

    /**
     * The positive control lives on the SCANNER, not on the tree: asserting that a violation still exists would
     * turn a clean estate into a failure and pressure the next author to delete the guard (TESTING-SPEC). This
     * feeds it the pre-fix shape verbatim instead.
     */
    @Test
    void theScannerRecognisesTheStaleFormItExistsToCatch() {
        String staleShape = """
                /** … across all eight pairs, all of which pass `checkConsistency` and all of which compute. */
                class X {
                    val m = BoundModel.ofJson(base)
                    val json = RuntimeLaws.withComputation(base, ROOT, "C", "../F", operation)
                }
                """;
        assertTrue(claimsTheGateWithoutAssertingIt(staleShape),
                "the scanner no longer recognises the unbacked-claim shape it was written for");
        assertFalse(claimsTheGateWithoutAssertingIt(
                staleShape.replace("val m =", "RuntimeLaws.consistencyMessages(json); val m =")),
                "a test that DOES run the gate must not be reported");
        assertFalse(claimsTheGateWithoutAssertingIt(staleShape.replace("`checkConsistency`", "both strategies")),
                "a splice without an admission claim is not this guard's business");
    }

    /** True when this source both CLAIMS the gate in prose and never runs it in code. */
    private static boolean claimsTheGateWithoutAssertingIt(String source) {
        return SPLICE.matcher(source).find()
                && CLAIM.matcher(commentsOf(source)).find()
                && !ROUTE.matcher(codeOf(source)).find();
    }

    /** Comment lines only — a claim is prose, and the code half must not satisfy the prose trigger. */
    private static String commentsOf(String source) {
        return source.lines()
                .filter(line -> line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")
                        || line.stripLeading().startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /** Code only — block comments and line-comment tails removed, so prose cannot pose as a gate call. */
    private static String codeOf(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
    }

    /**
     * The adapter test source set, and only it: {@code RuntimeLaws} lives there, so no source outside that set can
     * reach a splice helper at all. Scoping the walk this way also keeps the guard cheap (a repo-wide read of every
     * {@code src/test} source cost 6.1 s of a lane the estate runs often) and keeps a same-named helper in another
     * module out of the denominator, where it would be noise rather than coverage.
     */
    private static List<Path> testSources() throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot().resolve("adapter/src/test"))) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String s = p.toString();
                        return (s.endsWith(".java") || s.endsWith(".kt")) && !s.contains("/build/");
                    })
                    .toList();
        } catch (UncheckedIOException e) {
            throw new IOException(e);
        }
    }

    private static Path repoRoot() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot locate the repository root from " + Path.of("").toAbsolutePath());
    }
}
