package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>No test may read a clock outside a lane that can measure.</b>
 *
 * <p>This is the source half of the lane boundary; {@code PerfLaneGate} is the runtime half, and neither subsumes
 * the other. The runtime gate sits on {@code PerfHarness}'s measuring entry points, so it sees a timing test
 * however it is named — but only if the timing goes <i>through</i> the harness. A test that calls
 * {@code System.nanoTime()} directly and asserts on the difference never reaches it, and a cold review named
 * exactly that bypass. This census closes it by reading the sources.
 *
 * <p><b>The exemption rule is the same one, derived the same way</b> (a performance lane tag, an
 * {@code @EnabledIfSystemProperty} gate, named infrastructure, or one reasoned entry), so the two halves cannot
 * drift into disagreeing about what counts as a lane.
 *
 * <p><b>Why a source census rather than a smarter detector.</b> Earlier attempts keyed on the bar-asserting
 * helpers and leaked in both directions: {@code PerfAnchorBudgetTest}, {@code GrowthFloorRetryTest},
 * {@code PairedAcceptanceTest}, {@code SteadyStateWarmUpTest} and {@code PerfStaleAnchorTest} all call those
 * helpers on <b>scripted</b> values and are correctly untagged, while {@code KernelParityCoverageGuardTest}
 * matched every pattern because it scans the perf sources as text. Reading a clock is the narrower and more
 * honest question: those six do not, so none of them is a false positive here.
 */
class RawClockCensusTest {

    /**
     * Clock APIs, matched narrowly enough not to collide with domain names. {@code TimeSource} is deliberately
     * qualified: A12 has its own {@code TimeSource} concept, and two kernel read tests name it in prose and in
     * types without touching a clock ({@code PointInTimeSourceReadTest}, {@code TimeAndDateTimeReadTest}).
     */
    private static final Pattern CLOCK = Pattern.compile(
            "System\\.nanoTime|System\\.currentTimeMillis|getCurrentThreadCpuTime|ThreadMXBean"
                    + "|kotlin\\.time\\.measureTime|measureTimeMillis|measureTime\\s*\\{|measureTimedValue"
                    + "|TimeSource\\.Monotonic|performance\\.now|Date\\.now");

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final Pattern LANE_TAG = Pattern.compile("^@Tag\\(\"(perf|perf-bench)\"\\)", Pattern.MULTILINE);
    private static final Pattern PROPERTY_GATED = Pattern.compile("^@EnabledIfSystemProperty", Pattern.MULTILINE);

