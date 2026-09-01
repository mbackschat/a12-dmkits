package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>The release's performance gate refuses a run that proved nothing — and each refusal is proven to fire.</b>
 *
 * <p>{@code scripts/release-perf-gate.sh} stops a release on five conditions that all leave a green-looking gate
 * behind: the gate command failed, the anchor declined to assert, the lane reported no result XML, the directory
 * was empty, or a bar was skipped (JUnit reports a skip as success). Every one of them used to be a shell branch
 * inside {@code publish-release.sh} that nothing could drive, since that script builds native images and pushes
 * to a public mirror. What that cost is concrete rather than theoretical: under {@code set -euo pipefail} the
 * skip-count assignment <b>aborted</b> when the XML glob matched nothing, so the advertised "NO RESULT XMLs"
 * diagnostic was unreachable and the maintainer would have seen a bare exit 1. Only an executed control finds
 * that.
 *
 * <p>The assertions live in {@code scripts/selftest-release-perf-gate.sh}, which fabricates a gate command, a log
 * and a result-XML directory per case. This wrapper runs it under the suite so the guard rides the inner loop and
 * CI rather than a manual invocation. Its tenth case is the one that keeps the other nine honest: a clean run must
 * <i>pass</i>, or "refuses everything" would satisfy them all.
 *
 * <p>POSIX-only (bash + shell stubs); skipped on Windows, where the JVM {@code :test} task does not run anyway.
 */
class ReleasePerfGateRefusalTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void everyReleasePerformanceGateRefusalFires() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only self-test (bash gate stubs)");
        Path script = CHECKOUT.requireFile("scripts/selftest-release-perf-gate.sh");
        Process process = new ProcessBuilder("bash", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "scripts/selftest-release-perf-gate.sh failed — a release-blocking refusal "
                + "does not fire, or a clean gate run is being refused. Output:\n" + output);
    }

    /**
     * <b>The release path must call the gate, not re-implement it.</b> The refusals are only tested where the
     * self-test can reach them, so a copy of the logic inlined back into {@code publish-release.sh} would be
     * untested again the moment it drifted — the exact state this replaced.
     */
    @Test
    void thePublishPathDelegatesToTheGuardedGate() throws IOException {
        String publish = Files.readString(CHECKOUT.requireFile("scripts/publish-release.sh"));

        assertTrue(publish.contains("./scripts/release-perf-gate.sh"),
                "publish-release.sh must run the performance gate through scripts/release-perf-gate.sh, whose "
                        + "refusals have a positive control");
        assertTrue(!publish.contains("PERFORMANCE GATE SKIPPED") && !publish.contains("PERFORMANCE GATE WAS VACUOUS"),
                "the gate's refusal messages must live only in release-perf-gate.sh; a second copy in the publish "
                        + "script is one the self-test cannot drive");
    }

    /** Locate a repo-root file regardless of the module working dir (mirrors {@link EnsureDmtoolCacheTest}). */}
