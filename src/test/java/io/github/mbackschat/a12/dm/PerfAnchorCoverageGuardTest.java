package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The wiring between the routine performance gate's lanes and their absolute-time anchor (IF280) — the one bar in
 * that estate that can see a <b>uniform</b> regression, since a growth ratio is unchanged by one at every factor.
 *
 * <p><b>Why this guard exists.</b> A cold review found the wiring unenforced: {@code PerfAnchorBudgetTest} injects
 * an {@code AnchorBudget} directly, so deleting the anchor call from {@code PerfGateTest.assertSubQuadratic} left
 * every automated lane green — the gate itself is perf-tagged and runs only in the nightly, where the hosted runner
 * class has no committed block and is therefore in the bootstrap state that never fails. The end-to-end evidence was
 * a manual key-mutation run rather than a test. Two things are asserted here, both cheap and both on every push:
 * that the call is still there, and that the committed anchor covers <b>every</b> lane at <b>both</b> scales — so
 * adding a lane without anchoring it, or dropping anchors, fails the build instead of quietly widening the band.
 *
 * <p>The oracle is source text, which is weaker than an executed assertion; it is chosen because the alternative
 * needs an injection seam through a perf-tagged class, and because the count half genuinely cannot be faked by a
 * comment. Its inputs are declared on {@code docGuardTest} (test sources are already covered there; the anchor
 * resource is declared beside them), so a source-only edit re-runs this guard rather than the broad suite.
 */
class PerfAnchorCoverageGuardTest {

    private static final Path GATE =
            Path.of("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/PerfGateTest.kt");
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
    void everyGrowthLaneStillReachesTheAnchor() throws IOException {
        String gate = Files.readString(locate(GATE));

        assertTrue(gate.contains("PerfAnchors.assertWithinBudget"),
                "PerfGateTest must hold each lane's accepted medians against the committed anchor: without that "
                        + "call the growth floors are the only bar left, and no growth ratio can see a uniform "
                        + "slowdown at any factor (IF280)");
        assertTrue(gate.contains("accepted.smallMs") && gate.contains("accepted.largeMs"),
                "both scales must be anchored, and from the medians acceptance was decided on rather than a fresh "
                        + "sample");
    }

    /**
     * Every lane, at both scales, must carry a committed number for each anchored runner class — the enumeration
     * that makes "the 18 growth lanes are anchored" a checked claim instead of a sentence in a findings doc.
     */
    @Test
    void theCommittedAnchorCoversEveryLaneAtBothScales() throws IOException {
        String gate = Files.readString(locate(GATE));
        String anchors = Files.readString(locate(ANCHORS));

        int declarations = count(gate, "private fun assertSubQuadratic\\(");
        assertEquals(1, declarations, "expected exactly one assertSubQuadratic helper to subtract from the count");
        int lanes = count(gate, "assertSubQuadratic\\(") - declarations;
        assertTrue(lanes > 0, "PerfGateTest must have growth lanes to anchor");

        for (String environment : count(anchors, "\"environments\"") == 1
                ? anchors.split("\"observations\"")
                : new String[0]) {
            // Each block's observation count is the number of `"…|cpu":` / `"…|wall":` keys inside it.
            if (!environment.contains("|interpreter|")) {
                continue;
            }
            int observations = count(environment, "\\|interpreter\\|(cpu|wall)\": ");
            assertEquals(lanes * 2, observations,
                    "each anchored runner class must carry every lane at both scales: " + lanes + " lanes × 2 = "
                            + (lanes * 2) + " observations, found " + observations
                            + ". Adding a lane without refreshing the anchor leaves it unguarded, and an anchor "
                            + "the lanes no longer produce is dead weight (scripts/perf-anchor.sh)");
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
