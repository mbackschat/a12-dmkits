package io.github.mbackschat.a12.dm.edit;

import java.util.function.Function;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;

/**
 * L2c — a held reference to an existing computation (its full name = group-path + name), so re-expressing
 * its formula, editing its element metadata, or removing it doesn't re-type the path. Obtain from
 * {@link EditSession#computation(String)}. Existence is checked when the edit applies, not at construction.
 */
public final class ComputationEditor extends ElementMetadataEditor<ComputationEditor> {

    ComputationEditor(EditSession session, String fullName) {
        super(session, fullName);
    }

    /** This computation's full name (group-path + name). */
    public String name() {
        return elementPath;
    }

    /** Re-express this computation's formula via the typed DSL builder. Chains. */
    public ComputationEditor modify(Function<BoundModel, Computation> builder) {
        session.modifyComputation(elementPath, builder);
        return this;
    }

    /** Remove this computation; returns the session (the computation no longer exists). */
    public EditSession remove() {
        return session.removeComputation(elementPath);
    }
}
