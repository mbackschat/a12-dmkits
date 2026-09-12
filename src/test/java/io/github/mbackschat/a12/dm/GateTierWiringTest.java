package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Anti-rot guard for the test tiers (docs/TESTING-SPEC "Test tiers"). The performance tests (@Tag {@code perf}
 * routine tier, @Tag {@code perf-bench} checkpoint tier) and the differential fuzzers (@Tag {@code fuzz}) are
 * EXCLUDED from the default run (adapter/build.gradle), so this asserts CI still RUNS them on separate defined
 * triggers — fuzz on every push and the isolated routine perf gate in its own nightly workflow. If someone
 * removes, combines, or broadens a lane, the build fails loudly here (the F11 "loud detection at build"
 * discipline). The complementary question — WHICH classes belong in a performance lane, and whether their names
 * say so — is {@link PerfTestClassificationTest}.
 */
class GateTierWiringTest {

    @Test
    void pushCiOwnsCorrectnessAndPersistsTheGradleCache() throws IOException {
        String ci = Files.readString(locate(".github/workflows/ci.yml"));

        assertTrue(ci.contains("-PrunFuzz"),
                "CI must run the fuzz lane (-PrunFuzz) on every push — else the differential fuzzers are dropped");
        // The JS/Node target is tested NOWHERE else. `test`, `:adapter:test` and `:noBackendTest` select no
        // interpreter task, so before this was wired the module ran only on a developer's machine and Node
        // coverage was a matter of who happened to run `:interpreter:check` locally (IF295, measured 2026-08-13).
        // `allTests` deliberately, not `check`: `check` additionally drags release packaging, API generation,
        // Maven/npm publication verification and runtime-closure work into every push.
        assertTrue(ci.contains(":interpreter:allTests"),
                "CI must run :interpreter:allTests on every push — it is the only lane that executes the JS/Node "
                        + "target, and :interpreter:check would pull the release machinery in with it");
        assertFalse(ci.contains("schedule:"),
                "main-push correctness CI must not rerun on the nightly schedule");
        assertTrue(ci.contains("uses: gradle/actions/setup-gradle@v6"),
                "CI must persist the configured Gradle user home instead of discarding its local build cache");
        assertTrue(ci.contains("GRADLE_USER_HOME: ${{ github.workspace }}/.gradle-isolated"),
                "the Gradle cache action and build must share the isolated user home");
    }

    /**
     * <b>A guard that hangs off {@code check} reaches CI only by being NAMED, and this workflow names tasks
     * one by one.</b> A {@code check} dependency is not a {@code test} dependency, so a gate absent from the
     * run line is silently retired. That is not hypothetical, and it has now happened twice:
     * {@code :schemakit:check} appeared in NO lane until 2026-09-05, so the fifth published kit's 27 test
     * classes and {@code verifySchemakitPurity} had never once run in CI; and on 2026-09-12 a task-graph diff
     * (CI 204 tasks against the full {@code check} lifecycle's 295) found five more, including
     * {@code :interpreter:verifyInterpreterPurity} — the EUPL import boundary that CLAUDE.md calls one of the
     * two interpreter rules a miss on which is catastrophic.
     *
     * <p><b>The denominator is DERIVED, which is the part that matters.</b> The first version of this guard
     * named five gates in a literal list, and missed the second incident for the reason such a list always
     * misses one: nothing makes you extend it. So the gates are read out of the build scripts themselves —
     * every identifier wired into a {@code tasks.named('check')} block, in every module — and each must be
     * satisfied in CI or carry a written reason here. Adding a gate to {@code check} now fails this test until
     * it is dispositioned, which is the only version of the rule that cannot go stale.
     *
     * <p><b>The assertion is on the {@code run:} LINE, not on the file.</b> A file-wide {@code contains} is
     * satisfied by the comment above that line explaining why each gate is named there — measured: deleting
     * {@code :schemakit:check} from the invocation left this test green, because the prose still mentioned it.
     */
    @Test
    void pushCiNamesEveryCheckOnlyGuardHoldingTheKernelFreeClaim() throws IOException {
        String invocation = Files.readString(locate(".github/workflows/ci.yml")).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("run: ./gradlew"))
                .reduce((a, b) -> {
                    throw new AssertionError("more than one gradlew invocation in ci.yml — say which one holds "
                            + "the kernel-free gates before asserting on it");
                })
                .orElseThrow(() -> new AssertionError("ci.yml runs no gradlew invocation at all"));

