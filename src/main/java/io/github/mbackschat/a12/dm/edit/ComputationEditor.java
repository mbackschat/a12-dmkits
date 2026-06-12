package io.github.mbackschat.a12.dm.edit;

import java.util.function.Function;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;

/**
 * L2c — a held reference to an existing computation (its full name = group-path + name), so re-expressing
 * its formula or removing it doesn't re-type the path. Obtain from {@link EditSession#computation(String)}.
 * Existence is checked when the edit applies, not at construction.
 */
public final class ComputationEditor {

    private final EditSession session;
    private final String fullName;

    ComputationEditor(EditSession session, String fullName) {
        this.session = session;
        this.fullName = fullName;
    }

    /** This computation's full name (group-path + name). */
    public String name() {
        return fullName;
    }

    /** Re-express this computation's formula via the typed DSL builder. Chains. */
    public ComputationEditor modify(Function<BoundModel, Computation> builder) {
        session.modifyComputation(fullName, builder);
        return this;
    }

    /** Remove this computation; returns the session (the computation no longer exists). */
    public EditSession remove() {
        return session.removeComputation(fullName);
    }
}
