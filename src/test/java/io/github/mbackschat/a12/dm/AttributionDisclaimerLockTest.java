package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Locks the attribution disclaimers on every surface a reader can reach.
 *
 * <p>These are legal statements, not prose: the project is an independent build against the public A12 Kernel
 * and must say so wherever it is read. A reword that drops the statement is indistinguishable from an edit
 * that keeps it, so the removal has to break the build rather than depend on someone noticing in review.
 *
 * <p>Three near neighbours are deliberately absent. The assembled release mirror is asserted by
 * {@code InterpreterReleaseMirrorAssemblerTest}; the interpreter CHANGELOG entry is history rather than a
 * live surface; and {@code docs/LICENSING.md}'s "No &quot;mgm A12&quot; name/logo" is a licensing constraint
 * on branding, not a statement of independence, so locking it here would have mislabelled what this guard
 * protects.
 */
class AttributionDisclaimerLockTest {

    private record Disclaimer(String path, List<String> requiredPhrases) {}

    /** Every live surface, with the exact wording it must keep. */
    private static final List<Disclaimer> REQUIRED = List.of(
            new Disclaimer("README.md",
                    List.of("Not an official A12 artifact", "not affiliated with or endorsed by mgm")),
            new Disclaimer("core/src/main/java/io/github/mbackschat/a12/dm/package-info.java",
                    List.of("not affiliated with or endorsed by mgm")),
            new Disclaimer("docs/PROJECT-DESIGN.md",
                    List.of("Not an official A12 artifact", "not affiliated with or endorsed by mgm")),
            new Disclaimer("docs/INTERPRETER-RELEASE-SPEC.md",
                    List.of("not an official A12 or mgm artifact")),
            new Disclaimer("interpreter/public-release/DISCLAIMER.md",
                    List.of("not an official A12 or mgm product")),
            new Disclaimer("interpreter/public-release/README.md",
                    List.of("not an official A12 or mgm artifact")),
            new Disclaimer("interpreter/public-release/index.html",
                    List.of("Not affiliated with, endorsed by, or supported by mgm")),
            new Disclaimer("interpreter/consumer-tests/browser/index.html",
                    List.of("Not affiliated with, endorsed by, or supported by mgm technology partners")),
            new Disclaimer("plugin/README.md",
                    List.of("Not an official A12 / mgm artifact")),
            new Disclaimer("plugin-codex/AGENTS.md",
                    List.of("Not an official A12 / mgm artifact")),
            new Disclaimer("scripts/assemble-release-repo.sh",
                    List.of("Not an official mgm / A12 artifact")));

    @Test
    void everyPublishedSurfaceStillCarriesItsAttributionDisclaimer() throws IOException {
        Path repositoryRoot = repositoryRoot();
        var violations = new ArrayList<String>();

        for (Disclaimer disclaimer : REQUIRED) {
            Path file = repositoryRoot.resolve(disclaimer.path());
            if (!Files.isRegularFile(file)) {
                violations.add(disclaimer.path() + ": the file itself is gone");
                continue;
            }
            missingPhrases(Files.readString(file), disclaimer)
                    .forEach(phrase -> violations.add(disclaimer.path() + ": missing \"" + phrase + "\""));
        }

        assertTrue(violations.isEmpty(),
                "attribution disclaimers were removed or reworded — restore the exact wording:\n"
                        + String.join("\n", violations));
    }

    /**
     * The mechanism's positive control. Without it the guard above could pass because the phrase check never
     * reports anything, which is indistinguishable from every disclaimer being present.
     */
    @Test
    void theLockReportsAPhraseThatIsNoLongerThere() {
        var sample = new Disclaimer("sample.md", List.of("not affiliated with or endorsed by mgm"));

        assertEquals(List.of("not affiliated with or endorsed by mgm"),
                missingPhrases("A personal exploration built against the public A12 Kernel.", sample),
                "a surface that dropped the disclaimer must be reported");
        assertTrue(missingPhrases("Independent; not affiliated with or endorsed by mgm.", sample).isEmpty(),
                "a surface that kept the disclaimer must not be reported");
    }

    @Test
    void theLockCoversEverySurfaceAndNamesAPhraseForEach() {
        assertFalse(REQUIRED.isEmpty(), "an empty lock list would make the guard vacuous");
        REQUIRED.forEach(disclaimer -> {
            assertFalse(disclaimer.requiredPhrases().isEmpty(),
                    disclaimer.path() + " is locked without naming any phrase, so it asserts nothing");
            disclaimer.requiredPhrases().forEach(phrase -> assertFalse(phrase.isBlank(),
                    disclaimer.path() + " locks a blank phrase, which every file trivially contains"));
        });
    }

    private static List<String> missingPhrases(String text, Disclaimer disclaimer) {
        return disclaimer.requiredPhrases().stream().filter(phrase -> !text.contains(phrase)).toList();
    }

    /** The repository root is the one directory carrying both the settings script and the build wrapper. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isRegularFile(directory.resolve("build.sh"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from " + Path.of("").toAbsolutePath());
    }
}
