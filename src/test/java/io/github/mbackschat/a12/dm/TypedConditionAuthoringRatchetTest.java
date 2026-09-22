package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A law test authors its condition through the shared TYPED entry point, not a renderer of its own.
 *
 * <p><b>The defect this locks, root-caused 2026-09-22.</b> CLAUDE.md's dogfood rule already required it —
 * "tests … MUST author through the typed surface a real user would reach for, never reach <em>around</em> it
 * for low-level strings" — and the estate drifted anyway. The cause was not discipline but AFFORDANCE: both
 * shared harnesses published only a String-condition entry point ({@code KernelLaws.verdictModel(…, String)},
 * {@code RuntimeLaws.withRule(…, String)}), so the preferred spelling cost a private
 * {@code new DslRenderer(…)} helper at every site while the discouraged one was a one-liner.
 *
 * <p><b>The measurement that identified it rather than merely describing it.</b> If prose were being ignored,
 * nobody would pay to author typed. Instead <b>250 law files had each built that helper independently</b>
 * (2026-09-22: 41 under {@code validate/laws}, 31 + 178 under {@code adapter/laws}; the quantity is FILES
 * whose code constructs a {@code DslRenderer}, comments stripped, the two harnesses excluded — which is
 * exactly what {@link #CEILING} counts, so the figure and the guard cannot drift apart). That duplication is
 * the evidence: the authors wanted the typed path and the harness made them re-implement it. Both harnesses
 * now publish a {@code Condition} overload, so the typed spelling is the SHORTER one and a new file has no
 * reason to hand-roll a renderer.
 *
 * <p>Earlier revisions of this javadoc also counted the distinct names those private helpers were given. That
 * figure is deliberately gone: no guard can check it, two attempts to derive it disagreed by a factor of two
 * because each counted a different thing, and the claim the paragraph needs — the same helper re-implemented
 * independently, over and over — rests on the file count, which is enforced.
 *
 * <p><b>Why a ratchet and not a ban.</b> Those 250 files are not defects to be rewritten in one sweep; they are
 * the backlog this makes visible and monotone. The ceiling may only FALL, so a new hand-rolled renderer fails
 * immediately while a migration is free. A String condition itself stays legitimate for two reasons — the typed
 * surface ERASES the shape (a wrong-kind operand the kit refuses because the kernel does, where the row
 * measures that refusal), or the law's subject is concrete SYNTAX with no node (a brace depth, a malformed
 * token, a literal form) — and neither reason needs a private renderer, so neither is affected.
 */
class TypedConditionAuthoringRatchetTest {

    /**
     * Measured 2026-09-22 and exact — no slack, so a single new offender fails. It may only FALL: lower it in
     * the same commit that migrates a file, and never raise it, because the shared overloads mean a new law
     * has no reason to add one.
     *
     * <p><b>It read 248 for one day, and that was a scanner defect rather than a migration.</b> The first
     * pattern could not see a fully qualified {@code io.github…dsl.DslRenderer(}, so two files constructing
     * one that way were invisible — {@code CrossKindTemporalExtremumDiffTest} and
     * {@code DateRangeOverlapAdmissionLawsTest}. A ceiling is a denominator, and a denominator measured with
     * the same blind spot as the guard that enforces it agrees with itself and with nothing else.
     */
    private static final int CEILING = 250;

    /**
     * The two shared harnesses live inside the scanned trees and legitimately BUILD the renderer — they are
     * the entry point everything else should call. Counting them would put two files in the backlog that can
     * never leave it, and a ceiling whose denominator includes its own fix means something other than it says.
     *
     * <p>Matched on the path inside the tree, not the bare file name: a same-named file arriving in another
     * law tree would otherwise exempt itself silently, and the exemption must name the file it means.
     */
    private static final List<String> THE_HARNESSES_THEMSELVES = List.of(
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/KernelLaws.java",
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws/RuntimeLaws.java");

    /**
     * A renderer CONSTRUCTED by the test itself, in either language and at any qualification.
     *
     * <p><b>Both halves were wrong before 2026-09-22 and a planted mutant survived.</b> The pattern excluded a
     * preceding {@code .}, which silently exempted every FULLY QUALIFIED spelling — including the one
     * {@code RuntimeLaws} itself uses, so the harness matched only on its own javadoc and its exemption was
     * load-bearing against prose. And it matched inside comments, so a mention in a sentence counted as a
     * construction. Now: comments are stripped first, and the name may carry a package qualifier.
     *
     * <p>Two evasions remain out of reach of any regex and are accepted deliberately: a Kotlin {@code import
     * … as Alias}, and a factory that returns a renderer built elsewhere. Neither is reachable without going
     * out of one's way, and the cost of chasing them is a parser.
     */
    private static final Pattern OWN_RENDERER =
            Pattern.compile("(?<![A-Za-z])(new\\s+)?([A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)*DslRenderer\\s*\\(");

    /** Line and block comments, plus KDoc/Javadoc — stripped before the scan, never counted as code. */
    private static final Pattern COMMENTS =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final List<String> LAW_TREES = List.of(
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws",
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws",
            "adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws");

    @Test
    void noLawTestBuildsItsOwnConditionRenderer() {
        Checkout checkout = Checkout.locate();
        List<Path> harnesses = THE_HARNESSES_THEMSELVES.stream().map(checkout::resolve).toList();
        // The denominator is what git TRACKS, never a walk of the working directory: with an exact ceiling, a
        // stray untracked file would fail the build for something nobody wrote, and CI would disagree with a
        // local run. Checkout.java states the rule and owns the enumeration.
        List<Path> offenders = LAW_TREES.stream()
                .flatMap(tree -> checkout.tracked(tree).stream())
                .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                .filter(p -> !harnesses.contains(p))
                .filter(p -> OWN_RENDERER.matcher(code(p)).find())
                .toList();

        assertTrue(offenders.size() <= CEILING, () -> "law tests building their own DslRenderer rose to "
                + offenders.size() + ", ceiling " + CEILING + ". Author through the shared typed entry point"
                + " — KernelLaws.verdictModel(json, group, errorField, Condition) or"
                + " RuntimeLaws.withRule(json, group, name, errorRel, code, Condition) — which renders"
                + " relative to the rule's group for you. Every file that builds one, so diff this against the"
                + " ceiling rather than reading it as the new offenders:\n  "
                + offenders.stream().map(checkout::relative).collect(java.util.stream.Collectors.joining("\n  ")));
    }

    /** The file's CODE — comments removed, so a renderer named in a sentence is not a renderer built. */
    private static String code(Path path) {
        try {
            return COMMENTS.matcher(Files.readString(path)).replaceAll(" ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
