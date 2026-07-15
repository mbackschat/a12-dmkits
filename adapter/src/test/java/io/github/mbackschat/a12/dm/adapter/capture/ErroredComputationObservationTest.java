package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunStatus;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSets;

/**
 * The ERRORED and FORMAL-ERROR channels end-to-end (proposal §4, the transport half of the "dropped /
 * altered / reconstructed downstream" mutation class): an errored computed instance RETAINS its attempted
 * value and carries its structured cause where exposed (the kernel routes; the interpreter declares
 * {@code notExposedByRunner} — never an invented "no cause"); an operand formal error reaches its own
 * channel; and the two kernel routes agree on all of it.
 *
 * <p>Fixture {@code quote-shortcode.json}: {@code ShortCode := [PolicyCode]} with {@code maxLength 3} on
 * the target — a 9-char {@code PolicyCode} makes the computed value formally invalid for the target (the
 * generic single-target constraint-violation mechanism; deliberately NOT the cascade matrix, which is the
 * next set's externally-authored scenario).
 */
class ErroredComputationObservationTest {

    private static ComputeObservation groovy;
    private static ComputeObservation javaStatic;
    private static ComputeObservation interpreter;
    private static ComputeObservation operandError;

    @TempDir
    static Path root;

    @BeforeAll
    static void observe() {
        String sha = CaptureFixtures.placeModel(root, "capture/models/quote-shortcode.json",
                "quote-shortcode.json");
        Path scenario = CaptureFixtures.writeScenario(root, text -> text
                .replace("models/quote-premium.json", "models/quote-shortcode.json")
                .replaceFirst("\"sha256\": \"[0-9a-f]{64}\"", "\"sha256\": \"" + sha + "\"")
                .replace("{ \"kind\": \"FIELD\", \"path\": \"/Quote/BaseRate\", \"reps\": [1, 1], \"value\": \"10.25\" },",
                        "{ \"kind\": \"FIELD\", \"path\": \"/Quote/PolicyCode\", \"reps\": [1, 1], \"value\": \"GOLD-2026\" },")
                .replace("{ \"kind\": \"EMPTY\", \"path\": \"/Quote/Premium\", \"reps\": [1, 1] }",
                        "{ \"kind\": \"EMPTY\", \"path\": \"/Quote/ShortCode\", \"reps\": [1, 1] }")
                .replace("\"probes\": [ \"/Quote[1]/Premium\" ]", "\"probes\": [ \"/Quote[1]/ShortCode\" ]"));
        ScenarioSets.Loaded loaded = ScenarioSets.load(scenario);
        String modelJson = new String(loaded.models().get("models/quote-shortcode.json").bytes(),
                StandardCharsets.UTF_8);
        CaseExecution execution = CaseExecution.of(loaded.set().cases().get(0), modelJson, "en_US");
        groovy = Runners.observe(RunnerId.KERNEL_GROOVY_DYNAMIC, execution);
        javaStatic = Runners.observe(RunnerId.KERNEL_JAVA_STATIC, execution);
        interpreter = Runners.observe(RunnerId.A12_DMKITS_INTERPRETER, execution);

        // A second case: the OPERAND itself formally invalid (25 chars > maxLength 20) → channel 5.
        ScenarioSet.ScenarioCase base = loaded.set().cases().get(0);
        ScenarioSet.ScenarioCase operandCase = new ScenarioSet.ScenarioCase(
                "operand-error", base.modelRef(), base.clock(),
                base.placements().stream().map(p ->
                        p.path().equals("/Quote/PolicyCode")
                                ? new ScenarioSet.Placement(p.kind(), p.path(), p.reps(),
                                        java.util.Optional.of("RENEWAL-PLUS-2026-EXTRA"))
                                : p).toList(),
                base.probes());
        operandError = Runners.observe(RunnerId.KERNEL_GROOVY_DYNAMIC,
                CaseExecution.of(operandCase, modelJson, "en_US"));
    }

    @Test
    void theErroredEntryRetainsTheAttemptedValueAndItsStructuredCause() {
        assertEquals(RunStatus.SUCCESS, groovy.status());
        assertEquals(1, groovy.withErrors().entries().size(),
                () -> "expected one errored instance; withoutErrors=" + groovy.withoutErrors().entries());
        ComputeObservation.ErrorEntry entry = groovy.withErrors().entries().get(0);
        assertEquals("/Quote[1]/ShortCode", entry.target());
        assertEquals("/Quote/ShortCodeCalc", entry.declaredComputation());
        assertEquals("GOLD-2026", entry.attempted().rendered(),
                "the ATTEMPTED value is retained exactly as the kernel stores-and-flags it");
        assertEquals(Availability.AVAILABLE, entry.cause().availability());
        assertNotNull(entry.cause().code(), "the structured cause carries its diagnostic code");
        assertNotNull(entry.cause().errorPointer());
    }

    @Test
    void bothKernelRoutesAgreeOnErrorTransport() {
        assertEquals(groovy.withErrors(), javaStatic.withErrors());
        assertEquals(groovy.cleared(), javaStatic.cleared());
        assertEquals(groovy.appliedState(), javaStatic.appliedState());
    }

    @Test
    void theInterpreterRetainsTheAttemptedValueButDeclaresNoCauseTransport() {
        assertEquals(1, interpreter.withErrors().entries().size());
        ComputeObservation.ErrorEntry entry = interpreter.withErrors().entries().get(0);
        assertEquals("GOLD-2026", entry.attempted().rendered());
        assertEquals(Availability.NOT_EXPOSED_BY_RUNNER, entry.cause().availability(),
                "missing cause transport is DECLARED — never rendered as \"no cause\"");
    }

    @Test
    void anOperandFormalErrorReachesItsOwnChannel() {
        assertEquals(RunStatus.SUCCESS, operandError.status());
        assertFalse(operandError.formalErrorsInOperands().entries().isEmpty(),
                "a formally invalid operand surfaces in formalErrorsInOperands");
        ComputeObservation.DiagnosticEntry diagnostic = operandError.formalErrorsInOperands().entries().get(0);
        assertNotNull(diagnostic.code());
        assertTrue(diagnostic.pointer().contains("/Quote"), diagnostic.pointer());
    }
}
