package io.github.mbackschat.a12.dm.adapter.capture.runner;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;

/**
 * One registered runner (proposal §3): consumes the neutral {@link CaseExecution} and produces the
 * neutral {@link ComputeObservation} — engine-internal types never cross this boundary in either
 * direction (held by {@code verifyCapturePurity}). Every invocation starts from FRESH state: a new
 * engine/evaluation context, a fresh document built from the placements, and the explicitly injected
 * clock/locale — no cache or global state may leak between cases or routes.
 */
public interface ComputeRunner {

    RunnerId id();

    ComputeObservation observe(CaseExecution execution);
}
