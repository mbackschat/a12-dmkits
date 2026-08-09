package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Anti-rot guard for the test tiers (docs/TESTING-SPEC "Test tiers"). The heavy perf benchmark
 * (@Tag {@code perf}) and the differential fuzzers (@Tag {@code fuzz}) are EXCLUDED from the default run
 * (adapter/build.gradle), so this asserts CI still RUNS them on separate defined triggers — fuzz on every push
 * and the isolated routine perf gate in its own nightly workflow. If someone removes, combines, or broadens a
 * lane, the build fails loudly here (the F11 "loud detection at build" discipline).
 */
class GateTierWiringTest {

    @Test
    void pushCiOwnsCorrectnessAndPersistsTheGradleCache() throws IOException {
        String ci = Files.readString(locate(".github/workflows/ci.yml"));

        assertTrue(ci.contains("-PrunFuzz"),
                "CI must run the fuzz lane (-PrunFuzz) on every push — else the differential fuzzers are dropped");
        assertFalse(ci.contains("schedule:"),
                "push/PR correctness CI must not rerun on the nightly schedule");
        assertTrue(ci.contains("uses: gradle/actions/setup-gradle@v6"),
                "CI must persist the configured Gradle user home instead of discarding its local build cache");
        assertTrue(ci.contains("GRADLE_USER_HOME: ${{ github.workspace }}/.gradle-isolated"),
                "the Gradle cache action and build must share the isolated user home");
    }

    @Test
    void nightlyWorkflowRunsOnlyTheRoutinePerformanceGate() throws IOException {
        String nightly = Files.readString(locate(".github/workflows/nightly-performance.yml"));

        assertTrue(nightly.contains("schedule:"), "the performance workflow must retain the nightly trigger");
        assertTrue(nightly.contains("workflow_dispatch:"), "the performance workflow must remain runnable on demand");
        assertTrue(nightly.contains("run: ./scripts/perf.sh gate"),
                "nightly CI must invoke the routine performance launcher");
        assertTrue(nightly.contains("uses: gradle/actions/setup-gradle@v6"),
                "nightly CI must restore and save the Gradle user home");
        assertTrue(nightly.contains("GRADLE_USER_HOME: ${{ github.workspace }}/.gradle-isolated"),
                "the nightly cache action and gate must share the isolated user home");
        assertFalse(nightly.contains("-PrunFuzz"), "nightly performance must not rerun deterministic fuzzers");
        assertFalse(nightly.contains("verify-demos.sh"), "nightly performance must not rerun deterministic demos");
        assertFalse(nightly.contains(":cli:test"), "nightly performance must not run the CLI correctness suite");
    }

    @Test
    void routinePerformanceTaskCannotRestoreAStaleMeasurement() throws IOException {
        String perfScript = Files.readString(locate("scripts/perf.sh"));
        String adapterBuild = Files.readString(locate("adapter/build.gradle"));
        String perfTask = gradleTask(adapterBuild, "perfGateTest");

        assertTrue(perfScript.contains(":adapter:perfGateTest"),
                "the gate launcher must target its dedicated Gradle test task");
        assertTrue(perfScript.contains(":adapter:test -PrunPerf"),
                "non-gate performance modes must still opt into the tagged performance tests");
        assertTrue(perfScript.contains("--tests \"*PerfGateTest"),
                "the gate mode must isolate PerfGateTest from heavyweight benchmark classes");
        assertFalse(perfTask.isEmpty(),
                "adapter must provide a dedicated routine performance task");
        assertTrue(perfTask.contains("maxParallelForks = 1"),
                "the routine performance task must not compete across test forks");
        assertTrue(perfTask.contains("outputs.upToDateWhen { false }"),
                "the routine performance task must always execute");
        assertTrue(perfTask.contains("outputs.cacheIf { false }"),
                "the routine performance task must never restore a cached measurement");
    }

    @Test
    void adapterOracleLanesUseForksInsteadOfInForkThreads() throws IOException {
        String adapterBuild = Files.readString(locate("adapter/build.gradle"));

        assertTrue(adapterBuild.contains("systemProperty 'junit.jupiter.execution.parallel.enabled', 'false'"),
                "adapter lanes share RuntimeLaws' exact-model KernelEngine instances inside one fork; their "
                        + "service concurrency is unmeasured, and same-header-id generation is serialized by "
                        + "the kernel DocumentModelCodeGenerator. Pin JUnit in-fork parallelism false and scale "
                        + "with maxParallelForks instead");
        assertFalse(adapterBuild.contains("systemProperty 'junit.jupiter.execution.parallel.enabled', 'true'"),
                "adapter lanes must not override the shared-oracle concurrency pin");
    }

