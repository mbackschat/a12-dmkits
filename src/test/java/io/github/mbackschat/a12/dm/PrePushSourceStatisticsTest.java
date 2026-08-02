package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Proves the worktree-based statistics generator is used only for a clean pushed HEAD. */
class PrePushSourceStatisticsTest {

    @Test
    void prePushStatisticsDescribeTheCommitThatLeavesTheClone() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only Git-hook self-test");
        Path script = repoFile("scripts/selftest-pre-push-statistics.sh");
        Process process = new ProcessBuilder("bash", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "pre-push statistics provenance self-test failed:\n" + output);
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
