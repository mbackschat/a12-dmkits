package io.github.mbackschat.a12.dm.edit;

/**
 * L2c — a held reference to an existing computation (its full name = group-path + name), so removing it
 * doesn't re-type the path. Obtain from {@link EditSession#computation(String)}. Existence is checked
 * when the edit applies, not at construction.
 *
 * <p>Re-expression rides {@link EditSession#modifyComputation} (the spec path); a typed-builder modify
 * is not yet on the session, so it is not surfaced here.
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

    /** Remove this computation; returns the session (the computation no longer exists). */
    public EditSession remove() {
        return session.removeComputation(fullName);
    }
}
