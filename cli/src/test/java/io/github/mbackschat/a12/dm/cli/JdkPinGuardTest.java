package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * <b>Every CI JDK is pinned to an exact patch version.</b> {@code actions/setup-java} resolves a bare major
 * ({@code 21}) to whichever {@code 21.0.x} the runner image happens to cache, so the version silently follows the
 * provider's image refresh rather than the project's choice.
 *
 * <p><b>The cost is measured, not hypothetical.</b> That drift moved the hosted runners from {@code 21.0.11} to
 * {@code 21.0.12} in mid-August 2026 — both appeared on the same day, for the same commit. The committed
 * performance record keys its trend series on {@code jdk}, so one provider-side image bump split the hosted series
 * into a 5-run and a 2-run segment, each below the six-run threshold a step claim needs. A trend instrument that
 * cannot make a claim is the same class of loss as a bar that cannot fail.
 *
 * <p><b>Why pinning and not tolerating.</b> A JDK patch version on hosted infrastructure is a lottery draw rather
 * than a platform choice, and the fix belongs at the source: one declared version, bumped by a reviewable edit.
 * That is the rule the performance anchor already follows — committed rather than rolling, so successive
 * within-tolerance drifts cannot compound into one nobody chose. {@code 21.0.12} is also what every local run
 * under {@code perf-results/} was measured on, so pinning it makes the two estates comparable instead of merely
 * adjacent.
 *
 * <p><b>Scope.</b> The workflow files only — this cannot see a JDK a runner image provides by default, nor a
 * toolchain Gradle resolves locally, so it bounds the problem from one side. What it does close is the one
 * mechanism observed to drift.
 *
 * <p>Rides {@code docGuardTest}, whose input set declares the workflow tree.
 */
class JdkPinGuardTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");

    /** A version entry under a {@code java-version:} block list. */
    private static final Pattern JAVA_VERSION_BLOCK =
            Pattern.compile("java-version:\\s*\\|\\s*\\n((?:\\s*[0-9][^\\n]*\\n)+)");

    /** The inline form, {@code java-version: 21}. */
    private static final Pattern JAVA_VERSION_INLINE =
            Pattern.compile("java-version:[ \\t]*([0-9][^\\n#]*)");

    /** A pinned version names at least major.minor.patch. A bare major, or major.minor, is a moving target. */
    private static final Pattern PINNED = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private static final int MIN_DECLARATIONS = 2;

    @Test
    void everyWorkflowJdkNamesAnExactPatchVersion() throws IOException {
        List<String> declarations = new ArrayList<>();
        List<String> unpinned = new ArrayList<>();

        for (Path workflow : workflows()) {
            audit(workflow.getFileName().toString(), Files.readString(workflow), declarations, unpinned);
        }

        int found = declarations.size();
        assertTrue(found >= MIN_DECLARATIONS,
                () -> "parsed only " + found + " `java-version` declarations across " + WORKFLOWS + " (floor "
                        + MIN_DECLARATIONS + ") — the workflow tree moved or the block shape changed, and this "
                        + "guard is checking nothing");

        assertTrue(unpinned.isEmpty(), () -> "CI JDK version(s) that are not pinned to an exact patch. "
                + "`setup-java` resolves a bare major to whichever patch the runner image caches, so the version "
                + "follows the provider rather than this project — which already split the hosted performance "
                + "series across two JDKs and cost it the run count a step claim needs. Name the full version "
                + "(e.g. `21.0.12`) and bump it in a reviewable edit:\n  " + String.join("\n  ", unpinned));
    }

    /** Does-fire proof for the rule, over fabricated workflow text rather than the live tree. */
    @Test
    void theRuleFiresOnABareMajorInEitherFormAndAcceptsAnExactPatch() {
        List<String> found = new ArrayList<>();
        List<String> bad = new ArrayList<>();

        audit("block.yml", "        with:\n          java-version: |\n            21\n            25\n", found, bad);
        assertEquals(2, bad.size(), () -> "both bare majors in a block list must fire, got " + bad);

        bad.clear();
        found.clear();
        audit("pinned.yml", "          java-version: |\n            21.0.12\n            25.0.4\n", found, bad);
        assertTrue(bad.isEmpty(), () -> "exact patch versions must not fire, got " + bad);
        assertEquals(2, found.size(), "both pinned versions must still be COUNTED toward the floor");

        bad.clear();
        found.clear();
        audit("inline.yml", "          java-version: 21\n", found, bad);
        assertEquals(1, bad.size(), () -> "the inline form must fire too, got " + bad);

        bad.clear();
        found.clear();
        audit("major-minor.yml", "          java-version: 21.0\n", found, bad);
        assertEquals(1, bad.size(),
                () -> "major.minor is still a moving target — 21.0 resolves to any 21.0.x, got " + bad);
    }

    /** Collect every declared version and those that are not an exact patch. */
    private static void audit(String name, String yaml, List<String> declarations, List<String> unpinned) {
        Matcher block = JAVA_VERSION_BLOCK.matcher(yaml);
        boolean sawBlock = false;
        while (block.find()) {
            sawBlock = true;
            for (String line : block.group(1).split("\n")) {
                record(name, line.trim(), declarations, unpinned);
            }
        }
        if (sawBlock) {
            return;
        }
        Matcher inline = JAVA_VERSION_INLINE.matcher(yaml);
        while (inline.find()) {
            record(name, inline.group(1).trim(), declarations, unpinned);
        }
    }

    private static void record(String file, String version, List<String> declarations, List<String> unpinned) {
        if (version.isEmpty()) {
            return;
        }
        declarations.add(file + " → " + version);
        if (!PINNED.matcher(version).matches()) {
            unpinned.add(file + " → `" + version + "` is not an exact patch version");
        }
    }

    private static List<Path> workflows() throws IOException {
        Path dir = Files.isDirectory(WORKFLOWS) ? WORKFLOWS : Path.of(".github/workflows");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yml")
                    || p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }
}
