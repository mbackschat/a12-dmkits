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
 * (41 in {@code validate/laws}, 209 in {@code adapter/laws}) under nine different names — {@code render},
 * {@code verdict}, {@code cond}, {@code guarded}, {@code typedVerdict}, … That duplication is the evidence:
 * the authors wanted the typed path and the harness made them re-implement it. Both harnesses now publish a
 * {@code Condition} overload, so the typed spelling is the SHORTER one and a new file has no reason to
 * hand-roll a renderer.
 *
 * <p><b>Why a ratchet and not a ban.</b> The 250 existing files are not defects to be rewritten in one sweep;
 * they are the backlog this makes visible and monotone. The ceiling may only FALL, so a new hand-rolled
 * renderer fails immediately while a migration is free. A String condition itself stays legitimate for two
 * reasons — the typed surface ERASES the shape (a wrong-kind operand the kit refuses because the kernel does,
 * where the row measures that refusal), or the law's subject is concrete SYNTAX with no node (a brace depth,
 * a malformed token, a literal form) — and neither reason needs a private renderer, so neither is affected.
 */
class TypedConditionAuthoringRatchetTest {

    /**
     * Measured 2026-09-22 and exact — no slack, so a single new offender fails. It may only FALL: lower it in
     * the same commit that migrates a file, and never raise it, because the shared overloads mean a new law
     * has no reason to add one.
     */
    private static final int CEILING = 248;

    /**
     * The two shared harnesses live inside the scanned trees and legitimately BUILD the renderer — they are
     * the entry point everything else should call. Counting them would put two files in the backlog that can
     * never leave it, and a ceiling whose denominator includes its own fix means something other than it says.
     */
    private static final List<String> THE_HARNESSES_THEMSELVES =
            List.of("KernelLaws.java", "RuntimeLaws.java");

    /** A renderer built by the test itself, in either language. A qualified mention in prose is not one. */
    private static final Pattern OWN_RENDERER = Pattern.compile("(?<![A-Za-z.])(new\\s+)?DslRenderer\\s*\\(");

    private static final List<String> LAW_TREES = List.of(
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws",
            "adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws",
            "adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws");

    @Test
    void noLawTestBuildsItsOwnConditionRenderer() {
        Checkout checkout = Checkout.locate();
        List<Path> offenders = LAW_TREES.stream()
                .map(checkout::resolve)
                .filter(Files::isDirectory)
                .flatMap(TypedConditionAuthoringRatchetTest::sourcesUnder)
                .filter(p -> !THE_HARNESSES_THEMSELVES.contains(p.getFileName().toString()))
                .filter(p -> OWN_RENDERER.matcher(read(p)).find())
                .toList();

        assertTrue(offenders.size() <= CEILING, () -> "law tests building their own DslRenderer rose to "
                + offenders.size() + ", ceiling " + CEILING + ". Author through the shared typed entry point"
                + " — KernelLaws.verdictModel(json, group, errorField, Condition) or"
                + " RuntimeLaws.withRule(json, group, name, errorRel, code, Condition) — which renders"
                + " relative to the rule's group for you. Newly over the ceiling:\n  "
                + offenders.stream().map(checkout::relative).collect(java.util.stream.Collectors.joining("\n  ")));
    }

    private static java.util.stream.Stream<Path> sourcesUnder(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
