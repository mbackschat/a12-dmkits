package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Behavioral guard that the plugin's on-demand installer caches the native binary <b>version-awarely</b>:
 * bumping the pinned {@code VERSION} must re-download the matching binary, never keep serving a stale cached
 * one. A version-less fixed-path cache ("download only if absent") shipped a fixed binary that never reached
 * upgraders — the finding this locks. The assertions live in {@code scripts/selftest-ensure-dmtool.sh}
 * (it stubs the release server and runs the real installer at two versions); this wrapper runs it under the
 * test suite so the guard rides the inner loop + CI, not only a manual invocation. POSIX-only (bash + shell
 * stubs); skipped on Windows, where the JVM {@code :test} task does not run anyway.
 */
class EnsureDmtoolCacheTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void theInstallerBinaryCacheIsVersionAware() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only self-test (bash release-server stubs)");
        Path script = CHECKOUT.requireFile("scripts/selftest-ensure-dmtool.sh");
        Process process = new ProcessBuilder("/bin/bash", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "scripts/selftest-ensure-dmtool.sh failed — the plugin's binary cache is "
                + "not version-aware (a VERSION bump must re-download, not reuse the stale binary). Output:\n" + output);
    }

    /** Locate a repo-root file regardless of the module working dir (mirrors {@link PluginInstallerVersionTest}). */}
