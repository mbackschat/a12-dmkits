package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The refactor safety gate (CLI-SPEC §6, the standing principle): check the specific refactor's
 * preconditions against <em>this</em> model BEFORE touching anything; the first that fails yields a
 * {@link Refusal} — the command then refuses ({@code outcome:refused}, exit 2) and mutates nothing;
 * only when every check passes does the op proceed. We gate the refactor and stay in control — never
 * a best-effort rewrite, never a silent degrade.
 *
 * <p>Checks run in registration order, so a cheaper or clearer precondition can be placed first and
 * shadow a costlier one. Each refactor op registers the checks that fit its blast radius (see
 * {@link SafetyChecks}).
 */
final class SafetyGate {

    private final List<SafetyCheck> checks = new ArrayList<>();

    SafetyGate require(SafetyCheck check) {
        checks.add(check);
        return this;
    }

    /** The first failing precondition (registration order), or empty when the refactor is safe. */
    Optional<Refusal> firstFailure() {
        for (SafetyCheck check : checks) {
            Optional<Refusal> refusal = check.evaluate();
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return Optional.empty();
    }
}
