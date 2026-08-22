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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * No committed shell script may read a file timestamp through a {@code stat} spelling only ONE dialect
 * understands, unless that script handles both.
 *
 * <p><strong>Why this is a guard and not advice.</strong> CI is Linux and every development machine here is
 * macOS, so a one-dialect spelling is green locally and dead — or red — on CI. That is not hypothetical: the
 * doc-guard staleness check in {@code .githooks/pre-commit} read mtimes as
 * {@code stat -f %m F || stat -c %Y F || echo 0} and therefore never fired on any GNU-coreutils machine from
 * the day it landed, because the wrong dialect does not fail cleanly. Measured 2026-08-22 on GNU coreutils
 * 9.11 and macOS BSD {@code stat}, same file:
 *
 * <ul>
 *   <li>{@code stat -c %Y F} — GNU prints the mtime; BSD answers {@code illegal option -- c}, exit 1.</li>
 *   <li>{@code stat -f %m F} — BSD prints the mtime; GNU reads {@code %m} as a FILE, prints F's
 *       <em>filesystem</em> block on <em>stdout</em> and exits 1, so a {@code ||} fallback CONCATENATES that
 *       block with the real number instead of replacing it. Every later {@code [ "$t" -gt … ]} then dies as a
 *       non-integer, and a test on the left of a {@code &&} is exempt from {@code set -e}, so the failure is
 *       silent and the check reads every timestamp as 0.</li>
 * </ul>
 *
 * <p><strong>The rule.</strong> A script containing BOTH spellings is handling the split deliberately (the
 * repaired hook probes one, validates the reading, and refuses when neither answers) and passes. A script
 * containing only one is flagged: use that probe shape, or take the timestamp somewhere dialect-free —
 * {@code find -newer} for a comparison, {@code python3}'s {@code os.stat} for a number (what
 * {@code scripts/test-evidence.sh} does).
 *
 * <p><strong>Denominator + controls.</strong> {@code git ls-files} supplies the paths, so a script added
 * tomorrow is scanned without anyone declaring it; comment lines are excluded, since naming a spelling in
 * prose is how this trap gets documented. The scanner has its own positive controls on planted text and a
 * negative control on the both-dialect shape, and the tree scan asserts a floor on the number of scripts it
 * read, so "no findings" cannot mean "no files".
 */
class ShellPortabilityGuardTest {

    private enum StatDialect {
        GNU(Pattern.compile("\\bstat\\s+-c\\b"), "stat -c (GNU only — BSD: illegal option)"),
        BSD(Pattern.compile("\\bstat\\s+-f\\b"), "stat -f (BSD only — GNU reads the format as a file)");

        private final Pattern spelling;
        private final String description;

        StatDialect(Pattern spelling, String description) {
            this.spelling = spelling;
            this.description = description;
        }
    }

    private record Finding(String file, int line, StatDialect dialect, String source) {
        @Override
        public String toString() {
            return file + ":" + line + "  " + dialect.description + "\n      " + source.strip();
        }
    }

    @Test
    void noTrackedShellScriptReadsATimestampInOneDialectOnly() throws Exception {
        Path root = repositoryRoot();
        List<String> scripts = trackedShellScripts(root);
        assertTrue(scripts.size() >= 40,
                "only " + scripts.size() + " shell scripts found — the scanner lost its denominator, so a "
                        + "green here would mean nothing");

        var findings = new ArrayList<Finding>();
        for (String relative : scripts) {
            findings.addAll(scan(relative, Files.readString(root.resolve(relative), StandardCharsets.UTF_8)));
        }

        assertEquals(List.of(), findings, () -> """
                A shell script reads a timestamp in one `stat` dialect only. CI is Linux, this machine is \
                macOS, so the other dialect is never exercised where you can see it.

                %s

                Two ways out: probe once and validate the reading, the way `.githooks/pre-commit` does (a \
                script containing both spellings passes), or avoid `stat` entirely — `find -newer` for a \
                comparison, `python3 -c 'os.stat(...)'` for a number.
                Measurements: docs/BUILD-ENVIRONMENT.md "`stat` has two dialects".""".formatted(
                findings.stream().map(Finding::toString).reduce("", (a, b) -> a.isEmpty() ? "  " + b : a + "\n  " + b)));
    }

