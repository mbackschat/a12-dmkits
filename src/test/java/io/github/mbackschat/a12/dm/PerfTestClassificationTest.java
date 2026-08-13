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
 *       {@code filter} block, and that produced <b>two distinct defects</b> across the 9 routine-tier timing
 *       bars: <b>five</b> were tagged and ran in no lane at all, while <b>two</b> ({@code CharsetPerfTest},
 *       {@code CustomTypePerfTest}) carried no tag and therefore ran inside the broad 2-4-fork suite, where a
 *       timing observation measures contention. Missing coverage and <i>invalid</i> coverage, not one thing —
 *       the discriminator is the tag census at the parent commit. A {@code --tests} pattern could not widen the
 *       lane either, because Gradle ANDs the command-line filter with the task's own (measured 2026-08-13:
 *       {@code --tests "*CharsetPerfTest"} against that filter matched nothing). The lane now selects
 *       {@code includeTags 'perf'}, which is what {@link #theRoutinePerformanceGateSelectsByTag} pins.</li>
 * </ol>
 *
 * <p><b>The bound of this guard, and what covers it.</b> This one is deliberately syntactic — a name and a tag,
 * both exact — so it can never produce a false red, and it therefore cannot recognize a timing bar written under a
 * neutral name with no tag at all ({@code FooTest} calling {@code PerfHarness.timeInterpreter}). Several text
 * detectors for "asserts a machine measurement" were tried and each leaked in both directions: the harness's own
 * unit tests call {@code assertWithinBudget} and {@code assertGrowthBelowCatastropheFloor} on scripted values and
 * are correctly untagged, while {@code KernelParityCoverageGuardTest} matched every pattern because it scans the
 * perf sources as text.
 *
 * <p>That residue is closed by two <b>other</b> mechanisms rather than by widening this one (IF297):
 * {@code PerfLaneGate} refuses at runtime when a real measurement is taken from a class in no lane, and
 * {@code RawClockCensusTest} reads the sources for the clock APIs themselves — which is the half a runtime seam
 * cannot see, since a direct {@code System.nanoTime()} never reaches the harness. Both use the same derived
 * exemption rule as this class, so the three cannot disagree about what a lane is.
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
            if (perfTagOf(source) != null) {
                continue;
            }
            for (String name : declaredNames(source)) {
                if (name.endsWith("PerfTest")) {
                    untagged.add(name);
                }
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
        // ANY declared name may carry a measuring suffix, not EVERY one: a perf-tagged file legitimately declares
        // private helpers beside its test class (`ProfileCliPerfTest.kt` declares `object ProfileCliCanary`), and
        // requiring every declaration to be named for a measurement fails those for no reason. What must not exist
        // is a perf-tagged file where NOTHING is named for what it measures.
        var misnamed = new TreeMap<String, String>();
        for (var source : testSources()) {
            String tag = perfTagOf(source);
            if (tag == null) {
                continue;
            }
            var names = declaredNames(source);
            if (names.stream().noneMatch(name -> MEASURING_SUFFIXES.stream().anyMatch(name::endsWith))) {
                misnamed.put(String.join("/", names), tag);
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
        // Any indentation (so a METHOD-level tag is seen too) and a permissive tag body: the character class was
        // `[a-z-]*`, which silently accepted `perf2` and `perf_bench` — a typo that selects no lane at all, which
        // is the exact failure this assertion exists to catch. Digits and underscores are now inside it.
        var found = new TreeSet<String>();
        for (var source : testSources()) {
            Matcher tags = Pattern.compile("^\\s*@Tag\\(\"(perf[A-Za-z0-9_-]*)\"\\)", Pattern.MULTILINE)
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
     * <b>A lane tag is CLASS-level, at column 0.</b> {@link #perfTagOf} recognizes exactly that form, which makes
     * an <i>indented</i> perf tag invisible to it — a valid class-level tag on a nested class, or a method-level
     * one, would read as untagged and silently escape both directions above. Rather than widen the pattern (which
     * would let one tagged method make a whole file read as tagged, the hole a review found), the alternative form
     * is <b>refused</b> here with the diagnostic that says why. That is what makes column-0 recognition complete
     * instead of merely conventional.
     */
    @Test
    void everyPerformanceLaneTagSitsAtClassLevel() throws IOException {
        var indented = new TreeMap<String, String>();
        Pattern anywhere = Pattern.compile("^(\\s+)@Tag\\(\"(perf|perf-bench)\"\\)", Pattern.MULTILINE);
        for (var source : testSources()) {
            Matcher tags = anywhere.matcher(Files.readString(source));
            while (tags.find()) {
                indented.put(source.getFileName().toString(), tags.group(2));
            }
        }

        assertEquals(Set.of(), indented.keySet(),
                "a performance lane tag must sit on the CLASS at column 0. An indented @Tag is either method-level "
                        + "(which puts one method in the lane while the file's other timing methods stay in the "
                        + "broad parallel-fork suite) or on a nested class (which no reader of a directory listing "
                        + "can see). Both are refused so that the class-level form is the only one, and the "
                        + "classification checks can rely on it. Found: " + indented);
    }

    /**
     * <b>The routine gate selects by TAG, never by a class list.</b> This is what makes "full coverage" structural
     * instead of a promise: a new {@code @Tag("perf")} class joins the lane by existing. The enumerated
     * {@code filter} this replaced is the exact mechanism by which 7 bars ran nowhere.
     */
    @Test
    void theRoutinePerformanceGateSelectsByTag() throws IOException {
        // Comment-stripped: `contains` cannot tell live configuration from a line that quotes it, and this task's
        // block is heavily commented — commenting out the selection while leaving the text would otherwise pass.
        String gate = uncommented(gradleTask(Files.readString(locate("adapter/build.gradle")), "perfGateTest"));

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
     * <b>Neither performance tier runs in the broad suite.</b> {@code :adapter:test} forks 2-4 JVMs in parallel,
     * which is the configuration CLAUDE.md forbids for a timed task and which manufactured a false 10.24x growth
     * failure once. Both tiers must be excluded there, so the exclusion cannot be half-applied.
     *
     * <p>This is an assertion about the two <i>tiers</i>, not the stronger claim that the broad suite contains no
     * timing assertion at all: {@code PerfGrowthEstimatorTest.currentThreadCpuExcludesBlockedWallTime} deliberately
     * remains there, because it protects the CPU-clock contract every growth floor rests on and must be answered
     * per push. Its exposure is directional — contention only adds wall time and leaves CPU-during-sleep near zero,
     * so load pushes both of its assertions further into passing. IF297 owns the general residue.
     */
    @Test
    void theBroadAdapterSuiteExcludesBothPerformanceTiers() throws IOException {
        String build = Files.readString(locate("adapter/build.gradle"));
        int test = build.indexOf("tasks.named('test')");
        assertTrue(test >= 0, "adapter/build.gradle must configure its broad test task");
        String broad = uncommented(build.substring(test, build.indexOf("\ntasks.register(", test)));

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

    /**
     * Every type this file DECLARES, plus its filename stem.
     *
     * <p>The filename alone was the denominator and that is unsound in Kotlin, which does not require the two to
     * agree: `NeutralTest.kt` may declare `class HiddenPerfTest`, and a filename-keyed check would never see it.
     * The stem is retained as well, because a Java file's public class must match it and keeping both costs
     * nothing — the union can only widen what the assertions inspect.
     */
    private static Set<String> declaredNames(Path source) throws IOException {
        var names = new TreeSet<String>();
        String fileName = source.getFileName().toString();
        names.add(fileName.substring(0, fileName.lastIndexOf('.')));
        Matcher declared = Pattern.compile(
                        "^(?:(?:public|final|abstract|internal|open|private|sealed|data|annotation)\\s+)*"
                                + "(?:class|object|interface)\\s+(\\w+)", Pattern.MULTILINE)
                .matcher(Files.readString(source));
        while (declared.find()) {
            names.add(declared.group(1));
        }
        return names;
    }

    /**
     * The CLASS-level performance lane tag, or {@code null}.
     *
     * <p><b>Column 0, deliberately — and the alternative is refused, not merely unmatched.</b> A top-level class
     * annotation is unindented in both languages, while a method-level {@code @Tag} is indented inside the class
     * body. Matching any indentation would let one tagged method make a class read as tagged, so a file could carry
     * an <i>untagged</i> timing method beside it and pass. But leaving the indented form merely unmatched hid a
     * second hole: a valid class-level tag on a nested class would read as untagged.
     * {@link #everyPerformanceLaneTagSitsAtClassLevel} closes that by failing on any indented perf tag, so this
     * pattern's denominator is the whole estate rather than a convention. The vocabulary assertion deliberately
     * keeps the looser pattern, because a lane-selecting typo is worth catching wherever it sits.
     */
    private static String perfTagOf(Path source) throws IOException {
        Matcher tag = Pattern.compile("^@Tag\\(\"(perf|perf-bench)\"\\)", Pattern.MULTILINE)
                .matcher(Files.readString(source));
        return tag.find() ? tag.group(1) : null;
    }

    /**
     * Source with comments dropped, so a check matches LIVE configuration and never a line quoting it.
     *
     * <p><b>Both comment forms.</b> Line comments alone left a bypass a review constructed: wrapping the live
     * selection in {@code /* … *}{@code /} keeps the text present, so a {@code contains} check on it passes while
     * the configuration is dead. Block comments are stripped first, since a block may open and close mid-line.
     */
    private static String uncommented(String source) {
        String withoutBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll("");
        return withoutBlocks.lines()
                .filter(line -> !line.strip().startsWith("//"))
                .collect(java.util.stream.Collectors.joining("\n"));
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
