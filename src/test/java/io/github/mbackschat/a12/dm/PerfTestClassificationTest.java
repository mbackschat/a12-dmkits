package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * <b>A performance test must be recognizable as one, and must run in a lane that can measure.</b>
 *
 * <p>Performance is the estate's priority #1: a release is blocked on regressions with full coverage, and ordinary
 * pushes are covered by the nightly with full coverage. Two things have to hold for that to mean anything, and
 * both failed at least once:
 *
 * <ol>
 *   <li><b>A test that measures the machine must not be confused with a regular test.</b>
 *       {@code ModelReportUsagePerfTest} asserted only counts — {@code parses <= rules + comps} and
 *       {@code many == few} — and its one timed method was opt-in and self-described as "not a gate". Reading the
 *       name as a classification and tagging it {@code perf} removed two deterministic guards from every push. It
 *       is now {@code ModelReportSinglePassTest}.</li>
 *   <li><b>Every performance test must be selected by a lane.</b> The routine gate named exactly two classes in a
 *       {@code filter} block, so 7 of the 9 routine-tier timing bars ran in no automated lane at all — and a
 *       {@code --tests} pattern could not widen it, because Gradle ANDs the command-line filter with the task's
 *       own (measured 2026-08-13: {@code --tests "*CharsetPerfTest"} against that filter matched nothing). The
 *       lane now selects {@code includeTags 'perf'}, which is what {@link #theRoutinePerformanceGateSelectsByTag}
 *       pins.</li>
 * </ol>
 *
 * <p><b>The bound of this guard, stated rather than implied.</b> It is deliberately syntactic — a name and a tag,
 * both exact — so it can never produce a false red. It therefore cannot recognize a timing bar written under a
 * neutral name with no tag at all ({@code FooTest} calling {@code PerfHarness.timeInterpreter}). Several text
 * detectors for "asserts a machine measurement" were tried and each leaked in both directions: the harness's own
 * unit tests call {@code assertWithinBudget} and {@code assertGrowthBelowCatastropheFloor} on scripted values and
 * are correctly untagged, while {@code KernelParityCoverageGuardTest} matched every pattern because it scans the
 * perf sources as text. What covers the residue is the convention this guard enforces at the two ends plus the
 * reviewed classification in {@code docs/TESTING-SPEC.md} — not a heuristic that would eventually fail a build
 * for the wrong reason.
 */
class PerfTestClassificationTest {

    /** The lane tags. {@code perf} runs in the routine gate; {@code perf-bench} is the checkpoint tier. */
    private static final Set<String> PERF_TAGS = Set.of("perf", "perf-bench");

    /**
     * Suffixes that announce a machine measurement. {@code PerfTest} is the general one; the other three name the
     * three singular lanes — the routine gate, the kernel-ratio bench tier, and the retained-heap probe.
     */
    private static final List<String> MEASURING_SUFFIXES = List.of("PerfTest", "GateTest", "BenchTest", "ProbeTest");

    /**
     * <b>Name ⇒ tag.</b> The owner's rule, mechanized: a class named {@code *PerfTest} is claiming to be a
     * performance test, so it must be tagged into a performance lane. If it is not one, rename it.
     *
     * <p>Only {@code *PerfTest} is checked in this direction, not every entry of {@link #MEASURING_SUFFIXES}:
     * {@code GateTest}/{@code ProbeTest} are general English used elsewhere in the estate ({@code CrashProbeTest}
     * is a corpus-sweep shrinker), and a guard that failed on those would be a false red rather than a finding.
     */
    @Test
    void everyClassNamedPerfTestIsTaggedIntoAPerformanceLane() throws IOException {
        var untagged = new TreeSet<String>();
        for (var source : testSources()) {
            String name = className(source);
            if (name.endsWith("PerfTest") && perfTagOf(source) == null) {
                untagged.add(name);
            }
        }

        assertEquals(Set.of(), untagged,
                "a class named *PerfTest claims to measure the machine, so it must carry @Tag(\"perf\") (routine "
                        + "gate) or @Tag(\"perf-bench\") (checkpoint tier). If it does not measure anything — "
                        + "ModelReportUsagePerfTest asserted only counts — then RENAME it instead of tagging it, "
                        + "or the tag silently removes a deterministic guard from every push");
    }

    /**
     * <b>Tag ⇒ name.</b> The converse: a perf-tagged class is excluded from the ordinary suite, so a reader who
     * finds it in a directory listing must be able to tell that it never runs there.
     */
    @Test
    void everyPerformanceTaggedClassIsNamedForWhatItMeasures() throws IOException {
        var misnamed = new TreeMap<String, String>();
        for (var source : testSources()) {
            String tag = perfTagOf(source);
            String name = className(source);
            if (tag != null && MEASURING_SUFFIXES.stream().noneMatch(name::endsWith)) {
                misnamed.put(name, tag);
            }
        }

        assertEquals(Set.of(), misnamed.keySet(),
                "a @Tag(\"perf\")/@Tag(\"perf-bench\") class does not run in the ordinary suite, so its name must "
                        + "say it measures: end it in one of " + MEASURING_SUFFIXES + ". Two classes were tagged "
                        + "while asserting no measurement at all (PerfPreflightTest — deterministic assertThrows "
                        + "on n of 1/50/201; PerformanceScenarioEligibilityTest — semantic agreement, no clock) "
                        + "and the tag was what hid them from every push. Found: " + misnamed);
    }

    /**
     * <b>No third tag.</b> A typo ({@code @Tag("perfs")}) would drop a class out of the routine gate AND leave it
     * excluded from nothing, so it would run nowhere while looking tagged. Both lanes name their tags literally,
     * so the vocabulary has to be closed.
     */
    @Test
    void thePerformanceTagVocabularyIsClosed() throws IOException {
        var found = new TreeSet<String>();
        for (var source : testSources()) {
            Matcher tags = Pattern.compile("^\\s*@Tag\\(\"(perf[a-z-]*)\"\\)", Pattern.MULTILINE)
                    .matcher(Files.readString(source));
            while (tags.find()) {
                found.add(tags.group(1));
            }
        }

        // A SUBSET check, not equality: the invariant is that no tag outside the pair exists, not that both are
        // populated. Requiring equality would fail the build the day a tier legitimately empties — a false red,
        // and the kind this guard must not manufacture, since it exists to be trustworthy about classification.
        found.removeAll(PERF_TAGS);
        assertEquals(Set.of(), found,
                "the performance lane tags are declared in adapter/build.gradle (the routine gate's includeTags "
                        + "and the broad suite's excludeTags), so only " + new TreeSet<>(PERF_TAGS) + " select a "
                        + "lane; any other perf-prefixed tag selects none, and the class runs nowhere while "
                        + "reading as covered");
    }

    /**
     * <b>The routine gate selects by TAG, never by a class list.</b> This is what makes "full coverage" structural
     * instead of a promise: a new {@code @Tag("perf")} class joins the lane by existing. The enumerated
     * {@code filter} this replaced is the exact mechanism by which 7 bars ran nowhere.
     */
    @Test
    void theRoutinePerformanceGateSelectsByTag() throws IOException {
        String gate = gradleTask(Files.readString(locate("adapter/build.gradle")), "perfGateTest");

        assertTrue(gate.contains("useJUnitPlatform { includeTags 'perf' }"),
                "perfGateTest must select the routine tier by tag; an includeTestsMatching list is how a "
                        + "performance test silently stops being gated");
        assertTrue(gate.lines().noneMatch(line -> line.contains("includeTestsMatching")),
                "perfGateTest must not name classes: a list and a tag disagree the moment someone adds a test, "
                        + "and Gradle ANDs the two so the list always wins");

        String launcher = Files.readString(locate("scripts/perf.sh"));
        assertTrue(launcher.contains("run_gradle :adapter:perfGateTest\n"),
                "the launcher's default gate invocation must pass no --tests filter, or the task's tag selection "
                        + "is intersected back down to whatever the launcher happens to name");
    }

    /**
     * <b>No timing assertion in the broad suite.</b> {@code :adapter:test} forks 2-4 JVMs in parallel, which is
     * the configuration CLAUDE.md forbids for a timed task and which manufactured a false 10.24x growth failure
     * once. Both tiers must be excluded there, so the exclusion cannot be half-applied.
     */
    @Test
    void theBroadAdapterSuiteExcludesBothPerformanceTiers() throws IOException {
        String build = Files.readString(locate("adapter/build.gradle"));
        int test = build.indexOf("tasks.named('test')");
        assertTrue(test >= 0, "adapter/build.gradle must configure its broad test task");
        String broad = build.substring(test, build.indexOf("\ntasks.register(", test));

        for (String tag : new TreeSet<>(PERF_TAGS)) {
            assertTrue(broad.contains("excludeTags '" + tag + "'"),
                    "the broad adapter suite must exclude @Tag(\"" + tag + "\"): it runs up to four parallel test "
                            + "forks, so a timing bar there measures contention rather than the interpreter");
        }
    }

    /** Every committed Java/Kotlin test source, excluding generated output and linked worktrees. */
    private static List<Path> testSources() throws IOException {
        Path root = locate("settings.gradle").getParent();
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith("Test.java") || name.endsWith("Test.kt");
                    })
                    .filter(path -> {
                        String text = root.relativize(path).toString();
                        return text.contains("src" + java.io.File.separator)
                                && !text.contains("build" + java.io.File.separator)
                                && !text.startsWith(".claude");
                    })
                    .toList();
        }
    }

    private static String className(Path source) {
        String name = source.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    /** The performance lane tag on a class, or {@code null}. Annotation lines only — prose quoting a tag is not one. */
    private static String perfTagOf(Path source) throws IOException {
        Matcher tag = Pattern.compile("^\\s*@Tag\\(\"(perf|perf-bench)\"\\)", Pattern.MULTILINE)
                .matcher(Files.readString(source));
        return tag.find() ? tag.group(1) : null;
    }

    private static String gradleTask(String build, String name) {
        String marker = "tasks.register('" + name + "', Test)";
        int start = build.indexOf(marker);
        assertTrue(start >= 0, () -> "could not find task " + name);
        int next = build.indexOf("\ntasks.register('", start + marker.length());
        return build.substring(start, next < 0 ? build.length() : next);
    }

    private static Path locate(String relativePath) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate " + relativePath);
    }
}
