package io.github.mbackschat.a12.dm.adapter.capture.runner;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunStatus;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.runner.interpreter.InterpreterRunner;
import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.GroovyDynamicRunner;
import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.JavaStaticRunner;

/**
 * The compile-time runner REGISTRY (proposal §3) — the exhaustive switch below IS the registry; there is
 * no runtime plugin discovery, and adding a runner is a deliberate code change the compiler enumerates.
 * {@link #observe} wraps every execution with the per-run status contract: a wall-clock budget
 * ({@link #DEFAULT_TIMEOUT}) maps to {@code timeout}, a throw maps to {@code technicalFailure} with
 * SANITIZED detail (fixed text — no exception message, class name, or stack frame reaches the packet;
 * the full trace goes to the process stderr for the developer).
 */
public final class Runners {

    /** Generous per-(case, runner) budget — a legal scenario case evaluates in milliseconds-to-seconds. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private Runners() {
    }

    /** The registered runner for {@code id} — a FRESH instance per call (no shared engine state). */
    public static ComputeRunner of(RunnerId id) {
        return switch (id) {
            case KERNEL_GROOVY_DYNAMIC -> new GroovyDynamicRunner();
            case KERNEL_JAVA_STATIC -> new JavaStaticRunner();
            case A12_DMKITS_INTERPRETER -> new InterpreterRunner();
        };
    }

    /** Execute {@code id} over {@code execution} under the default budget. */
    public static ComputeObservation observe(RunnerId id, CaseExecution execution) {
        return observe(of(id), execution, DEFAULT_TIMEOUT);
    }

    /** The status-mapping wrapper (budget injectable for the timeout self-test). */
    public static ComputeObservation observe(ComputeRunner runner, CaseExecution execution, Duration budget) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "capture-runner-" + runner.id().wire());
            t.setDaemon(true);
            return t;
        });
        try {
            Future<ComputeObservation> future = executor.submit(() -> runner.observe(execution));
            try {
                return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return ComputeObservation.failed(execution.caseId(), runner.id(), RunStatus.TIMEOUT,
                        "the runner did not return within the per-run budget");
            } catch (ExecutionException e) {
                e.getCause().printStackTrace();   // developer detail — stderr only, never the packet
                return ComputeObservation.failed(execution.caseId(), runner.id(), RunStatus.TECHNICAL_FAILURE,
                        "the runner threw during execution; details withheld from the packet (see stderr)");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ComputeObservation.failed(execution.caseId(), runner.id(), RunStatus.TECHNICAL_FAILURE,
                        "the capture run was interrupted");
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
