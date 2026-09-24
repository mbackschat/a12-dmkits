package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 * <p>{@code HOME}, {@code ACTIONLINT} and {@code core.hooksPath} are faked. The repository root is the real
 * one, because the script derives it from its own location, so the marker-missing branch is deliberately not
 * covered here — {@code BuildPerformancePolicyTest} asserts the marker's presence directly instead.
 *
 * <p>The hooks reading is injected for a reason worth keeping: it is the one input whose real value differs
 * between a developer's clone (wired, by the CLAUDE.md instruction) and a fresh checkout (never wired). Asserting
 * the wired branch without injecting it therefore passes on every machine that runs it locally and fails only in
 * CI, which is what it did — a green {@code :docGuardTest} could not have caught it.
 */
class DoctorScriptTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** The state CLAUDE.md tells a developer to put their clone in. */
    private static final String WIRED = ".githooks";

    /** What {@code git config --get} yields on a fresh clone: the key is absent, so the script reads empty. */
    private static final String UNWIRED = "";

    @Test
    void aMachineWithNoInitScriptIsClean(@TempDir Path home) throws Exception {
        var run = runDoctorWith(home);

        assertTrue(run.output().contains("POSIX shell launcher reached the typed doctor — "), run.output());
        assertTrue(run.output().contains("Node.js runtime meets the >= 24.12.0 baseline — "), run.output());
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

    @Test
    void theKernelRepositoryMarkerMustBeADirectory(@TempDir Path home) throws Exception {
        Path marker = home.resolve(".m2/repository/com/mgmtp");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "not a Maven repository\n");

        var run = runDoctor(home);

        assertTrue(run.output().contains("no kernel in"), run.output());
        assertEquals(1, run.exitCode(), "a file at the repository path must not satisfy the kernel check:\n"
                + run.output());
    }

    @Test
    void aMissingRequiredNodeRuntimeFails(@TempDir Path home) throws Exception {
        var run = runDoctorWith(home, null, WIRED, home.resolve("missing-node").toString());

        assertTrue(run.output().contains("Node.js >= 24.12.0 is required"), run.output());
        assertEquals(1, run.exitCode(), "the repository's TypeScript tools cannot run without Node:\n" + run.output());
    }

    @Test
    void anOldNodeRuntimeFailsWithTheRequiredBoundary(@TempDir Path home) throws Exception {
        Path node = executable(home.resolve("node-20"), "#!/bin/sh\nprintf '20.20.2\\n'\n");

        var run = runDoctorWith(home, null, WIRED, node.toString());

        assertTrue(run.output().contains("Node.js 20.20.2 is older than the required >= 24.12.0"), run.output());
        assertEquals(1, run.exitCode(), "an EOL Node without stable direct TypeScript support must fail:\n"
                + run.output());
    }

    @Test
    void theAgentNeutralPushPrerequisitesAreReported(@TempDir Path home) throws Exception {
        var run = runDoctorWith(home, home.resolve("missing-actionlint").toString(), WIRED);

        assertTrue(run.output().contains("the commit and push hooks are wired (core.hooksPath=.githooks)"),
                run.output());
        assertTrue(run.output().contains("actionlint is unavailable, so the pre-push hook will refuse"),
                run.output());
        assertTrue(run.output().contains("brew install actionlint"), run.output());
    }

    /**
     * The other branch, which is the one every fresh clone is actually in — CI included, which is where this
     * pair's missing control was found (run 33216895191, 2026-08-28).
     */
    @Test
    void anUnwiredCloneIsToldHowToWireTheHooks(@TempDir Path home) throws Exception {
        var run = runDoctorWith(home, home.resolve("missing-actionlint").toString(), UNWIRED);

        assertTrue(run.output().contains("the committed Git hooks are not wired in this clone"), run.output());
        assertTrue(run.output().contains("config core.hooksPath .githooks"),
                "a warning without its remediation is a dead end:\n" + run.output());
        assertEquals(0, run.exitCode(), "an unwired clone is a warning, not a failure:\n" + run.output());
    }

    private record Run(String output, int exitCode) {}

    /** Fabricates an otherwise healthy machine: the kernel is published, nothing else is assumed. */
    private static Run runDoctorWith(Path home) throws IOException, InterruptedException {
        Files.createDirectories(home.resolve(".m2/repository/com/mgmtp"));
        return runDoctor(home);
    }

    private static Run runDoctorWith(Path home, String actionlint, String hooksPath)
            throws IOException, InterruptedException {
        Files.createDirectories(home.resolve(".m2/repository/com/mgmtp"));
        return runDoctor(home, actionlint, hooksPath, null);
    }

    private static Run runDoctorWith(Path home, String actionlint, String hooksPath, String node)
            throws IOException, InterruptedException {
        Files.createDirectories(home.resolve(".m2/repository/com/mgmtp"));
        return runDoctor(home, actionlint, hooksPath, node);
    }

    private static Run runDoctor(Path home) throws IOException, InterruptedException {
        return runDoctor(home, null, WIRED, null);
    }

    private static Run runDoctor(Path home, String actionlint, String hooksPath, String node)
            throws IOException, InterruptedException {
        var builder = CHECKOUT.command(List.of("/bin/dash", CHECKOUT.resolve("scripts/doctor.sh").toString()));
        builder.redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().remove("GRADLE_USER_HOME");
        // Git reads these ahead of every config file, so the run's verdict on the hooks is this argument and
        // never the clone the test happens to execute in.
        builder.environment().put("GIT_CONFIG_COUNT", "1");
        builder.environment().put("GIT_CONFIG_KEY_0", "core.hooksPath");
        builder.environment().put("GIT_CONFIG_VALUE_0", hooksPath);
        if (actionlint != null) {
            builder.environment().put("ACTIONLINT", actionlint);
        }
        if (node != null) {
            builder.environment().put("A12_NODE", node);
        }

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\u001b\\[[0-9;]*m", "");
        return new Run(output, process.waitFor());
    }

    private static void writeInitScript(Path home, String name, String body) throws IOException {
        Path initDirectory = home.resolve(".gradle/init.d");
        Files.createDirectories(initDirectory);
        Files.writeString(initDirectory.resolve(name), body + "\n");
    }

    private static Path executable(Path path, String body) throws IOException {
        Files.writeString(path, body);
        assertTrue(path.toFile().setExecutable(true), "could not make fake Node executable: " + path);
        return path;
    }

}
