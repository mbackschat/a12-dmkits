package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;

/**
 * Fresh-state execution (proposal §3): re-running the full case set in a DIFFERENT order, and running a
 * filtered SINGLE case, preserves every case's observation exactly — no cache, engine, or document state
 * leaks between cases or routes. Every runner builds a fresh engine per (case, runner), so order cannot
 * matter; this test is the guard that keeps it that way.
 */
class ReorderEquivalenceTest {

    @TempDir
    static Path root;

    @Test
    void reorderedAndFilteredRunsObserveIdentically() {
        ScenarioSets.Loaded loaded = ScenarioSets.load(CaptureFixtures.writeThreeCaseScenario(root));
        String modelJson = new String(loaded.models().get("models/quote-premium.json").bytes(),
                StandardCharsets.UTF_8);
        List<ScenarioSet.ScenarioCase> cases = loaded.set().cases();
        assertEquals(3, cases.size());

        for (RunnerId runner : RunnerId.values()) {
            Map<String, ComputeObservation> fullRun = observeAll(cases, modelJson, runner);
            Map<String, ComputeObservation> reversedRun = observeAll(cases.reversed(), modelJson, runner);
            assertEquals(fullRun, reversedRun,
                    runner.wire() + ": a reordered full run must observe identically (no cross-case state)");

            for (ScenarioSet.ScenarioCase single : cases) {
                ComputeObservation filtered = Runners.observe(runner,
                        CaseExecution.of(single, modelJson, "en_US"));
                assertEquals(fullRun.get(single.caseId()), filtered,
                        runner.wire() + ": the filtered single-case run must equal the full run's observation");
            }
        }
    }

    private static Map<String, ComputeObservation> observeAll(List<ScenarioSet.ScenarioCase> cases,
            String modelJson, RunnerId runner) {
        Map<String, ComputeObservation> byCase = new LinkedHashMap<>();
        for (ScenarioSet.ScenarioCase c : cases) {
            byCase.put(c.caseId(), Runners.observe(runner, CaseExecution.of(c, modelJson, "en_US")));
        }
        return byCase;
    }
}