    @Test
    void theScannerCatchesEitherSpellingOnItsOwn() {
        assertEquals(List.of(StatDialect.GNU),
                dialectsFound("t=$(stat -c %Y \"$f\")"),
                "the GNU-only spelling must be caught");
        assertEquals(List.of(StatDialect.BSD),
                dialectsFound("newest=$(stat -f %m \"$x\" 2>/dev/null || echo 0)"),
                "the BSD-only spelling must be caught");
    }

    @Test
    void theScannerClearsWhatIsAlreadyPortable() {
        assertEquals(List.of(),
                dialectsFound("""
                        if is_epoch "$(stat -c %Y -- . 2>/dev/null || true)"; then dialect=gnu
                        elif is_epoch "$(stat -f %m . 2>/dev/null || true)"; then dialect=bsd
                        fi"""),
                "a script that probes both dialects is handling the split, not falling into it");
        assertEquals(List.of(),
                dialectsFound("# GNU's `stat -f %m F` reads the format as a file, so we do not use it"),
                "naming the spelling in a comment is how the trap gets documented");
        assertEquals(List.of(),
                dialectsFound("python3 -c 'import os,sys; print(int(os.stat(sys.argv[1]).st_mtime))' \"$f\""),
                "the dialect-free reading must not be flagged");
    }

    private static List<StatDialect> dialectsFound(String script) {
        return scan("planted.sh", script).stream().map(Finding::dialect).distinct().toList();
    }

    /**
     * Findings for one script. Both spellings present means the script chooses a dialect deliberately, so it
     * is exempt as a whole — the choice is the fix, and it is made once per script, not once per call site.
     */
    private static List<Finding> scan(String file, String content) {
        var occurrences = new ArrayList<Finding>();
        var seen = new ArrayList<StatDialect>();
        int number = 0;
        for (String line : content.lines().toList()) {
            number++;
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            for (StatDialect dialect : StatDialect.values()) {
                if (dialect.spelling.matcher(line).find()) {
                    occurrences.add(new Finding(file, number, dialect, line));
                    if (!seen.contains(dialect)) {
                        seen.add(dialect);
                    }
                }
            }
        }
        return seen.size() == StatDialect.values().length ? List.of() : occurrences;
    }

    /** Extension, location, or shebang — a script is a script whether or not it is named {@code *.sh}. */
    private static List<String> trackedShellScripts(Path root) throws IOException, InterruptedException {
        var scripts = new ArrayList<String>();
        for (String relative : trackedFiles(root)) {
            Path file = root.resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            if (relative.endsWith(".sh") || relative.startsWith(".githooks/")) {
                scripts.add(relative);
            } else if (!file.getFileName().toString().contains(".") && hasShellShebang(file)) {
                scripts.add(relative);
            }
        }
        return scripts;
    }

    private static boolean hasShellShebang(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            return first != null && first.startsWith("#!") && first.contains("sh");
        } catch (IOException e) {
            return false; // a binary or non-UTF-8 blob is not a shell script
        }
    }

    /** {@code git ls-files} is the committed set. Failing loudly beats scanning nothing and passing. */
    private static List<String> trackedFiles(Path root) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("git", "ls-files").directory(root.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> lines;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        }
        assertEquals(0, process.waitFor(),
                "git ls-files failed, so the scanned set is unknown rather than clean: " + lines);
        assertFalse(lines.isEmpty(),
                "git ls-files returned nothing, so this guard would scan an empty set and pass vacuously");
        return lines;
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
