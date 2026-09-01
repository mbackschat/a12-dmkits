package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Locks the selected-Node contract shared by TypeScript tools and the literal-node custom-JS worker. */
class TypeScriptRuntimeLauncherTest {

    @Test
    void stableBoundaryAndNewerReleasePassButItsPrereleaseDoesNot(@TempDir Path temporary) throws Exception {
        assertEquals(0, runSelected(fakeNode(temporary.resolve("floor"), "24.12.0")).exitCode());
        assertEquals(0, runSelected(fakeNode(temporary.resolve("newer"), "25.0.0")).exitCode());

        Run prerelease = runSelected(fakeNode(temporary.resolve("prerelease"), "24.12.0-rc.1"));
        assertEquals(2, prerelease.exitCode(), prerelease.output());
        assertTrue(prerelease.output().contains("pre-release"), prerelease.output());
    }

    @Test
    void demoAndNativePreflightsCheckTheLiteralNodeTheirWorkerWillLaunch(@TempDir Path temporary) throws Exception {
        Path validOverride = fakeNode(temporary.resolve("valid-override"), "24.20.0");
        Path systemDirectory = temporary.resolve("system");
        fakeNode(systemDirectory.resolve("node"), "20.20.2");

        Path root = repositoryRoot();
        var builder = new ProcessBuilder(
                "/bin/bash", "-c", "A12_NODE=node /bin/bash \"$1\" --check", "runtime-check",
                root.resolve("scripts/run-typescript.sh").toString());
        builder.redirectErrorStream(true);
        builder.environment().put("A12_NODE", validOverride.toString());
        builder.environment().put("PATH", systemDirectory + System.getProperty("path.separator")
                + builder.environment().get("PATH"));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, process.waitFor(), output);
        assertTrue(output.contains("Node.js 20.20.2 is older"), output);

        String expectedCall = "A12_NODE=node /bin/bash \"$ROOT/scripts/run-typescript.sh\" --check";
        for (String consumer : new String[] {"scripts/verify-demos.sh", "scripts/native-gate.sh"}) {
            assertTrue(Files.readString(root.resolve(consumer)).contains(expectedCall),
                    consumer + " must check literal PATH node because CustomJsWorker launches literal node");
        }
        String worker = Files.readString(root.resolve(
                "cli/src/main/java/io/github/mbackschat/a12/dm/cli/interp/CustomJsWorker.java"));
        assertTrue(worker.contains("new ProcessBuilder(\"node\""),
                "the consumer-runtime control must move with CustomJsWorker if its executable becomes configurable");
    }

    private static Run runSelected(Path node) throws IOException, InterruptedException {
        Path launcher = repositoryRoot().resolve("scripts/run-typescript.sh");
        var builder = new ProcessBuilder("/bin/bash", launcher.toString(), "--check");
        builder.redirectErrorStream(true);
        builder.environment().put("A12_NODE", node.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output);
    }

    private static Path fakeNode(Path path, String version) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nprintf '%s\\n' '" + version + "'\n");
        assertTrue(path.toFile().setExecutable(true), "could not make fake Node executable: " + path);
        return path;
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

    private record Run(int exitCode, String output) {}
}
