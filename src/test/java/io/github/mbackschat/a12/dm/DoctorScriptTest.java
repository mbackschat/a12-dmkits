package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code scripts/doctor.sh} against fabricated machines.
 *
 * <p>The script exists to give a verdict on a machine, and a verdict nobody checks is a verdict that can be
 * wrong in the direction that matters. Its first version shipped with two defects, both in branches no run had
 * exercised: it FAILED the exact configuration this project makes correct, and it PASSED a Kotlin-DSL init
 * script that was injecting. Both are cases below.
 *
 * <p>Only {@code HOME} is faked. The repository root is the real one, because the script derives it from its
 * own location, so the marker-missing branch is deliberately not covered here — {@code BuildPerformancePolicyTest}
 * asserts the marker's presence directly instead.
 */
class DoctorScriptTest {

    @Test
    void aMachineWithNoInitScriptIsClean(@TempDir Path home) throws Exception {
        var run = runDoctorWith(home);

        assertTrue(run.output().contains("the machine-default home injects nothing into this build"),
                run.output());
        assertEquals(0, run.exitCode(), "a clean machine must not fail the doctor:\n" + run.output());
    }

    @Test
    void anInitScriptThatIgnoresTheMarkerIsReported(@TempDir Path home) throws Exception {
        writeInitScript(home, "corporate.gradle", "def injected = 1");

        var run = runDoctorWith(home);

        assertTrue(run.output().contains("runs init script(s) that ignore the marker: corporate.gradle"),
                run.output());
    }

    /** The regression: {@code init.d/*.gradle.kts} is loaded by Gradle exactly like {@code *.gradle}. */
    @Test
    void aKotlinDslInitScriptThatIgnoresTheMarkerIsAlsoReported(@TempDir Path home) throws Exception {
        writeInitScript(home, "corporate.gradle.kts", "val injected = 1");

        var run = runDoctorWith(home);

        assertTrue(run.output().contains("runs init script(s) that ignore the marker: corporate.gradle.kts"),
                "a Kotlin-DSL init script injects exactly like a Groovy one:\n" + run.output());
    }

    /** The other regression: an init script that honours the marker must not be reported as a problem. */
    @Test
    void anInitScriptThatHonoursTheMarkerIsNotAProblem(@TempDir Path home) throws Exception {
        writeInitScript(home, "corporate.gradle",
                "if (!new File(settings.rootDir, '.no-global-gradle-repos').isFile()) { return }");

        var run = runDoctorWith(home);

        assertTrue(run.output().contains("the machine-default home injects nothing into this build"),
                run.output());
        assertFalse(run.output().contains("FAIL"),
                "the configuration this project makes correct must not be reported as broken:\n" + run.output());
        assertEquals(0, run.exitCode(), run.output());
    }

    @Test
    void aMissingKernelFails(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".gradle"));

        var run = runDoctor(home);

        assertTrue(run.output().contains("no kernel in"), run.output());
        assertEquals(1, run.exitCode(), "an unpublished kernel must fail, it is the first-run trap:\n" + run.output());
    }

    private record Run(String output, int exitCode) {}

    /** Fabricates an otherwise healthy machine: the kernel is published, nothing else is assumed. */
    private static Run runDoctorWith(Path home) throws IOException, InterruptedException {
        Files.createDirectories(home.resolve(".m2/repository/com/mgmtp"));
        return runDoctor(home);
    }

    private static Run runDoctor(Path home) throws IOException, InterruptedException {
        Path root = repositoryRoot();
        var builder = new ProcessBuilder("bash", root.resolve("scripts/doctor.sh").toString());
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().remove("GRADLE_USER_HOME");

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\[[0-9;]*m", "");
        return new Run(output, process.waitFor());
    }

    private static void writeInitScript(Path home, String name, String body) throws IOException {
        Path initDirectory = home.resolve(".gradle/init.d");
        Files.createDirectories(initDirectory);
        Files.writeString(initDirectory.resolve(name), body + "\n");
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