        var unreached = new java.util.ArrayList<String>();
        int derived = 0;
        for (var module : CHECK_WIRED_BUILD_FILES.entrySet()) {
            String build = Files.readString(locate(module.getValue()));
            for (String gate : checkDependencies(build)) {
                derived++;
                if (NOT_A_PUSH_CI_GATE.containsKey(gate)
                        || invocation.contains(":" + gate)
                        || invocation.contains(module.getKey() + "check")) {
                    continue;
                }
                unreached.add(module.getValue() + ": " + module.getKey() + gate);
            }
        }

        // Non-vacuity: the estate wires ~24 gates onto `check`, so a detector that finds a handful has stopped
        // parsing a build script and would report a stranded gate as reached.
        assertTrue(derived >= 20,
                "derived only " + derived + " check-wired gates across " + CHECK_WIRED_BUILD_FILES.size()
                        + " build scripts, so the parse broke and a green result here proves nothing");
        assertTrue(unreached.isEmpty(),
                "these gates hang off a `check` that ci.yml never runs, so they execute NOWHERE — name each in "
                        + "the run line, or give it a reason in NOT_A_PUSH_CI_GATE:\n" + String.join("\n", unreached)
                        + "\nInvocation was: " + invocation);
    }

    /** Every build script that wires something onto `check`, keyed by the task prefix CI would have to name. */
    private static final java.util.Map<String, String> CHECK_WIRED_BUILD_FILES = java.util.Map.of(
            ":", "build.gradle",
            ":adapter:", "adapter/build.gradle",
            ":cli:", "cli/build.gradle",
            ":interpreter:", "interpreter/build.gradle",
            ":interpreter-bridge:", "interpreter-bridge/build.gradle",
            ":consistency:", "consistency/build.gradle",
            ":schemakit:", "schemakit/build.gradle");

    /**
     * Gates deliberately outside push CI, each with the reason. A gate belongs here only when running it on
     * every push would buy nothing — never merely because it is slow, which is an argument for a cheaper gate.
     */
    private static final java.util.Map<String, String> NOT_A_PUSH_CI_GATE = java.util.Map.ofEntries(
            java.util.Map.entry("focusedSelectionGuardTest", "also hangs off root `test`, which CI runs"),
            java.util.Map.entry("kernelRuntimeClosureReport", "declares itself INFORMATIVE ONLY and promotes no threshold"),
            java.util.Map.entry("interpreterRuntimeClosure", "produces the measurement interpreterRuntimeClosurePolicyTest asserts on"),
            java.util.Map.entry("interpreterJsRuntimeClosure", "the JS half of the same producer"),
            java.util.Map.entry("verifyInterpreterMavenPublication", "release rehearsal: needs a staged Maven repository"),
            java.util.Map.entry("verifyInterpreterNpmPublication", "release rehearsal: needs a packed npm tarball"),
            java.util.Map.entry("verifyInterpreterReleaseArtifacts", "release rehearsal: asserts on assembled release assets"),
            java.util.Map.entry("verifyInterpreterBrowserSizeDocumentation", "release rehearsal: needs the production browser bundle"),
            java.util.Map.entry("assembleInterpreterPublicMirror", "release step, not a gate"));

    /** Every identifier wired into a {@code tasks.named('check')} block, single-line and braced forms alike. */
    private static List<String> checkDependencies(String build) {
        var gates = new java.util.ArrayList<String>();
        var block = java.util.regex.Pattern.compile("tasks\\.named\\('check'\\)\\s*\\{(.*?)\\n?\\}", java.util.regex.Pattern.DOTALL);
        var matcher = block.matcher(build);
        while (matcher.find()) {
            var names = java.util.regex.Pattern.compile("dependsOn\\s+([A-Za-z0-9_', ]+)").matcher(matcher.group(1));
            while (names.find()) {
                for (String name : names.group(1).split(",")) {
                    String clean = name.strip().replace("'", "");
                    if (!clean.isEmpty()) {
                        gates.add(clean);
                    }
                }
            }
        }
        return List.copyOf(gates);
    }

    @Test
    void infographicFreshnessSetsUpPinnedNodeThenBlocksHeavySetupWithoutASecondJob() throws IOException {
        String ci = Files.readString(locate(".github/workflows/ci.yml"));
        String specification = Files.readString(locate("docs/INFOGRAPHICS.md"));
        int buildStart = ci.indexOf("  build:");
        assertTrue(buildStart >= 0, "missing CI build job");
        assertFalse(ci.contains("  infographic-preflight:"),
                "a separate preflight job duplicates runner startup and repository checkout on every green run");

        String build = ci.substring(buildStart);
        int checkout = build.indexOf("- name: Checkout rulekit");
        int nodeSetup = build.indexOf("- name: Set up Node for repository TypeScript tools");
        int preflight = build.indexOf("- name: Verify infographic claims, placements, and public copies");
        int kernelCheckout = build.indexOf("- name: Checkout the kernel bootstrap kit");
        int javaSetup = build.indexOf("- name: Set up JDK 21 + 25");
        int gradleSetup = build.indexOf("- name: Set up Gradle cache");
        int kernelBootstrap = build.indexOf("- name: Bootstrap the A12 kernel from public sources");
        assertTrue(checkout >= 0 && checkout < nodeSetup && nodeSetup < preflight,
                "the TypeScript preflight needs only the checkout and the repository's pinned Node runtime");
        assertTrue(preflight < kernelCheckout && preflight < javaSetup && preflight < gradleSetup
                        && preflight < kernelBootstrap,
                "the preflight must fail before Kernel checkout, JDK/Gradle setup, cache restore, or bootstrap");
        assertTrue(build.substring(preflight, kernelCheckout)
                        .contains("run: ./scripts/check-infographic-freshness.sh"),
                "the first post-Node step must execute the stable infographic launcher");
        assertTrue(specification.contains("checks out rulekit, sets up the repository-pinned Node runtime, and "
                        + "then runs that stable shell command"),
                "the infographic synchronization contract must publish the guarded CI ordering");
    }

    @Test
    void ciChecksEveryPosixEntrypointThroughTheRepositoryWidePortabilityCommand() throws IOException {
        String ci = Files.readString(locate(".github/workflows/ci.yml"));

        assertTrue(ci.contains("run: ./scripts/check-shell-portability.sh"),
                "CI must discover every POSIX entrypoint through the committed portability command; an enumerated "
                        + "launcher list silently misses the next script");
        assertFalse(ci.contains("shellcheck -s sh \\"),
                "the CI workflow must not carry a second, hand-maintained POSIX-script denominator");
    }

    @Test
    void ciRunsEverySourceAdjacentTypeScriptToolTestBeforeHeavySetup() throws IOException {
        String ci = Files.readString(locate(".github/workflows/ci.yml"));
        int nodeSetup = ci.indexOf("- name: Set up Node for repository TypeScript tools");
        int toolTests = ci.indexOf("- name: Run repository TypeScript tool tests");
        int kernelCheckout = ci.indexOf("- name: Checkout the kernel bootstrap kit");

        assertTrue(nodeSetup >= 0 && nodeSetup < toolTests && toolTests < kernelCheckout,
                "source-adjacent TypeScript tests must reuse pinned Node and fail before Kernel/toolchain setup");
        assertTrue(ci.substring(toolTests, kernelCheckout)
                        .contains("run: ./scripts/run-typescript.sh --test scripts/typescript/*.test.ts"),
                "CI must discover TypeScript tool tests by suffix rather than maintain a second test list");
    }

    @Test
    void infographicBehavioralGuardDeclaresItsCompleteLiveInputSet() throws IOException {
        String delivery = gradleTask(Files.readString(locate("build.gradle")), "deliveryGuardTest");
        for (String input : List.of(
                "README.md",
                "docs/INFOGRAPHICS.md",
                "interpreter/public-release/PERFORMANCE.md",
                "plugin/README.md",
                "interpreter/public-release/README.md",
                ".github/workflows/ci.yml",
                "assets/infographics/*.png",
                "plugin/assets/infographics/*.png",
                "interpreter/public-release/assets/infographics/*.png")) {
            assertTrue(delivery.contains(input),
                    "deliveryGuardTest executes the live infographic command, so it must invalidate on " + input);
        }
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

    /** The three {@code -Pfast} levers and their measured splits; the rationale is on {@link #assertFastLeverSplit}. */
    @Test
    void theCheapGuardsAreNotBehindTheFastLever() throws IOException {
        // Root: the six that bind a committed artifact — API goldens, catalog prose-review digests, the
        // JSON-construction classification, the release-gap ledger, the kernel-derived temporal vocabulary —
        // against the broad guards and Gradle process controls. This list is a subset check, so a new always-on guard is unlocked until
        // it is named here: `vocabularyGuardTest` was added 2026-08-29 after a cold review found its build
        // comment claiming this tier with nothing asserting it.
        // `leakGuardTest` sat in the always-on list until 2026-09-12, when a warm `--profile` run measured it at
        // 15.6s of a 16.8s `:test -Pfast` build — 92.7% of the loop it rides on. It is the one guard here that
        // declares `upToDateWhen { false }`, so it alone re-runs in full every time while the rest go
        // UP-TO-DATE for free; and a repo-wide secret scan mirrors no committed artifact, so skipping it
        // leaves nothing stale. Both conjuncts fail, and the general rule is locked by
        // noAlwaysRunTaskRidesTheUnconditionalTier below.
        assertFastLeverSplit("build.gradle",
                List.of("jsonConstructionGuardTest", "apiGuardTest", "catalogProseReviewGuardTest",
                        "releaseGapGuardTest", "vocabularyGuardTest"),
                List.of("docGuardTest", "interpreterReleaseGuardTest", "deliveryGuardTest",
                        "focusedSelectionGuardTest", "leakGuardTest"));
        // :adapter — the four FIXTURE guards (6.6s) against the two broad ones (82.7s). A committed fixture
        // that silently stops matching what the code produces is the same class as a stale golden.
        assertFastLeverSplit("adapter/build.gradle",
                List.of("footprintFixtureGuardTest", "performanceFixtureGuardTest",
                        "retainedComputationFixtureGuardTest", "workspacePreparationFixtureGuardTest"),
                List.of("corpusReplayTest", "profileFixtureGuardTest"));
        // :cli — the provenance resource, the gap ledgers' live ids, and the semantics-map registration of
        // every lock class (2.4s) against the three broad ones (12.7s).
        assertFastLeverSplit("cli/build.gradle",
                List.of("semanticsMapGuardTest", "staleGapGuardTest", "sourceProvenanceGuardTest"),
                List.of("docGuardTest", "evalGuardTest", "mutationTailGuardTest"));
    }

    /**
     * An always-run task may never be an UNCONDITIONAL prerequisite of {@code test}, in any module.
     *
     * <p>This is the class behind the incident above, not another instance of it. The unconditional tier's
     * admission rule is a conjunction — bind a committed artifact to the source it mirrors, AND cost a small
     * fraction of the loop you ride on — and {@code outputs.upToDateWhen { false }} contradicts both halves by
     * construction. It re-runs in full on every invocation, so it can never be a small fraction; and a task
     * with no declared inputs has no committed artifact to go stale, so skipping it costs reach, never a false
     * green. Nothing checked this, so a guard could be admitted while cheap and then grow without limit: root
     * {@code leakGuardTest} accreted seven guard classes and reached 92.7% of a warm {@code :test -Pfast} build
     * before anyone re-derived the tier total it was justified by (2026-09-12).
     *
     * <p>Cost alone is deliberately NOT the trigger. A cost ceiling needs a number that must be re-measured to
     * stay true, which is the very thing that rotted here; this reads a DECLARATION instead, so it cannot go
     * stale. The remedy when it fires is to move the task behind {@code -Pfast} — CI runs {@code test} without
     * that lever, so full reach is preserved exactly where an escape matters.
     */
    @Test
    void noAlwaysRunTaskRidesTheUnconditionalTier() throws IOException {
        var alwaysRunFound = new java.util.ArrayList<String>();
        var violations = new java.util.ArrayList<String>();

        for (String buildFile : List.of("build.gradle", "adapter/build.gradle", "cli/build.gradle")) {
            String build = Files.readString(locate(buildFile));
            String testTask = namedGradleTask(build, "test");
            int fastAt = testTask.indexOf("if (!project.hasProperty('fast'))");
            String unconditional = fastAt > 0 ? testTask.substring(0, fastAt) : testTask;

            var registered = java.util.regex.Pattern.compile("tasks\\.register\\('([A-Za-z0-9]+)'")
                    .matcher(build);
            while (registered.find()) {
                String task = registered.group(1);
                if (!gradleTask(build, task).contains("outputs.upToDateWhen { false }")) {
                    continue;
                }
                alwaysRunFound.add(buildFile + ":" + task);
                if (unconditional.contains("dependsOn '" + task + "'")) {
                    violations.add(buildFile + ": " + task);
                }
            }
        }

        // Positive control on the scan's own denominator: the estate's regenerators (dump*/sync*) and
        // measurement tasks (perfGateTest, *PerfTest) all declare it, so finding none would mean the registered
        // -task pattern or the block bounding broke, and a green result would prove nothing.
        assertFalse(alwaysRunFound.isEmpty(),
                "found no always-run task at all across the three build files, so this scan checked nothing — "
                        + "fix the detector rather than trusting the green");
        assertTrue(violations.isEmpty(),
                "an always-run task hangs off `test` unconditionally, so every focused inner-loop run pays for "
                        + "it in full and its cost can grow without any measurement noticing:\n"
                        + String.join("\n", violations)
                        + "\nMove it behind the -Pfast lever. CI runs `test` without -Pfast, so its reach is "
                        + "kept where an escape actually matters.");
    }

    /**
     * A guard cheap enough that dropping it saves nothing must never be behind {@code -Pfast}, in ANY module.
     *
     * <p><b>The incident this exists for.</b> Every guard prerequisite of the root {@code test} task used to sit
     * inside {@code if (!project.hasProperty('fast'))}, so a {@code -Pfast} run silently removed all eight from
     * the task graph — not UP-TO-DATE, absent. The whole DG37 landing was verified that way, and
     * {@code apiGuardTest} never ran: {@code EntityPath}/{@code HasEntityPath} and the {@code FieldRef}
     * /{@code GroupRef} component change shipped against stale API goldens, with the catalog prose-review digest
     * stale beside them. Both are artifacts nothing else mirrors, so nothing else could notice. `:adapter` and
     * `:cli` carried the same undivided lever and were re-partitioned the same day, from their own measurements.
     *
     * <p><b>Why each split is where it is.</b> Per-task test time from a green run's result XMLs (2026-08-15) is
     * recorded beside each {@code dependsOn} block. The rule is not a pure cost threshold: a guard stays
     * unconditional when it binds a COMMITTED ARTIFACT to the source it must mirror <em>and</em> costs a small
     * fraction of the loop it rides on. Those guards also declare their inputs, so editing a test leaves them
     * UP-TO-DATE at zero cost — they execute exactly when the artifact they mirror could have moved.
     */
    private static void assertFastLeverSplit(String buildFile, List<String> alwaysOn, List<String> behindFast)
            throws IOException {
        String build = Files.readString(locate(buildFile));
        String testTask = namedGradleTask(build, "test");
        String fastMarker = "if (!project.hasProperty('fast'))";

        int fastAt = testTask.indexOf(fastMarker);
        assertTrue(fastAt > 0, buildFile + ": the test task no longer carries the -Pfast lever, so this guard is "
                + "vacuous — re-derive the split rather than deleting the assertion");
        String unconditional = testTask.substring(0, fastAt);
        String conditional = testTask.substring(fastAt);

        for (String guard : alwaysOn) {
            String dependency = "dependsOn '" + guard + "'";
            assertTrue(unconditional.contains(dependency),
                    buildFile + ": " + guard + " must hang off `test` UNCONDITIONALLY. It costs a fraction of the "
                            + "loop, and it binds a committed artifact to the source it mirrors — behind -Pfast it "
                            + "vanishes from the graph and the artifact goes stale with nothing to notice. That is "
                            + "not hypothetical: it is how the DG37 API goldens shipped stale.");
            assertFalse(conditional.contains(dependency),
                    buildFile + ": " + guard + " is ALSO declared behind -Pfast; the unconditional declaration "
                            + "above would still hold, but the duplicate invites deleting the wrong one");
        }
        for (String guard : behindFast) {
            assertTrue(conditional.contains("dependsOn '" + guard + "'"),
                    buildFile + ": " + guard + " is one of the expensive guards the fast lever exists to drop — "
                            + "if it moved, re-measure and re-derive the split instead of editing this list");
        }
        assertTrue(conditional.contains("SKIPPING"),
                buildFile + ": a -Pfast run must ANNOUNCE which guards it dropped — the failure mode here was "
                        + "never a wrong result, it was a silent absence that read as a clean run");
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
        // WHICH classes the gate runs is PerfTestClassificationTest's invariant (it must be the whole @Tag('perf')
        // routine tier, selected by tag). This assertion used to pin `--tests "*PerfGateTest"` — the enumerated
        // form that left 7 of 9 routine-tier bars in no lane — so pinning it here would now defend the defect.
        assertFalse(perfTask.isEmpty(),
                "adapter must provide a dedicated routine performance task");
        assertTrue(perfTask.contains("maxParallelForks = 1"),
                "the routine performance task must not compete across test forks");
        assertTrue(perfTask.contains("outputs.upToDateWhen { false }"),
                "the routine performance task must always execute");
        assertTrue(perfTask.contains("outputs.cacheIf { false }"),
                "the routine performance task must never restore a cached measurement");
        assertTrue(perfTask.contains("failFast = false"),
                "the routine performance task must not be failFast: it exists to MEASURE, and aborting on the "
                        + "first breach discards every later lane's observation — the 2026-08-11 nightly "
                        + "reported 21 of 26 rows and hid five behind the one that tripped");
        assertFalse(perfTask.contains("failFast = true"),
                "…and the negative half, so the assertion above cannot be satisfied by a comment while the "
                        + "setting says otherwise");
        assertTrue(perfTask.contains("systemProperty 'perf.anchor.lane', 'true'"),
                "the absolute-time anchor bar (PerfAnchors, IF280) is armed only in this single-fork lane; without "
                        + "the property every lane records and asserts nothing, which is the bar silently off");
        assertTrue(perfTask.contains("systemProperty 'perf.anchor.required', 'true'"),
                "and an observation this runner class has not committed a number for must be a failure, or adding a "
                        + "lane silently switches its bar off");
        // Forbids the MECHANISMS a re-gating needs (an env lookup, a project property, a provider, a conditional)
        // rather than two spellings of one of them: an exact-literal match is defeated by reindenting or by
        // double quotes, and the earlier two-spelling version passed `if (System.getenv("CI") != null)` unchanged.
        // The task body is small and has no legitimate conditional, so forbidding `if (` outright is affordable.
        assertFalse(perfTask.contains("getenv") || perfTask.contains("hasProperty")
                        || perfTask.contains("environmentVariable") || perfTask.contains("if ("),
                "…unconditionally, not gated on CI or on an opt-in property: a scenario whose fingerprint moves "
                        + "takes its anchor key with it while leaving the observation COUNT intact, so "
                        + "PerfAnchorCoverageGuardTest cannot see it either, and gating the failure on CI put it on "
                        + "the one runner nobody watches interactively");
    }

    /**
     * The anchor is refreshable only from a run's own observation record, and a hosted runner's block can be
     * authored from nowhere else — so the upload is part of the mechanism, not a convenience (IF280).
     */
    @Test
    void theNightlyUploadsTheObservationRecordTheAnchorIsRefreshedFrom() throws IOException {
        String workflow = Files.readString(locate(".github/workflows/nightly-performance.yml"));

        assertTrue(workflow.contains("actions/upload-artifact"),
                "the nightly must upload its performance observations");
        assertTrue(workflow.contains("git ls-files --others --exclude-standard -- perf-results"),
                "…collected as the files THIS run added. The gate writes into the committed perf-results/ tree "
                        + "(never build/, which clean deletes — SurvivingDataNotInBuildGuardTest), so an artifact "
                        + "of the whole tree would ship other machines' and other revisions' committed numbers as "
                        + "if this run had produced them");
        assertFalse(workflow.contains("a12-dmkits/perf-results/**"),
                "…and never the committed tree itself, which is the shape that regression takes");
        assertTrue(workflow.contains("path: ${{ runner.temp }}/perf-record/**"),
                "…uploaded from the staging directory the collection step fills, which is also what keeps the "
                        + "artifact rooted at the record rather than at the runner's absolute paths");
        assertTrue(workflow.contains("if: always()"),
                "…and on failure too, since a breach is exactly when the numbers are wanted");
    }

    /**
     * The kernel-runtime observation probe (docs/KERNEL-PROBE-SPEC.md) is a MEASUREMENT, and must stay one.
     *
     * <p>It runs the kernel's Groovy runtime code generation — the very thing the released binary was severed
     * from — so wiring it into a gate would put a kernel runtime evaluation on every verification run and give
     * the release boundary a reason to bend. Its classpath is the assertion that keeps it out of any shipped
     * artifact: a JavaExec over the TEST runtime classpath cannot ride a published jar or the native image.
     */
    @Test
    void theKernelRuntimeProbeIsAMeasurementAndNotAGate() throws IOException {
        String adapterBuild = Files.readString(locate("adapter/build.gradle"));
        String probe = taskBlock(adapterBuild, "tasks.register('kernelProbe', JavaExec)");

        assertFalse(probe.isEmpty(), "adapter must provide the kernel-runtime observation probe");
        assertTrue(probe.contains("classpath = sourceSets.test.runtimeClasspath"),
                "the probe must run off TEST source, so it cannot enter a published artifact or the native image");
        assertTrue(probe.contains("group = 'measurement'"),
                "the probe reports an observation; it asserts nothing and belongs in the measurement group");
        assertTrue(probe.contains("outputs.upToDateWhen { false }") && probe.contains("outputs.cacheIf { false }"),
                "an observation of the live kernel must never be restored from a previous run");
        assertFalse(adapterBuild.contains("dependsOn 'kernelProbe'") || adapterBuild.contains("dependsOn kernelProbe"),
                "no gate may depend on the probe: `check` would then run kernel Groovy codegen on every build");
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
        assertTrue(task.contains("systemProperty 'dmtool.versionProperties', dmtoolContractVersionProperties"),
                "the packaged version oracle must be the selected artifact's build snapshot, not live Git state");
        assertTrue(task.contains("inputs.file(file(dmtoolContractVersionProperties))"),
                "a changed packaged provenance snapshot must invalidate the public-contract lane");

        String versionContract = Files.readString(locate(
                "cli/src/test/java/io/github/mbackschat/a12/dm/cli/DmtoolPublicContractTest.java"));
        assertTrue(versionContract.contains("System.getProperty(\"dmtool.versionProperties\""),
                "the version contract must read the immutable snapshot supplied for the selected executable");
        assertFalse(versionContract.contains("git(\"rev-parse\"")
                        || versionContract.contains("git(\"ls-files\"")
                        || versionContract.contains("git(\"diff\""),
                "an artifact's build identity must never be compared with a later test-time working tree");

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
