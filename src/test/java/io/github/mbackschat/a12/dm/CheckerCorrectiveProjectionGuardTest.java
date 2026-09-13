package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import io.github.mbackschat.a12.dm.rulekit.validate.StructuralKernelFix;

/**
 * The portable checker's corrective guidance is a PROJECTION of the Java owners, never a second copy.
 *
 * <p><b>Why a projection at all.</b> {@code StructuralKernelFix} and {@code RkCode} are the single live
 * statement of what a code MEANS and how to repair it — hand-authored, and locked by tests that trigger the
 * real kernel code and assert the hint attaches. The Kernel-free checker carries no project dependency and
 * cannot read Java source from Node, so restating that prose in Kotlin would mint the second live statement
 * the doc rules forbid. Generating it keeps one owner and makes drift a build failure.
 *
 * <p><b>The denominator is DERIVED from the consumer, not chosen.</b> The projection carries exactly the
 * codes the checker can actually emit, read out of its own sources — which is also the answer to the
 * applicable-{@code RkCode}-subset question the plan left open: a field predicate over
 * {@code Severity}/{@code DiagnosticSource} over- and under-matches in both directions (measured), while the
 * consumer's own emitted set needs no judgement and cannot go stale. A code the checker gains whose owner
 * has guidance fails this guard until the projection is regenerated.
 *
 * <p><b>Regenerate with {@code ./build.sh :dumpCheckerCorrectives}</b>, which runs this class with
 * {@code correctives.fix=true} so it writes the file instead of asserting against it — the same fixer shape
 * as {@code dumpKitApi} and {@code :cli:dumpOperatorIndex}.
 */
class CheckerCorrectiveProjectionGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String PROJECTION =
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency/KernelCorrectives.kt";

    /** The checker's own sources — the consumer whose emitted codes are the denominator. */
    private static final String CHECKER_MAIN = "consistency/src/commonMain";

    private static final Pattern EMITTED = Pattern.compile("\"((?:MVK|RK)_[A-Z0-9_]+)\"");

    /** Every A12 code the checker's own sources spell as a literal, sorted. */
    private static Set<String> emittedCodes() throws IOException {
        Set<String> codes = new TreeSet<>();
        try (var paths = Files.walk(CHECKOUT.resolve(CHECKER_MAIN))) {
            for (Path source : paths.filter(p -> p.toString().endsWith(".kt")).toList()) {
                Matcher matcher = EMITTED.matcher(Files.readString(source));
                while (matcher.find()) {
                    codes.add(matcher.group(1));
                }
            }
        }
        return codes;
    }

    /** The guidance each emitted code's owner carries, or nothing when no owner has any. */
    private static Map<String, String[]> projected() throws IOException {
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String code : emittedCodes()) {
            Optional<StructuralKernelFix.Hint> curated = StructuralKernelFix.forCode(code);
            if (curated.isPresent()) {
                out.put(code, new String[] {curated.get().fix(), curated.get().explain()});
                continue;
            }
            RkCode.byCode(code).ifPresent(rk -> out.put(code, new String[] {rk.fix(), rk.meaning()}));
        }
        return out;
    }

    @Test
    void theCheckersCorrectiveTableIsTheOwnersProjection() throws IOException {
        String rendered = render(projected());
        Path target = CHECKOUT.resolve(PROJECTION);
        if (Boolean.getBoolean("correctives.fix")) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, rendered);
            return;
        }
        assertTrue(Files.isRegularFile(target),
                () -> "the projection is missing: regenerate with `./build.sh :dumpCheckerCorrectives`");
        assertEquals(rendered, Files.readString(target),
                "the checker's corrective table is out of step with its Java owners. It is DERIVED, never "
                        + "hand-edited: regenerate with `./build.sh :dumpCheckerCorrectives` and commit the "
                        + "result alongside the change that moved it");
    }

    /**
     * The control: the scan must still find codes, and the owners must still serve some of them.
     *
     * <p>A projection guard that silently matches nothing reports a clean bill of health forever — and both
     * halves can fail independently, since the checker could stop spelling codes as literals or the owners
     * could stop being reachable.
     */
    @Test
    void theScanAndTheOwnersBothStillAnswer() throws IOException {
        int emitted = emittedCodes().size();
        int served = projected().size();
        assertTrue(emitted > 20,
                () -> "only " + emitted + " codes found in the checker's sources — the scan has stopped "
                        + "matching, and the projection above is vacuous until it does again");
        assertFalse(StructuralKernelFix.codes().isEmpty(), "the curated owner serves no code at all");
        assertTrue(served >= 2,
                () -> "the owners serve guidance for only " + served + " of the checker's codes, which is "
                        + "fewer than the two this guard was written against");
    }

    /** The generated file, byte for byte. Keys are sorted, so the output is a function of the owners alone. */
    private static String render(Map<String, String[]> guidance) {
        StringBuilder out = new StringBuilder();
        out.append("package io.github.mbackschat.a12.dm.consistency\n\n");
        out.append("/**\n");
        out.append(" * GENERATED — do not edit. Regenerate with `./build.sh :dumpCheckerCorrectives`.\n");
        out.append(" *\n");
        out.append(" * A12's own guidance for the codes this checker emits, projected from the single Java\n");
        out.append(" * owners — `rulekit.validate.StructuralKernelFix` for the curated structural correctives\n");
        out.append(" * and `rulekit.validate.RkCode` for the `RK_*` catalog. The projection carries exactly the\n");
        out.append(" * codes the checker's own sources spell, so it cannot advertise guidance for a code that\n");
        out.append(" * never reaches a consumer; `CheckerCorrectiveProjectionGuardTest` re-derives it and fails\n");
        out.append(" * the build on any drift.\n");
        out.append(" */\n");
        out.append("internal val KERNEL_CORRECTIVES: Map<String, Corrective> = mapOf(\n");
        guidance.forEach((code, texts) -> {
            out.append("    \"").append(code).append("\" to Corrective(\n");
            out.append("        fix = \"").append(escape(texts[0])).append("\",\n");
            out.append("        meaning = \"").append(escape(texts[1])).append("\",\n");
            out.append("    ),\n");
        });
        out.append(")\n");
        return out.toString();
    }

    /** Kotlin string-literal escaping: the backslash, the quote, and `$`, which Kotlin would interpolate. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "");
    }
}
