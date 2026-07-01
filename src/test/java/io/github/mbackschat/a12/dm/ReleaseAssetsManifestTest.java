package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Guard the public-release asset manifest against the static mirror workflow matrix. Most release scripts
 * source {@code scripts/release-assets.sh} directly; GitHub Actions matrix YAML cannot, so the companion
 * shell guard makes drift loud in the normal test suite.
 */
class ReleaseAssetsManifestTest {

    @Test
    void releaseAssetManifestMatchesTheMirrorWorkflow() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only shell guard");
        Path script = repoFile("scripts/check-release-assets.sh");
        Process process = new ProcessBuilder("bash", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "scripts/check-release-assets.sh failed:\n" + output);
    }

    /** Locate a repo-root file regardless of the module working dir (mirrors {@link PluginInstallerVersionTest}). */
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
