package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Runs the repository launcher self-test in the ordinary delivery guard and CI. */
class DmtoolLauncherTest {

    @Test
    void launcherDispatchAndJdkGateAreGuarded() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only launcher self-test");
        Path script = repoFile("scripts/selftest-dmtool-launcher.sh");
        String nativeOutput = System.getProperty("dmtool.nativeOutputRelative", "");
        if (nativeOutput.isBlank()) {
            throw new IllegalStateException("deliveryGuardTest did not provide nativeCompile.outputFile");
        }
        Process process = new ProcessBuilder("/bin/bash", script.toString(), nativeOutput)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "scripts/selftest-dmtool-launcher.sh failed:\n" + output);
    }

    @Test
    void sourcePreparationNeverServesAStaleOrUnidentifiedLauncher() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only source-preparation self-test");
        Path script = repoFile("scripts/selftest-prepare-dmtool-source.sh");
        Process process = new ProcessBuilder("/bin/bash", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "scripts/selftest-prepare-dmtool-source.sh failed:\n" + output);
    }

    /**
     * The pre-commit doc-guard staleness check both fires and stays quiet. It exists because a prose trigger
     * table did not hold — the same drift class shipped three times in one day, the last time hours after the
     * note warning about it — so the check itself must be shown to work rather than believed.
     */
    @Test
    void thePreCommitHookRefusesACommitWhoseDocGuardEvidenceIsStale() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"), "POSIX-only hook self-test");
        Path script = repoFile("scripts/selftest-precommit-doc-guards.sh");
        Process process = new ProcessBuilder("/bin/bash", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "scripts/selftest-precommit-doc-guards.sh failed:\n" + output);
    }

    @Test
    void thePrePushHookRunsTheCheapCiGuardsAndRefusesBlindValidation() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"), "POSIX-only hook self-test");
        Path script = repoFile("scripts/selftest-prepush-ci.sh");
        Process process = new ProcessBuilder("/bin/bash", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "scripts/selftest-prepush-ci.sh failed:\n" + output);
    }

    private static Path repoFile(String relative) {
        for (String base : List.of(".", "..", "../..")) {
            Path candidate = Path.of(base, relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
