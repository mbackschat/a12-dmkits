package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

/**
 * One safety precondition of a refactor (CLI-SPEC §6): it inspects the model and returns a
 * {@link Refusal} when the refactor is <b>not</b> safe to perform, or empty when this precondition
 * holds. A {@link SafetyGate} runs a set of these before any mutation.
 */
@FunctionalInterface
interface SafetyCheck {

    Optional<Refusal> evaluate();
}
