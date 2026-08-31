package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The infographic freshness guard must reach the same verdict whatever locale the developer runs in.
 *
 * <p><b>Measured, not hypothetical.</b> On 2026-08-31 this guard refused a clean push on a
 * {@code LANG=de_DE.UTF-8} machine: it derives the production Java size from the README's Tokei block and
 * requires {@code docs/INFOGRAPHICS.md} to carry that exact string, and {@code awk}'s {@code printf "%.1f"}
 * honours {@code LC_NUMERIC} — so it computed {@code 47,7 kLOC} and compared it against the committed
 * {@code 47.7 kLOC}. CI runners are C/UTF-8, so the failure was invisible there and there was no drift to fix;
 * the guard was simply wrong on that machine.
 *
 * <p><b>Why a property test rather than a pinned number.</b> Asserting the guard passes would couple this test
 * to the docs actually being fresh, which is the guard's own job and would make an honest drift fail here too.
 * Asserting the two runs AGREE tests only what is claimed — locale-invariance — and holds whether the docs are
 * fresh or stale, which is exactly the property that was missing.
 */
class InfographicFreshnessLocaleTest {

    /** A locale whose decimal separator is a comma, which is what made the difference observable. */
    private static final String COMMA_DECIMAL_LOCALE = "de_DE.UTF-8";

    @Test
    void theFreshnessGuardReachesTheSameVerdictUnderACommaDecimalLocale() throws Exception {
        Path root = repositoryRoot();
        Run underC = run(root, "C");
        Run underComma = run(root, COMMA_DECIMAL_LOCALE);

        assertEquals(underC.exitCode, underComma.exitCode,
                () -> "the guard's verdict changed with the developer's locale — C exited " + underC.exitCode
                        + ", " + COMMA_DECIMAL_LOCALE + " exited " + underComma.exitCode
                        + ".\nC:\n" + underC.output + "\n" + COMMA_DECIMAL_LOCALE + ":\n" + underComma.output);
        assertEquals(underC.output, underComma.output,
                "the guard's output changed with the developer's locale, so a future check could diverge even "
                        + "while the exit status agrees");
    }

    private record Run(int exitCode, String output) {
    }

    private static Run run(Path root, String locale) throws Exception {
        var builder = new ProcessBuilder("bash", root.resolve("scripts/check-infographic-freshness.sh").toString());
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        // Both are set, and LC_ALL last: the script may legitimately export its own, and what is under test is
        // that it does so rather than inheriting whatever the developer has.
        env.put("LANG", locale);
        env.put("LC_ALL", locale);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
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
        throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
