package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *
 * <p><b>The behavioural arm needs a comma-decimal locale to be INSTALLED, and it is not portable.</b> This class
 * first hard-coded {@code de_DE.UTF-8} and went red on CI, where that locale is not generated: bash emits
 * {@code setlocale: cannot change locale} on stderr and then runs in C anyway. Both halves of that matter —
 * the run fails, and it would be VACUOUS if the warning were merely filtered out, because then both arms would
 * be the same C run agreeing with itself. So the premise is established by measurement rather than assumed
 * ({@link #firstCommaDecimalLocale} asks {@code awk} to format a number and keeps the first locale that
 * actually answers with a comma), the arm is skipped where no such locale exists, and
 * {@link #theGuardPinsTheNumericLocaleItselfRatherThanInheritingIt} keeps a deletion of the fix failing
 * everywhere — including on the runners that cannot exercise the behaviour.
 */
class InfographicFreshnessLocaleTest {

    /**
     * Locales whose decimal separator is a comma, which is what made the difference observable. Several
     * spellings and languages, because which are generated is a property of the machine: macOS carries the
     * {@code .UTF-8} forms, glibc images usually the {@code .utf8} ones, and a CI runner typically neither.
     */
    private static final List<String> COMMA_DECIMAL_LOCALES = List.of(
            "de_DE.UTF-8", "de_DE.utf8", "fr_FR.UTF-8", "fr_FR.utf8", "nl_NL.UTF-8", "pt_BR.UTF-8");

    private static final String GUARD = "scripts/check-infographic-freshness.sh";

    @Test
    void theFreshnessGuardReachesTheSameVerdictUnderACommaDecimalLocale() throws Exception {
        Path root = repositoryRoot();
        String comma = firstCommaDecimalLocale().orElse(null);
        assumeTrue(comma != null, "no comma-decimal locale is installed here, so this machine cannot reproduce "
                + "the hazard at all; " + COMMA_DECIMAL_LOCALES + " were probed through awk and none formatted "
                + "1.5 with a comma");

        Run underC = run(root, "C");
        Run underComma = run(root, comma);

        assertEquals(underC.exitCode, underComma.exitCode,
                () -> "the guard's verdict changed with the developer's locale — C exited " + underC.exitCode
                        + ", " + comma + " exited " + underComma.exitCode
                        + ".\nC:\n" + underC.output + "\n" + comma + ":\n" + underComma.output);
        assertEquals(underC.output, underComma.output,
                "the guard's output changed with the developer's locale, so a future check could diverge even "
                        + "while the exit status agrees");
    }

    /**
     * The always-on half. A machine without a comma-decimal locale cannot run the arm above, so on CI nothing
     * would notice the fix being deleted — and the fix is one line in a shell script. This is a source-text
     * check and is weak on purpose: it does not claim the guard is locale-invariant, only that it still pins
     * the one variable that made it locale-dependent.
     */
    @Test
    void theGuardPinsTheNumericLocaleItselfRatherThanInheritingIt() throws Exception {
        String script = Files.readString(repositoryRoot().resolve(GUARD), StandardCharsets.UTF_8);
        assertTrue(script.contains("LC_NUMERIC=C"),
                "the freshness guard must pin LC_NUMERIC itself — awk's printf honours it, so inheriting the "
                        + "developer's makes the guard compute 47,7 where the committed doc says 47.7");
    }

    private record Run(int exitCode, String output) {
    }

    /**
     * The first candidate this machine can actually format a comma with — the premise the behavioural arm
     * needs, established rather than inferred. {@code locale -a} is deliberately not the oracle: it lists what
     * is generated, while what matters is whether {@code awk}'s {@code printf} really switches separator, which
     * is the exact mechanism the guard tripped over.
     */
    private static Optional<String> firstCommaDecimalLocale() throws Exception {
        for (String locale : COMMA_DECIMAL_LOCALES) {
            var builder = new ProcessBuilder("awk", "BEGIN{printf \"%.1f\", 1.5}");
            builder.environment().put("LANG", locale);
            builder.environment().put("LC_ALL", locale);
            Process process = builder.start();
            String formatted = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            if ("1,5".equals(formatted)) {
                return Optional.of(locale);
            }
        }
        return Optional.empty();
    }

    private static Run run(Path root, String locale) throws Exception {
        var builder = new ProcessBuilder("bash", root.resolve(GUARD).toString());
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
