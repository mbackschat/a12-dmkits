package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The wiring between the routine performance gate's lanes and their absolute-time anchor (IF280) — the one bar in
 * that estate that can see a <b>uniform</b> regression, since a growth ratio is unchanged by one at every factor.
 *
 * <p><b>Why this guard exists.</b> A cold review found the wiring unenforced: {@code PerfAnchorBudgetTest} injects
 * an {@code AnchorBudget} directly, so deleting the anchor call left every automated lane green — the gate is
 * perf-tagged and runs only in the nightly, where the hosted runner class has no committed block and is therefore
 * in the bootstrap state that never fails. The end-to-end evidence was a manual key-mutation run rather than a test.
 *
 * <p>Three things are asserted, all cheap and all on every push: the record-and-arm step is still present in the two
 * shared seams; no routine-tier class reaches the growth floor around them; and every anchored runner class carries
 * at least one lane. The middle one is the structural fix for IF296 — before it, eight of the nine
 * {@code @Tag("perf")} classes could measure and record nothing, because the seam lived in the ninth one's private
 * helper.
 *
 * <p>The oracle is source text, which is weaker than an executed assertion; it is chosen because the alternative
 * needs an injection seam through a perf-tagged class. What source text cannot answer is <i>completeness</i> — how
 * many lanes the tier actually produces at runtime — and the version of this class that tried anyway got it wrong
 * (see {@link #everyAnchoredRunnerClassCarriesAtLeastOneLane}). That question is answered by
 * {@code perf.anchor.required} in the gate itself. Its inputs are declared on {@code docGuardTest} (test sources are
 * already covered there; the anchor resource is declared beside them), so a source-only edit re-runs this guard
 * rather than the broad suite.
 */
class PerfAnchorCoverageGuardTest {

    private static final Path HARNESS =
            Path.of("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/PerfHarness.kt");
    private static final Path ADVANTAGE =
            Path.of("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/KernelAdvantage.kt");
    private static final Path ANCHORS = Path.of("adapter/src/test/resources/perf/anchors.json");

    private static Path locate(Path relative) {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path resolved = candidate.resolve(relative);
            if (Files.exists(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("cannot locate " + relative + " from " + here);
    }

    @Test
    void theRecordAndArmStepLivesInTheSharedSeams() throws IOException {
        String harness = Files.readString(locate(HARNESS));
        String advantage = Files.readString(locate(ADVANTAGE));

        assertTrue(harness.contains("fun assertSubQuadraticLane") && harness.contains("PerfAnchors.assertWithinBudget"),
                "PerfHarness.assertSubQuadraticLane must hold each lane's accepted medians against the committed "
                        + "anchor: without that call the growth floors are the only bar left, and no growth ratio "
                        + "can see a uniform slowdown at any factor (IF280)");
        assertTrue(harness.contains("accepted.smallMs") && harness.contains("accepted.largeMs"),
                "both scales must be anchored, and from the medians acceptance was decided on rather than a fresh "
                        + "sample");
        assertTrue(advantage.contains("PerfAnchors.assertWithinBudget"),
                "KernelAdvantage.assertBeatsKernelAndScales must anchor the interpreter's absolute medians too: "
                        + "both of its own bars are RELATIVE (interpreter to itself, and to the kernel in the same "
                        + "run), so a slowdown moving both arms together passes them unchanged");
    }

    /**
     * <b>No routine-tier class may reach the growth floor without going through a recording seam.</b>
     *
     * <p>This is the structural half of IF296, and the reason that gap existed: the record-and-arm step lived
     * inside {@code PerfGateTest}'s private helper, so the other eight {@code @Tag("perf")} classes could measure,
     * pass, and leave nothing in the committed record — invisible to {@code perf-history.sh}, the change-point
     * detection, the margin band and the anchor alike. Calling the raw
     * {@code PerfHarness.assertGrowthBelowCatastropheFloor} from a routine-tier class puts a lane back in exactly
     * that state, so it is refused here. The primitive stays available to the harness's own unit tests (which
     * script their ratios and must not record) and to the checkpoint tier.
     */
    @Test
    void noRoutineTierClassBypassesTheRecordingSeam() throws IOException {
        var bypassing = new java.util.TreeSet<String>();
        for (Path source : routineTierSources()) {
            String text = Files.readString(source);
            if (text.contains("PerfHarness.assertGrowthBelowCatastropheFloor(")) {
                bypassing.add(source.getFileName().toString());
            }
        }

        assertEquals(java.util.Set.of(), bypassing,
                "a @Tag(\"perf\") class called the raw growth-floor primitive, which asserts a ratio and records "
                        + "NOTHING. Use PerfHarness.assertSubQuadraticLane (growth floor + record + anchor) so the "
                        + "lane is visible to every trend instrument. Found: " + bypassing);
    }

    /**
     * <b>Every anchored runner class carries at least one lane.</b> A block with an empty observation map reads as
     * anchored and guards nothing.
     *
     * <p><b>What this deliberately does NOT assert, twice over.</b> First, it did assert
     * {@code lanes × 2 == observations} with {@code lanes} counted by matching call sites in the tier's Kotlin —
     * and that count is wrong the moment a lane is reached through a shared helper: {@code EntryPointPerfTest}
     * writes {@code assertBeatsKernelAndScales} <b>once</b> inside a private {@code measure()} that four
     * {@code @Test} methods call, so the text sees 1 lane where the run produces 4. Second, it then asserted that
     * every block carries the <i>same</i> key set — which is a legitimate state, not a defect: a refresh is per
     * environment and only a hosted run can refresh the hosted block, so adding a lane locally necessarily leaves
     * the two divergent until the next nightly. {@code perf-anchor.sh} already encodes exactly that
     * ({@code PerfAnchorScriptTest.anExistingBlockIsNotHeldToAnotherClassesDivergentLanes}), and a guard that fails
     * on a state the tooling is designed to produce gets deleted rather than obeyed.
     *
     * <p>Completeness is therefore answered where it is answered exactly: at RUNTIME, by
     * {@code perf.anchor.required} — an observation whose key has no committed value fails the gate rather than
     * passing quietly.
     */
    @Test
    void everyAnchoredRunnerClassCarriesAtLeastOneLane() throws IOException {
        // Parsed, not pattern-matched: the first version of this check used a regex over the raw text, assumed
        // `observations` was the block's first key, and silently matched NOTHING when it was third.
        JsonNode root = new ObjectMapper().readTree(locate(ANCHORS).toFile());
        JsonNode environments = root.path("environments");
        assertTrue(environments.isObject(), "the anchor file must carry an environments map");

        var perEnvironment = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        environments.fields().forEachRemaining(environment -> {
            var keys = new java.util.TreeSet<String>();
            environment.getValue().path("observations").fieldNames()
                    .forEachRemaining(key -> {
                        if (key.contains("|interpreter|")) {
                            keys.add(key);
                        }
                    });
            perEnvironment.put(environment.getKey(), keys);
        });

        assertTrue(!perEnvironment.isEmpty(), "no anchored runner class was parsed out of " + ANCHORS);
        perEnvironment.forEach((environment, keys) ->
                assertTrue(!keys.isEmpty(), "runner class '" + environment + "' carries no interpreter observation"));
    }

    /** Every {@code @Tag("perf")} class — the routine tier, read from source the way the classification guard does. */
    private static List<Path> routineTierSources() throws IOException {
        Path root = locate(Path.of("settings.gradle")).getParent();
        try (Stream<Path> tree = Files.walk(root.resolve("adapter/src/test"))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .filter(path -> {
                        try {
                            return Pattern.compile("^@Tag\\(\"perf\"\\)", Pattern.MULTILINE)
                                    .matcher(Files.readString(path)).find();
                        } catch (IOException failure) {
                            throw new IllegalStateException("cannot read " + path, failure);
                        }
                    })
                    .sorted()
                    .toList();
        }
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
