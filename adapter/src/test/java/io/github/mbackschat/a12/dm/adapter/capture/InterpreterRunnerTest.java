package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.dto.AppliedState;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunStatus;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;

/**
 * The interpreter TRIANGULATION lane (proposal §4): its observation is honest about fidelity — rendered
 * values only (typed {@code notExposedByRunner}), no structured causes, no changed subset (report-all
 * granularity), no operand formal errors. Applied probes carry the real absent / present-empty /
 * present-value states via {@code Document.cellStateAt} (IF126; the distinction lock is
 * {@code AppliedStateDistinctionTest}). Missing transport is DECLARED, never rendered as an empty
 * observation — and the rendered value it does expose agrees with the kernel anchor's rendered form.
 */
class InterpreterRunnerTest {

    private static ComputeObservation interpreter;
    private static ComputeObservation anchor;

    @TempDir
    static Path root;

    @BeforeAll
    static void observe() {
        ScenarioSets.Loaded loaded = ScenarioSets.load(CaptureFixtures.writeValidScenario(root));
        CaseExecution execution = CaseExecution.of(loaded.set().cases().get(0),
                new String(loaded.models().get("models/quote-premium.json").bytes(), StandardCharsets.UTF_8),
                "en_US");
        interpreter = Runners.observe(RunnerId.A12_DMKITS_INTERPRETER, execution);
        anchor = Runners.observe(RunnerId.KERNEL_GROOVY_DYNAMIC, execution);
    }

    @Test
    void succeedsWithRenderedOnlyValues() {
        assertEquals(RunStatus.SUCCESS, interpreter.status());
        assertEquals(1, interpreter.withoutErrors().entries().size());
        ComputeObservation.ValueEntry entry = interpreter.withoutErrors().entries().get(0);
        assertEquals("/Quote[1]/Premium", entry.target());
        assertEquals("/Quote/PremiumCalc", entry.declaredComputation());
        assertEquals(Availability.NOT_EXPOSED_BY_RUNNER, entry.value().typedAvailability(),
                "the interpreter transports no typed valueV2 — declared, never derived");
        assertNull(entry.value().typed());
        assertEquals(Availability.AVAILABLE, entry.value().renderedAvailability());
    }

    @Test
    void theRenderedValueAgreesWithTheKernelAnchorsRenderedForm() {
        String interpreterRendered = interpreter.withoutErrors().entries().get(0).value().rendered();
        String anchorRendered = anchor.withoutErrors().entries().get(0).value().rendered();
        assertEquals(anchorRendered, interpreterRendered);
    }

    @Test
    void unexposedChannelsAreDeclaredNotEmpty() {
        assertEquals(Availability.NOT_EXPOSED_BY_RUNNER, interpreter.changedSubset().availability(),
                "report-all granularity exposes no changed subset");
        assertTrue(interpreter.changedSubset().entries().isEmpty());
        assertEquals(Availability.NOT_EXPOSED_BY_RUNNER, interpreter.formalErrorsInOperands().availability());
    }

    @Test
    void theAppliedProbeIsPresentValueRenderedOnly() {
        ComputeObservation.AppliedEntry entry = interpreter.appliedState().entries().get(0);
        assertEquals("/Quote[1]/Premium", entry.pointer());
        AppliedState.PresentValue present = assertInstanceOf(AppliedState.PresentValue.class, entry.state());
        assertEquals(Availability.NOT_EXPOSED_BY_RUNNER, present.value().typedAvailability());
        assertEquals(anchor.withoutErrors().entries().get(0).value().rendered(), present.value().rendered());
    }
}
