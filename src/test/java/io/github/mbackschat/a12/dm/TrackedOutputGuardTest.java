package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * No build output and no log file may be tracked.
 *
 * <p>{@code .gitignore} cannot enforce this. It is consulted only for UNTRACKED paths, so {@code git add -f}
 * walks past it, and once a path is tracked the rule is never consulted again. That is precisely what
 * happened: {@code build/} has been ignored since the first scaffold commit on 2026-06-02, and 72
 * {@code build/*-red.log} files were still force-added across ~20 commits seven weeks later, publishing 591
 * copies of an absolute home path until 2026-08-10.
 *
 * <p>This guard is the authoritative half of the defence because it runs in CI, where a local hook cannot be
 * assumed and {@code --no-verify} cannot reach. The early half is {@code .githooks/pre-commit}, which gives
 * the same verdict at staging time for anyone who wired {@code core.hooksPath}.
 */
class TrackedOutputGuardTest {

    /** A {@code build/} directory at any depth, or any {@code .log} file. */
    private static final Pattern FORBIDDEN = Pattern.compile("(^|/)build/|\\.log$");

    @Test
    void noBuildOutputOrLogFileIsTracked() throws Exception {
        Path root = repositoryRoot();
        List<String> tracked = trackedFiles(root);

        List<String> offenders = tracked.stream().filter(path -> FORBIDDEN.matcher(path).find()).toList();

        assertTrue(offenders.isEmpty(),
                "build output or log files are tracked. .gitignore does not stop this — it is only consulted "
                        + "for untracked paths — so these were force-added:\n" + String.join("\n", offenders));
    }

    @Test
    void gitignoreStillCoversBuildOutput() throws IOException {
        String ignored = Files.readString(repositoryRoot().resolve(".gitignore"));

        assertTrue(ignored.lines().anyMatch(line -> line.strip().equals("build/")),
                ".gitignore must keep ignoring build/, so an unforced `git add` cannot pick it up in the "
                        + "first place; the guard above only catches what got past it");
    }

    @Test
    void theStagingHookIsCommittedAndExecutable() throws IOException {
        Path hook = repositoryRoot().resolve(".githooks/pre-commit");

        assertTrue(Files.isRegularFile(hook), ".githooks/pre-commit is the early half of this defence");
        assertTrue(Files.isExecutable(hook), "a non-executable hook is silently skipped by git");
        String body = Files.readString(hook);
        assertTrue(body.contains("--diff-filter=ACMR"),
                "the hook must inspect what is STAGED, not the working tree");
        assertTrue(body.contains("(^|/)build/|\\.log$"),
                "the hook and this guard must refuse the same shapes, or one of them is decorative");
    }

    /** The pattern's own control: it must flag what it exists for and leave ordinary sources alone. */
    @Test
    void thePatternFlagsOutputAndSparesSources() {
        for (String offender : List.of("build/R2.1-red.log", "adapter/build/reports/x.html",
                "perf-gate.log", "docs/build/index.html")) {
            assertTrue(FORBIDDEN.matcher(offender).find(), "must be refused: " + offender);
        }
        for (String legitimate : List.of("build.gradle", "build.sh", "scripts/rebuild.sh",
                "docs/BUILD-ENVIRONMENT.md", "src/main/java/Builder.java", "settings.gradle")) {
            assertFalse(FORBIDDEN.matcher(legitimate).find(), "must be allowed: " + legitimate);
        }
    }

    private static List<String> trackedFiles(Path root) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("git", "ls-files").directory(root.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        List<String> lines;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        }

        assertEquals(0, process.waitFor(), "git ls-files failed, so the tracked set is unknown: " + lines);
        assertFalse(lines.isEmpty(), "git ls-files returned nothing, so this guard would pass vacuously");
        return lines;
    }

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