    /**
     * Files that legitimately read a clock without a lane tag, each with the reason it is not a defect. Keyed by
     * file name because that is what the census reports; the list is short on purpose, and every entry that could
     * be replaced by a derived rule already has been.
     *
     * <p>A Kotlin-Multiplatform test source can only appear here. {@code @Tag} and
     * {@code @EnabledIfSystemProperty} are JUnit annotations, and those source sets run on {@code kotlin.test},
     * so the two derived escape hatches below are unreachable for them and an entry is the sole disposition.
     */
    /**
     * A measurement lane in a Kotlin-Multiplatform test source set, derived from its package. Those source sets
     * run on {@code kotlin.test}, so neither annotation hatch below can exist there and an entry in
     * {@link #ALLOWED} would otherwise be the only disposition — one that grows with the estate and whose reason
     * nothing re-checks. The package is the lane, so it is derived instead.
     */
    private static final Pattern MULTIPLATFORM_MEASUREMENT_PACKAGE =
            Pattern.compile("/src/(commonTest|jvmTest|jsTest)/kotlin/.*/(performance|footprint)/");

    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry("PerfHarness.kt",
                    "the measurement harness itself"),
            Map.entry("KernelAdvantage.kt",
                    "the cross-engine reading helper"),
            Map.entry("NativePerformanceRunner.java",
                    "drives an out-of-process native binary and times it"),
            Map.entry("NativePerformanceRunnerTest.java",
                    "asserts that runner's protocol, not a duration"),
            Map.entry("ModelReportSinglePassTest.java",
                    "its single timed method is @EnabledIfSystemProperty-gated at the METHOD level and self-describes as "
                    + "not a gate; the class-level rule below deliberately does not match a method annotation, "
                    + "because a class mixing a gated timing method with an ungated one must stay visible"),
            Map.entry("PerfGrowthEstimatorTest.kt",
                    "protects the CPU-clock contract every growth floor rests on, and is contention-SAFE: load only adds "
                    + "wall time, so both of its assertions move further into passing"),
            Map.entry("RawClockCensusTest.java",
                    "this census matches its own pattern literal — the same self-match that made every helper-name "
                    + "detector report KernelParityCoverageGuardTest, which scans the perf sources as text"));

    @Test
    void everyClockReadingTestSitsInALaneThatCanMeasure() throws IOException {
        var offenders = new TreeMap<String, String>();
        for (Path source : CHECKOUT.testSources()) {
            String text = uncommented(Files.readString(source));
            if (!CLOCK.matcher(text).find()) {
                continue;
            }
            String name = source.getFileName().toString();
            if (ALLOWED.containsKey(name)
                    || MULTIPLATFORM_MEASUREMENT_PACKAGE.matcher(CHECKOUT.relative(source)).find()
                    || LANE_TAG.matcher(text).find()
                    || PROPERTY_GATED.matcher(text).find()) {
                continue;
            }
            offenders.put(name, firstMatch(text));
        }

        assertEquals(Map.of(), offenders,
                "a test reads a clock outside any performance lane. :adapter:test and :cli:test fork JVMs in "
                        + "parallel, so a duration measured there is contention, not the interpreter — and a "
                        + "neutral name means neither PerfTestClassificationTest nor a reader of the directory "
                        + "listing can tell. Give the class @Tag(\"perf\")/@Tag(\"perf-bench\"), gate it behind "
                        + "@EnabledIfSystemProperty, or — if it genuinely must run per push — add it to ALLOWED "
                        + "with the reason its result cannot be changed by load. Found: " + offenders);
    }

    /**
     * <b>The allowlist must stay fully used.</b> An entry for a file that no longer reads a clock is an exemption
     * nobody is checking, and the way an allowlist decays into a blanket one is by accumulating those.
     */
    @Test
    void everyAllowlistEntryStillReadsAClock() throws IOException {
        var stale = new TreeMap<String, String>();
        var seen = new TreeMap<String, Boolean>();
        for (Path source : CHECKOUT.testSources()) {
            String name = source.getFileName().toString();
            if (ALLOWED.containsKey(name)) {
                seen.merge(name, CLOCK.matcher(uncommented(Files.readString(source))).find(), Boolean::logicalOr);
            }
        }
        ALLOWED.forEach((name, reason) -> {
            if (!Boolean.TRUE.equals(seen.get(name))) {
                stale.put(name, seen.containsKey(name) ? "no longer reads a clock" : "no such test source");
            }
        });

        assertEquals(Map.of(), stale,
                "the raw-clock allowlist carries entries that no longer need one; drop them, or the exemption "
                        + "outlives its reason. Found: " + stale);
    }

    /**
     * Source with comments removed, so a file that DOCUMENTS a clock API does not read as one that calls it.
     * This class and {@code PerfLaneGate} both name {@code System.nanoTime()} in prose to explain the bypass they
     * exist to close, and the first version of the census duly reported both as offenders.
     */
    private static String uncommented(String source) {
        String withoutBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll("");
        return withoutBlocks.lines()
                .filter(line -> !line.strip().startsWith("//"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** A short excerpt of the offending line, so the failure points somewhere rather than merely naming a file. */
    private static String firstMatch(String text) {
        return uncommented(text).lines()
                .filter(line -> CLOCK.matcher(line).find())
                .findFirst().orElse("?").strip();
    }


    @Test
    void theCensusActuallyMatchesTheClockApisItClaimsTo() {
        for (String snippet : List.of(
                "val t = System.nanoTime()", "long t = System.currentTimeMillis();",
                "bean.getCurrentThreadCpuTime()", "val d = measureTime { work() }",
                "TimeSource.Monotonic.markNow()", "performance.now()", "Date.now()")) {
            assertTrue(CLOCK.matcher(snippet).find(), () -> "the census pattern does not match: " + snippet);
        }
        // …and does NOT match A12's own TimeSource domain type, which is why the pattern is qualified.
        assertTrue(!CLOCK.matcher("PointInTime source = TimeSource.of(model);").find(),
                "A12's TimeSource domain type must not read as a clock");
    }
}
