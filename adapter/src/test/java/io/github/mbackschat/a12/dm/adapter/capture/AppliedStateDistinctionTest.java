package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.dto.AppliedState;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;

/**
 * ABSENT vs PRESENT-EMPTY stay DISTINCT in the applied-state channel (proposal §4, the "collapsed"
 * mutation class): a probe naming a cell no placement created reads {@code absent}; an EMPTY-placed
 * (present-but-valueless) cell reads {@code presentEmpty}; both kernel routes agree; and the interpreter
 * reports the SAME real states through its public {@code Document.cellStateAt} (IF126, graduated from
 * IG72 — the distinction is no longer kernel-routes-only evidence, and it is still never guessed: the
 * runner maps the interpreter's own read, one state per probe).
 */
class AppliedStateDistinctionTest {

    private static Map<String, AppliedState> groovyStates;
    private static Map<String, AppliedState> javaStates;
    private static Map<String, AppliedState> interpreterStates;

    @TempDir
    static Path root;

    @BeforeAll
    static void observe() {
        ScenarioSets.Loaded loaded = ScenarioSets.load(CaptureFixtures.writeValidScenario(root));
        String modelJson = new String(loaded.models().get("models/quote-premium.json").bytes(),
                StandardCharsets.UTF_8);
        // The base case places BaseRate (valued) + Premium (EMPTY, computed) — and NOTHING for InsuredName.
        // Probing InsuredName (absent) beside an EMPTY-placed InsuredName variant separates the two states.
        ScenarioSet.ScenarioCase base = loaded.set().cases().get(0);
        ScenarioSet.ScenarioCase absentProbe = new ScenarioSet.ScenarioCase("insured-absent",
                base.modelRef(), Optional.empty(), base.placements(), List.of("/Quote[1]/InsuredName"));
        ScenarioSet.ScenarioCase emptyProbe = new ScenarioSet.ScenarioCase("insured-empty",
                base.modelRef(), Optional.empty(),
                append(base.placements(), new ScenarioSet.Placement(ScenarioSet.PlacementKind.EMPTY,
                        "/Quote/InsuredName", List.of(1, 1), Optional.empty())),
                List.of("/Quote[1]/InsuredName"));

        groovyStates = statesOf(RunnerId.KERNEL_GROOVY_DYNAMIC, modelJson, absentProbe, emptyProbe);
        javaStates = statesOf(RunnerId.KERNEL_JAVA_STATIC, modelJson, absentProbe, emptyProbe);
        interpreterStates = statesOf(RunnerId.A12_DMKITS_INTERPRETER, modelJson, absentProbe, emptyProbe);
    }

    private static List<ScenarioSet.Placement> append(List<ScenarioSet.Placement> placements,
            ScenarioSet.Placement extra) {
        List<ScenarioSet.Placement> out = new java.util.ArrayList<>(placements);
        out.add(extra);
        return out;
    }

    private static Map<String, AppliedState> statesOf(RunnerId runner, String modelJson,
            ScenarioSet.ScenarioCase... cases) {
        Map<String, AppliedState> states = new java.util.LinkedHashMap<>();
        for (ScenarioSet.ScenarioCase c : cases) {
            ComputeObservation observation = Runners.observe(runner,
                    CaseExecution.of(c, modelJson, "en_US"));
            for (ComputeObservation.AppliedEntry entry : observation.appliedState().entries()) {
                if (entry.pointer().equals("/Quote[1]/InsuredName")) {
                    states.put(c.caseId(), entry.state());
                }
            }
        }
        return states;
    }

    @Test
    void theKernelRoutesKeepAbsentAndPresentEmptyDistinct() {
        assertInstanceOf(AppliedState.Absent.class, groovyStates.get("insured-absent"),
                "an unplaced cell reads ABSENT after application");
        assertInstanceOf(AppliedState.PresentEmpty.class, groovyStates.get("insured-empty"),
                "an EMPTY-placed cell reads PRESENT-EMPTY — never collapsed into absent");
        assertEquals(groovyStates.get("insured-absent"), javaStates.get("insured-absent"));
        assertEquals(groovyStates.get("insured-empty"), javaStates.get("insured-empty"));
    }

    @Test
    void theInterpreterReportsTheSameRealStatesAsTheKernelRoutes() {
        assertInstanceOf(AppliedState.Absent.class, interpreterStates.get("insured-absent"),
                "IF126: cellStateAt separates the states — an unplaced cell reads ABSENT");
        assertInstanceOf(AppliedState.PresentEmpty.class, interpreterStates.get("insured-empty"),
                "IF126: an EMPTY-placed cell reads PRESENT-EMPTY — never collapsed, never indeterminate");
    }
}
