package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Anti-rot guard for the test tiers (docs/TESTING-SPEC "Test tiers"). The heavy perf benchmark
 * (@Tag {@code perf}) and the differential fuzzers (@Tag {@code fuzz}) are EXCLUDED from the default run
 * (adapter/build.gradle), so this asserts CI still RUNS them on a defined trigger — fuzz on every push and the
 * isolated routine perf gate nightly. If someone removes or broadens a lane in the CI workflow, the build fails
 * loudly here (the F11 "loud detection at build" discipline).
 */
class GateTierWiringTest {

    @Test
    void ciRunsBothTieredLanesSoNeitherIsSilentlyDropped() throws IOException {
        String yaml = Files.readString(locate(".github/workflows/ci.yml"));
        String perfScript = Files.readString(locate("scripts/perf.sh"));
        assertTrue(yaml.contains("-PrunFuzz"),
                "CI must run the fuzz lane (-PrunFuzz) on every push — else the differential fuzzers are dropped");
        assertTrue(yaml.contains("run: ./scripts/perf.sh gate"),
                "nightly CI must run the isolated routine perf gate, not the whole adapter suite with perf enabled");
        assertTrue(perfScript.contains(":adapter:test -PrunPerf"),
                "the perf launcher must opt into the tagged perf tests");
        assertTrue(perfScript.contains("--tests \"*PerfGateTest"),
                "the gate mode must isolate PerfGateTest from heavyweight benchmark classes");
    }

    /** Walk up from the working dir to find {@code rel} (the test runs from a module dir; ci.yml is at the repo root). */
    private static Path locate(String rel) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(rel);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate " + rel + " upward from " + Path.of("").toAbsolutePath());
    }
}
