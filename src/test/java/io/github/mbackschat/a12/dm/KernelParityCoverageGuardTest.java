package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>Every growth-gated scenario is also compared against BOTH kernel strategies.</b>
 *
 * <p><b>Why this guard exists.</b> "The interpreter is always faster than the kernel, including scaling" is the
 * estate's stated first priority, and it was asserted on <b>two</b> scenarios. The eighteen growth lanes time the
 * interpreter alone — cheap at N=20,000, and blind to the kernel by construction — while the benchmark tier's
 * only relative bar is {@code RATIO_CEILING}, which is {@code interp/groovy < 5.0} and therefore tolerates the
 * interpreter being five times <i>slower</i>, with nothing at all against generated Java. Fifteen scenarios could
 * have lost to the kernel without a red build. {@code KernelParityGateTest} closed that; this stops it reopening,
 * because the cheap way to add a lane is to copy a growth lane, and a growth lane carries no kernel.
 *
 * <p>The oracle is source text, which is weaker than an executed assertion. It is chosen for the same reason
 * {@code PerfAnchorCoverageGuardTest} chose it: the alternative needs an injection seam through a perf-tagged
 * class that runs only in the nightly, and the set-difference half cannot be faked by a comment.
 */
class KernelParityCoverageGuardTest {

    private static final Path GATE =
            Path.of("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/PerfGateTest.kt");
    private static final Path PARITY =
            Path.of("adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/KernelParityGateTest.kt");

    /**
     * A growth-gated scenario that legitimately has no parity lane of its own, with the reason. Kept as a map so
     * an exemption must be argued rather than added to a list, and asserted to stay fully used below.
     */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<>(Map.of(
            "complexRealWorldFull",
            "the ~1,000-rule shape; generating and compiling it for both kernel strategies twice per lane is the "
                    + "cost the two-tier split exists to avoid. Its parity is measured on the reduced ~100-rule "
                    + "complexRealWorldGate, which is the same construct mix, and the full model keeps the "
                    + "interpreter-only interaction-effect growth floor"));

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

    /** The {@code PerfCatalog} factories named inside a {@code @Test} whose body calls {@code marker}. */
    private static Set<String> scenariosGatedBy(Path source, String marker) throws IOException {
        var found = new LinkedHashSet<String>();
        for (String body : Files.readString(locate(source)).split("\n    @Test\n")) {
            if (!body.contains(marker)) {
                continue;
            }
            Matcher factory = Pattern.compile("PerfCatalog\\.(\\w+)\\(").matcher(body);
            while (factory.find()) {
                found.add(factory.group(1));
            }
        }
        return found;
    }

    @Test
    void everyGrowthGatedScenarioIsAlsoComparedAgainstBothKernelStrategies() throws IOException {
        Set<String> growth = scenariosGatedBy(GATE, "assertSubQuadratic(");
        Set<String> parity = scenariosGatedBy(PARITY, "assertBeatsBothKernelStrategies(");

        // Both halves before the set difference: an empty scan makes the difference empty too, so the guard would
        // pass loudest exactly when it had stopped reading anything.
        assertTrue(growth.size() >= 15,
                "expected the growth lanes to name at least 15 scenarios, found " + growth.size() + " " + growth
                        + " — the scan stopped matching, so the comparison below is vacuous");
        assertTrue(parity.size() >= 15,
                "expected the parity lanes to name at least 15 scenarios, found " + parity.size() + " " + parity
                        + " — same vacuity risk in the other direction");

        var uncovered = new TreeSet<>(growth);
        uncovered.removeAll(parity);
        uncovered.removeAll(EXEMPT.keySet());
        assertEquals(Set.of(), uncovered, () -> "these scenarios carry a growth floor but no assertion that the "
                + "interpreter beats both kernel strategies, so they could lose to the kernel with a green build:\n  "
                + String.join("\n  ", uncovered)
                + "\n\nAdd a lane to KernelParityGateTest — one line, at roughly a tenth of the growth lane's "
                + "scale, since a per-unit cost needs two scales and not large ones. If a scenario genuinely "
                + "cannot carry one, declare it in " + KernelParityCoverageGuardTest.class.getSimpleName()
                + ".EXEMPT with the reason");

        for (String exempt : EXEMPT.keySet()) {
            assertTrue(growth.contains(exempt), () -> "\"" + exempt + "\" is exempted from parity coverage but no "
                    + "longer carries a growth floor — delete the entry so the exemptions keep meaning what they say");
            assertTrue(!parity.contains(exempt), () -> "\"" + exempt + "\" is exempted but now HAS a parity lane — "
                    + "delete the exemption, which is now claiming something untrue about the estate");
        }
    }

    /**
     * The parity lanes must reach the comparative helper, not merely exist. Deleting the
     * {@code pairedScenarioGrowth} call would leave seventeen green tests that time three engines and compare
     * nothing — the same shape of silent disarming that made {@code PerfAnchorCoverageGuardTest} necessary.
     */
    @Test
    void theParityLanesStillRunTheComparativeBars() throws IOException {
        String parity = Files.readString(locate(PARITY));

        assertTrue(parity.contains("PerfHarness.pairedScenarioGrowth("),
                "KernelParityGateTest must route through pairedScenarioGrowth: it is the only production path that "
                        + "reaches requireInterpreterBeatsBoth and PairedAcceptance, so without it these lanes "
                        + "measure three engines and assert nothing about their relationship");
        assertTrue(parity.contains("ABS_CEILING_MS"),
                "…and keep the absolute backstop, which pairedScenarioGrowth deliberately does not own");
    }
}