    @Test
    void packagedPublicContractDeclaresTheCanonicalSkillInput() throws IOException {
        String rootBuild = Files.readString(locate("build.gradle"));
        String cliBuild = Files.readString(locate("cli/build.gradle"));
        String task = gradleTask(cliBuild, "publicContractTest");
        String cliDocGuard = gradleTask(cliBuild, "docGuardTest");
        String cliMainTest = namedGradleTask(cliBuild, "test");
        String docGuard = gradleTask(rootBuild, "docGuardTest");

        assertFalse(task.isEmpty(), "cli must provide the packaged public-contract task");
        assertTrue(task.contains("rootProject.file('skill/SKILL.md')"),
                "the public-contract task reads the canonical skill and must invalidate on a skill-only change");
        assertTrue(docGuard.contains("file('cli/build.gradle')"),
                "the wiring guard reads cli/build.gradle, so docGuardTest must declare that file as an input");
        assertTrue(cliDocGuard.contains("includeTestsMatching 'io.github.mbackschat.a12.dm.cli.PublicOutputClaimsTest'"),
                "the public-output prose guard must run in the input-aware CLI doc lane");
        assertTrue(cliDocGuard.contains("includeTestsMatching 'io.github.mbackschat.a12.dm.cli.PublicCommandShapeClaimsTest'"),
                "the command-shape prose guard must run in the input-aware CLI doc lane");
        // Both exclusions are asserted through the DERIVATION rather than two literal exclude lines: the broad
        // task computes its excludes from the owning lanes' includes, so naming `docGuardTest` as an owner covers
        // every class it hosts. The literal form saw only the classes it spelled out, and four sibling docGuardTest
        // classes were measurably running in both lanes while these two assertions passed.
        // Matched WITHOUT the closing paren: the helper takes optional trailing classifications (non-owners,
        // untraced-during-capture lanes), and pinning the exact arity made a legitimate 4-argument call read as
        // a lost derivation. The invariant is that the excludes are DERIVED from the owning-lane list, which the
        // prefix establishes; the arguments after it are the classification, not the derivation.
        assertTrue(cliMainTest.contains("routeExclusivelyHostedTests(it, dedicatedTestTasks"),
                "the main CLI test task must DERIVE its excludes from the owning lanes, not mirror them by hand");
        assertTrue(dedicatedTaskList(cliBuild).contains("'docGuardTest'"),
                "the repo-file-reading prose guards must not also run in the input-blind main CLI test task");
    }

    @Test
    void packagedPublicContractOverlapsOnlyReviewedProcessOwners() throws IOException {
        String build = Files.readString(locate("cli/build.gradle"));
        String task = gradleTask(build, "publicContractTest");

        assertTrue(task.contains("junit.jupiter.execution.parallel.enabled', 'true'"),
                "the packaged lane must retain task-local JUnit concurrency or its independent process waits "
                        + "return to the measured 410-second serial wall time");
        assertTrue(task.contains("junit.jupiter.execution.parallel.config.strategy', 'fixed'"),
                "the packaged lane must use a bounded fixed pool, not JUnit's machine-dependent dynamic pool");
        assertTrue(task.contains("Math.min(4, Runtime.runtime.availableProcessors())"),
                "the packaged lane must cap process concurrency at four so it does not turn wait overlap into "
                        + "CPU and memory contention");
        assertTrue(task.contains("nativeCaptureNonRuntimeSystemProperties"),
                "the native capture re-hosts these classes serially, so the JUnit scheduling-only properties "
                        + "need an explicit reasoned classification rather than weakening runtime-input parity");
        assertTrue(task.contains("capture keeps one serial test fork while reaching the same methods"),
                "the classification must state why omitting concurrency cannot shrink native reachability");
        assertTrue(task.contains("systemProperty 'dmtool.executable', dmtoolContractExecutable"),
                "dmtool.executable is a real absorbed-class runtime input and must remain supplied separately");

        for (String className : new String[] {
                "CommandRouteEquivalenceTest",
                "DmtoolMutationSafetyTest",
                "DmtoolPublicContractTest",
                "PackagedDmtoolJourneyTest",
        }) {
            String source = Files.readString(locate(
                    "cli/src/test/java/io/github/mbackschat/a12/dm/cli/" + className + ".java"));
            assertTrue(source.contains("@Execution(ExecutionMode.CONCURRENT)"),
                    className + " owns an independent @TempDir and packaged process runner, so removing its "
                            + "reviewed opt-in silently serializes its methods; new classes stay serial until "
                            + "they receive the same explicit review");
        }
    }

    /** The literal list of lanes that exclusively own their test classes, which the broad task derives from. */
    private static String dedicatedTaskList(String build) {
        int start = build.indexOf("def dedicatedTestTasks = [");
        if (start < 0) {
            return "";
        }
        return build.substring(start, build.indexOf(']', start));
    }

    private static String gradleTask(String build, String name) {
        return taskBlock(build, "tasks.register('" + name + "', Test)");
    }

    private static String namedGradleTask(String build, String name) {
        return taskBlock(build, "tasks.named('" + name + "')");
    }

    /**
     * The text of one task block. Bounded by the block's own closing brace as well as the next {@code tasks.}
     * declaration: the last task in a file has no successor, so a next-marker-only scan returned everything to
     * EOF and an assertion meant for one task silently held over the whole remainder.
     */
    private static String taskBlock(String build, String marker) {
        int start = build.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int from = start + marker.length();
        int nextTask = build.indexOf("\ntasks.", from);
        int closingBrace = build.indexOf("\n}\n", from);
        int end = build.length();
        if (nextTask >= 0) {
            end = nextTask;
        }
        if (closingBrace >= 0 && closingBrace < end) {
            end = closingBrace;
        }
        return build.substring(start, end);
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
