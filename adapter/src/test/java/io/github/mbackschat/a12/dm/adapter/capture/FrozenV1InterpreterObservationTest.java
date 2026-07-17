package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.dto.AppliedState;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerFidelity;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter;
import io.github.mbackschat.a12.dm.interpreter.eval.Document;
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentKt;
import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState;
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef;

/**
 * The FROZEN-V1 COMPATIBILITY GUARD (CONFORMANCE-CORPUS-SPEC §15a "the V1 freeze record"): richer
 * INTERNAL interpreter state must not change the frozen V1 adapter output. Since IF126 the interpreter's
 * public {@code Document.cellStateAt} DOES separate absent from present-empty — this test builds exactly
 * that state-rich situation (the same absent-vs-EMPTY-placed pair as {@code AppliedStateDistinctionTest})
 * and pins the frozen V1 interpreter-observation shape over it: the registry still declares
 * {@code absentVsPresentEmpty = notExposedByRunner} and the observation reports the honest
 * {@code noValueIndeterminate} for BOTH probes. Exposing the richer state through capture is an
 * {@code a12-dmkits-capture-capabilities-v2} decision, never an in-place change under the frozen V1 id.
 * Does-fire proven by hand (Set Z4): wiring {@code cellStateAt} through the runner's applied read-back
 * (the reverted Set AB change) fails {@code theFrozenV1ObservationStaysIndeterminate}.
 */
class FrozenV1InterpreterObservationTest {

    private static ScenarioSets.Loaded loaded;
    private static String modelJson;
    private static ScenarioSet.ScenarioCase absentProbe;
    private static ScenarioSet.ScenarioCase emptyProbe;

    @TempDir
    static Path root;

    @BeforeAll
    static void loadTheStateRichPair() {
        loaded = ScenarioSets.load(CaptureFixtures.writeValidScenario(root));
        modelJson = new String(loaded.models().get("models/quote-premium.json").bytes(),
                StandardCharsets.UTF_8);
        // The base case places BaseRate (valued) + Premium (EMPTY, computed) — and NOTHING for
        // InsuredName; probing InsuredName absent beside an EMPTY-placed variant is the state-rich pair.
        ScenarioSet.ScenarioCase base = loaded.set().cases().get(0);
        absentProbe = new ScenarioSet.ScenarioCase("insured-absent",
                base.modelRef(), Optional.empty(), base.placements(), List.of("/Quote[1]/InsuredName"));
        List<ScenarioSet.Placement> withEmpty = new java.util.ArrayList<>(base.placements());
        withEmpty.add(new ScenarioSet.Placement(ScenarioSet.PlacementKind.EMPTY,
                "/Quote/InsuredName", List.of(1, 1), Optional.empty()));
        emptyProbe = new ScenarioSet.ScenarioCase("insured-empty",
                base.modelRef(), Optional.empty(), withEmpty, List.of("/Quote[1]/InsuredName"));
    }

    /** The premise: on this exact pair the interpreter's OWN public read (IF126) is richer than the
     *  frozen V1 wire — the applied documents distinguish ABSENT from PRESENT-EMPTY. */
    @Test
    void theInterpreterInternallyDistinguishesTheStates() {
        assertEquals(StoredCellState.Absent.INSTANCE, appliedCellState(absentProbe),
                "IF126 premise: the applied document reads the unplaced cell as ABSENT");
        assertEquals(StoredCellState.PresentEmpty.INSTANCE, appliedCellState(emptyProbe),
                "IF126 premise: the applied document reads the EMPTY-placed cell as PRESENT-EMPTY");
    }

    /** The pin: despite that richer internal state, the frozen V1 observation reports the honest
     *  {@code noValueIndeterminate} for BOTH probes — the richer read never leaks onto the V1 wire. */
    @Test
    void theFrozenV1ObservationStaysIndeterminate() {
        assertInstanceOf(AppliedState.NoValueIndeterminate.class, observedState(absentProbe),
                "frozen V1: the interpreter lane must not leak the richer cellStateAt read (absent)");
        assertInstanceOf(AppliedState.NoValueIndeterminate.class, observedState(emptyProbe),
                "frozen V1: the interpreter lane must not leak the richer cellStateAt read (present-empty)");
    }

    /** The declaration half: the registry fidelity still declares the distinction not exposed. */
    @Test
    void theFrozenV1FidelityDeclaresTheDistinctionNotExposed() {
        assertEquals(Availability.NOT_EXPOSED_BY_RUNNER,
                RunnerFidelity.of(RunnerId.A12_DMKITS_INTERPRETER).absentVsPresentEmpty(),
                "frozen V1: absentVsPresentEmpty stays notExposedByRunner on the interpreter lane");
    }

    private static StoredCellState appliedCellState(ScenarioSet.ScenarioCase scenarioCase) {
        ModelDef def = ModelLoader.INSTANCE.load(modelJson);
        Document doc = DocumentKt.newDocument(def);
        for (ScenarioSet.Placement p : scenarioCase.placements()) {
            int[] reps = p.reps().stream().mapToInt(Integer::intValue).toArray();
            switch (p.kind()) {
                case GROUP -> doc.group(p.path(), reps);
                case FIELD -> doc.field(p.path(), p.value().orElseThrow(), reps);
                case EMPTY -> doc.emptyField(p.path(), reps);
            }
        }
        Document applied = new DmInterpreter(def, null).applyComputations(doc, "en_US");
        return applied.cellStateAt("/Quote/InsuredName", List.of(1, 1));
    }

    private static AppliedState observedState(ScenarioSet.ScenarioCase scenarioCase) {
        ComputeObservation observation = Runners.observe(RunnerId.A12_DMKITS_INTERPRETER,
                CaseExecution.of(scenarioCase, modelJson, "en_US"));
        for (ComputeObservation.AppliedEntry entry : observation.appliedState().entries()) {
            if (entry.pointer().equals("/Quote[1]/InsuredName")) {
                return entry.state();
            }
        }
        throw new IllegalStateException("the probed pointer is missing from the applied-state channel");
    }
}
