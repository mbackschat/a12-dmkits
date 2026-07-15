package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunStatus;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerFidelity;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.CaseExecution;
import io.github.mbackschat.a12.dm.adapter.capture.runner.ComputeRunner;
import io.github.mbackschat.a12.dm.adapter.capture.runner.Runners;

/**
 * The per-run status contract (proposal §3): a throw maps to {@code technicalFailure} with SANITIZED
 * packet detail (no exception class, message, or stack frame), an elapsed budget maps to {@code timeout},
 * and successful-EMPTY output is representable only as {@code success} — a failed run can never be
 * serialized as a successful empty observation.
 */
class RunnersStatusTest {

    private static final CaseExecution EXECUTION = new CaseExecution(
            "status-case", "{}", Optional.empty(), "en_US", List.of(), List.of(), java.util.Map.of());

    private record FakeRunner(RunnerId id, java.util.function.Supplier<ComputeObservation> body)
            implements ComputeRunner {
        @Override
        public ComputeObservation observe(CaseExecution execution) {
            return body.get();
        }
    }

    @Test
    void aThrowMapsToTechnicalFailureWithSanitizedDetail() {
        ComputeRunner throwing = new FakeRunner(RunnerId.KERNEL_GROOVY_DYNAMIC, () -> {
            throw new IllegalStateException("com.mgmtp.secret.KernelClass exploded at /Users/someone/repo");
        });
        ComputeObservation observation = Runners.observe(throwing, EXECUTION, Duration.ofSeconds(5));
        assertEquals(RunStatus.TECHNICAL_FAILURE, observation.status());
        assertFalse(observation.statusDetail().contains("com.mgmtp"),
                "the packet detail is FIXED sanitized text — never the exception's own message");
        assertFalse(observation.statusDetail().contains("/Users/"));
        assertEquals(Availability.NOT_APPLICABLE, observation.withoutErrors().availability(),
                "a failed run carries no semantic channels");
    }

    @Test
    void anElapsedBudgetMapsToTimeout() {
        ComputeRunner slow = new FakeRunner(RunnerId.KERNEL_JAVA_STATIC, () -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return null;
        });
        ComputeObservation observation = Runners.observe(slow, EXECUTION, Duration.ofMillis(200));
        assertEquals(RunStatus.TIMEOUT, observation.status());
    }

    @Test
    void successfulEmptyIsDataAndDistinctFromEveryFailureEncoding() {
        ComputeObservation empty = ComputeObservation.success("status-case", RunnerId.A12_DMKITS_INTERPRETER)
                .withoutErrors(ComputeObservation.Channel.available(RunnerFidelity.ALL_COMPUTED_CLEAN, List.of()))
                .changedSubset(ComputeObservation.Channel.notExposed())
                .withErrors(ComputeObservation.Channel.available(RunnerFidelity.ERRORED_INSTANCES, List.of()))
                .cleared(ComputeObservation.Channel.available(RunnerFidelity.ALL_CLEARED, List.of()))
                .formalErrorsInOperands(ComputeObservation.Channel.notExposed())
                .appliedState(ComputeObservation.Channel.available(RunnerFidelity.REQUESTED_PROBES, List.of()))
                .build();
        assertEquals(RunStatus.SUCCESS, empty.status());
        assertEquals(Availability.AVAILABLE, empty.withoutErrors().availability(),
                "an empty AVAILABLE channel is real data — zero computed instances");
        assertTrue(empty.withoutErrors().entries().isEmpty());
    }

    @Test
    void aFailureObservationCannotClaimSuccess() {
        assertThrows(IllegalArgumentException.class, () -> ComputeObservation.failed(
                "status-case", RunnerId.A12_DMKITS_INTERPRETER, RunStatus.SUCCESS, "nope"));
    }

    @Test
    void aSuccessObservationRequiresEveryChannelExplicitly() {
        assertThrows(IllegalStateException.class, () ->
                ComputeObservation.success("status-case", RunnerId.A12_DMKITS_INTERPRETER).build());
    }
}
