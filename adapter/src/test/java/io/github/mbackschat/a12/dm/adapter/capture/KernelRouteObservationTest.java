package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.dto.AppliedState;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.EvidenceValue;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunStatus;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;

/**
 * The two kernel ROUTE observations over the self-test scenario (proposal §3/§4): both routes succeed,
 * every channel is populated at its declared granularity, the applied state is read back from a fresh
 * DocumentV2 copy, and — the load-bearing claim — the two routes' channel observations are EQUAL
 * (cross-route agreement within one kernel implementation; runner identity is the only difference).
 */
class KernelRouteObservationTest {

    private static ComputeObservation groovy;
    private static ComputeObservation javaStatic;

    @TempDir
    static Path root;

    @BeforeAll
    static void observeBothRoutes() {
        ScenarioSets.Loaded loaded = ScenarioSets.load(CaptureFixtures.writeValidScenario(root));
        CaseExecution execution = CaseExecution.of(loaded.set().cases().get(0),
                new String(loaded.models().get("models/quote-premium.json").bytes(),
                        java.nio.charset.StandardCharsets.UTF_8),
                "en_US");
        groovy = Runners.observe(RunnerId.KERNEL_GROOVY_DYNAMIC, execution);
        javaStatic = Runners.observe(RunnerId.KERNEL_JAVA_STATIC, execution);
    }

    @Test
    void bothRoutesSucceed() {
        assertEquals(RunStatus.SUCCESS, groovy.status());
        assertEquals(RunStatus.SUCCESS, javaStatic.status());
        assertEquals(RunnerId.KERNEL_GROOVY_DYNAMIC, groovy.runner());
        assertEquals(RunnerId.KERNEL_JAVA_STATIC, javaStatic.runner());
    }

    @Test
    void theCleanChannelCarriesTheComputedPremiumWithTypedAndRenderedForms() {
        assertEquals(1, groovy.withoutErrors().entries().size());
        ComputeObservation.ValueEntry entry = groovy.withoutErrors().entries().get(0);
        assertEquals("/Quote[1]/Premium", entry.target());
        assertEquals("/Quote/PremiumCalc", entry.declaredComputation(),
                "the entry names the authored computation declaration");
        EvidenceValue.DecimalValue typed =
                assertInstanceOf(EvidenceValue.DecimalValue.class, entry.value().typed());
        assertEquals("20.5", typed.plainValue(),
                "10.25 * 2 — the kernel's exact typed result (its arithmetic normalizes the trailing zero;"
                        + " both routes agree, and capture records the exposed form verbatim)");
        assertEquals(Availability.AVAILABLE, entry.value().renderedAvailability());
        assertNotNull(entry.value().rendered(), "typed and rendered travel as SEPARATE fields");
    }

    @Test
    void theChangedSubsetReportsTheSeededEmptyTargetAsChanged() {
        assertEquals(1, groovy.changedSubset().entries().size(),
                "the EMPTY-seeded Premium computes to a value — a delta vs the input document");
    }

    @Test
    void emptyChannelsAreDataInsideSuccessNeverAFailureEncoding() {
        assertEquals(RunStatus.SUCCESS, groovy.status());
        assertTrue(groovy.withErrors().entries().isEmpty());
        assertTrue(groovy.cleared().entries().isEmpty(), "Premium was seeded EMPTY (valueless) — not input-filled");
        assertTrue(groovy.formalErrorsInOperands().entries().isEmpty());
        assertEquals(Availability.AVAILABLE, groovy.withErrors().availability());
    }

    @Test
    void theAppliedStateProbesTheFreshCopyWithTypedValue() {
        assertEquals(1, groovy.appliedState().entries().size());
        ComputeObservation.AppliedEntry entry = groovy.appliedState().entries().get(0);
        assertEquals("/Quote[1]/Premium", entry.pointer());
        AppliedState.PresentValue present = assertInstanceOf(AppliedState.PresentValue.class, entry.state());
        EvidenceValue.DecimalValue typed =
                assertInstanceOf(EvidenceValue.DecimalValue.class, present.value().typed());
        assertEquals("20.5", typed.plainValue(), "the applied cell holds the kernel's exact typed result");
    }

    @Test
    void theTwoRoutesObserveIdentically() {
        assertEquals(groovy.withoutErrors(), javaStatic.withoutErrors());
        assertEquals(groovy.changedSubset(), javaStatic.changedSubset());
        assertEquals(groovy.withErrors(), javaStatic.withErrors());
        assertEquals(groovy.cleared(), javaStatic.cleared());
        assertEquals(groovy.formalErrorsInOperands(), javaStatic.formalErrorsInOperands());
        assertEquals(groovy.appliedState(), javaStatic.appliedState());
    }
}
