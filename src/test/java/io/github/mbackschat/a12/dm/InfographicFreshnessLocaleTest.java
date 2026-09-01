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
import org.junit.jupiter.api.io.TempDir;

/**
 * The infographic freshness guard must reach the same verdict whatever locale the developer runs in.
 *
 * <p><b>Measured, not hypothetical.</b> On 2026-08-31 this guard refused a clean push on a
 * {@code LANG=de_DE.UTF-8} machine: its former shell implementation derived the production Java size through
 * {@code awk}'s locale-sensitive {@code printf "%.1f"}, so it computed {@code 47,7 kLOC} and compared it against
 * the committed {@code 47.7 kLOC}. CI runners were C/UTF-8, so the failure was invisible there and there was no
 * drift to fix. The typed implementation now uses locale-independent {@code Number.toFixed} and Unicode-string
 * splitting rather than asking shell tools to parse stateful text.
 *
 * <p><b>Why the public command and runtime refusal are part of the property.</b> Comparing two direct
 * implementation runs let a no-op shell launcher pass while CI executed that launcher. An always-on arm now
 * drives the public command through Dash as the POSIX oracle in both directions: the exact success line with real Node, and
 * the shared runtime-gate refusal with an absent executable. The optional locale arm uses the same public path.
 * That makes the test sensitive to live-document drift, so {@code deliveryGuardTest} declares the complete
 * current document/asset denominator instead of caching the positive verdict across such a change.
 *
 * <p><b>The behavioural arm needs a comma-decimal locale to be INSTALLED, and it is not portable.</b> This class
 * first hard-coded {@code de_DE.UTF-8} and went red on CI, where that locale is not generated: bash emits
 * {@code setlocale: cannot change locale} on stderr and then runs in C anyway. Both halves of that matter —
 * the run fails, and it would be VACUOUS if the warning were merely filtered out, because then both arms would
 * be the same C run agreeing with itself. So the premise is established by measurement rather than assumed
 * ({@link #firstCommaDecimalLocale} intersects the operating system's generated locales with Node's default
 * formatter and keeps the first locale that actually answers with a comma), the arm is skipped where no such
 * locale exists, and the runtime-refusal plus source-wiring arms keep the command path visible everywhere —
 * including runners that cannot express the hazard.
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
    private static final String IMPLEMENTATION = "scripts/typescript/check-infographic-freshness.ts";

    @Test
    void thePublicLauncherAlwaysCrossesTheRuntimeGateAndRunsTheTypedGuard(@TempDir Path temporary) throws Exception {
        Path root = repositoryRoot();
        Run underC = runGuard(root, "C", nodeExecutable());
        Run missingNode = runGuard(root, "C", temporary.resolve("missing-node").toString());

        assertEquals(0, underC.exitCode, underC.output);
        assertEquals("infographic freshness: OK\n", underC.output,
                "a no-op public launcher must not satisfy the behavioral guard");
        assertEquals(2, missingNode.exitCode, missingNode.output);
        assertTrue(missingNode.output.contains("Node.js >= 24.12.0 is required"), missingNode.output);
    }

    @Test
    void theFreshnessGuardReachesTheSameVerdictUnderACommaDecimalLocale() throws Exception {
        Path root = repositoryRoot();
        String comma = firstCommaDecimalLocale().orElse(null);
        assumeTrue(comma != null, "no comma-decimal locale is available to Node, so this machine cannot reproduce "
                + "the hazard at all; " + COMMA_DECIMAL_LOCALES + " were probed and none formatted "
                + "1.5 with a comma");

        Run underC = runGuard(root, "C");
        Run underComma = runGuard(root, comma);

        assertEquals(0, underC.exitCode, underC.output);
        assertEquals(0, underComma.exitCode, underComma.output);
        assertEquals(underC.exitCode, underComma.exitCode,
                () -> "the guard's verdict changed with the developer's locale — C exited " + underC.exitCode
                        + ", " + comma + " exited " + underComma.exitCode
                        + ".\nC:\n" + underC.output + "\n" + comma + ":\n" + underComma.output);
        assertEquals(underC.output, underComma.output,
                "the guard's output changed with the developer's locale, so a future check could diverge even "
                        + "while the exit status agrees");
    }

    @Test
    void nodeIcuSupportCannotStandInForAnInstalledShellLocale() throws Exception {
        assertEquals(Optional.empty(), firstCommaDecimalLocale(List.of()),
                "a locale that Node can format through bundled ICU is not usable by the public Bash launcher "
                        + "unless the operating system has generated it too");
    }

    /**
     * The always-on structural half. A machine without a comma-decimal locale cannot run the arm above, so CI
     * would notice a return to default-locale formatting. This source-text arm is deliberately narrow: the
     * behavioural property above owns the verdict, while this one keeps the public shell path connected to the
     * typed source and preserves the locale-independent numeric primitive.
     */
    @Test
    void theGuardUsesLocaleIndependentTypeScriptFormattingBehindItsStableLauncher() throws Exception {
        Path root = repositoryRoot();
        String launcher = Files.readString(root.resolve(GUARD), StandardCharsets.UTF_8);
        String implementation = Files.readString(root.resolve(IMPLEMENTATION), StandardCharsets.UTF_8);
        assertTrue(launcher.contains("run-typescript.sh") && launcher.contains("check-infographic-freshness.ts"),
                "the public script path must delegate to the TypeScript implementation through the shared "
                        + "Node-version gate");
        assertTrue(implementation.contains(".toFixed(1)"),
                "kLOC formatting must use JavaScript's locale-independent fixed decimal form");
    }

    private record Run(int exitCode, String output) {
    }

    /**
     * The first candidate this machine can activate in Bash and actually format with a comma in Node — both
     * premises the behavioural arm needs, established rather than inferred. Neither check can stand alone:
     * {@code locale -a} says whether the launcher can activate the locale, while Node's bundled ICU says whether
     * its default formatter really differs — the ambient input a future {@code Intl.NumberFormat} regression
     * would consume.
     */
    private static Optional<String> firstCommaDecimalLocale() throws Exception {
        Process process = new ProcessBuilder("locale", "-a").start();
        List<String> installed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .map(String::trim)
                .toList();
        if (process.waitFor() != 0) {
            return Optional.empty();
        }
        return firstCommaDecimalLocale(installed);
    }

    private static Optional<String> firstCommaDecimalLocale(List<String> installedLocales) throws Exception {
        for (String locale : COMMA_DECIMAL_LOCALES) {
            if (!installedLocales.contains(locale)) {
                continue;
            }
            var builder = new ProcessBuilder("node", "-e", "process.stdout.write(new Intl.NumberFormat().format(1.5))");
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

    private static Run runGuard(Path root, String locale) throws Exception {
        return runGuard(root, locale, nodeExecutable());
    }

    private static Run runGuard(Path root, String locale, String node) throws Exception {
        var builder = new ProcessBuilder("/bin/dash", root.resolve(GUARD).toString());
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        // Both are set, and LC_ALL last: Node reads its default locale at process start, which is the ambient
        // input this property must vary while the typed implementation keeps its output fixed.
        env.put("LANG", locale);
        env.put("LC_ALL", locale);
        env.put("A12_NODE", node);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }

    private static String nodeExecutable() throws Exception {
        Process process = new ProcessBuilder("node", "-p", "process.execPath").start();
        String executable = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), "could not resolve the Node executable used by this test");
        return executable;
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
