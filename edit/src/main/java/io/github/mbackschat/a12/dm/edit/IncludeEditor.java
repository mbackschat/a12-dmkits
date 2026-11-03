package io.github.mbackschat.a12.dm.edit;

import io.github.mbackschat.a12.dm.modelkit.MountOptions;

/**
 * L2c — a held reference to an include, identified by the included model's id (DM 29.4 has no include alias,
 * KF651), so mounting it (again), re-pointing it, or removing it doesn't re-type the id. Obtain from
 * {@link EditSession#include(String)}. Existence is checked when the edit applies, not at construction.
 * {@link #setReference(String)} moves every mount and the header entry in step — mount paths are unchanged, so
 * rules over mounted content (keyed by path) are unaffected.
 */
public final class IncludeEditor {

    private final EditSession session;
    private final String reference;

    IncludeEditor(EditSession session, String reference) {
        this.session = session;
        this.reference = reference;
    }

    /** The included model's id. */
    public String reference() {
        return reference;
    }

    /** Mount this include at {@code parentPath} as a group named {@code name}. Chains. */
    public IncludeEditor mount(String parentPath, String name) {
        session.mount(parentPath, name, reference);
        return this;
    }

    /** Mount this include as a repeatable sub-form ({@code repeatability} rows; FEATURE-GAPS). Chains. */
    public IncludeEditor mount(String parentPath, String name, int repeatability) {
        session.mount(parentPath, name, reference, repeatability);
        return this;
    }

    /**
     * Mount this include with explicit {@link MountOptions} — repeatability plus whether to exclude the
     * included model's rules / computations on expand (FEATURE-GAPS). Chains.
     */
    public IncludeEditor mount(String parentPath, String name, MountOptions options) {
        session.mount(parentPath, name, reference, options);
        return this;
    }

    /** Re-point every mount of this include at the model {@code newReference}; returns an editor on that model. */
    public IncludeEditor setReference(String newReference) {
        session.setIncludeReference(reference, newReference);
        return new IncludeEditor(session, newReference);
    }

    /** Remove this include and all its mount groups; returns the session. */
    public EditSession remove() {
        return session.removeInclude(reference);
    }
}
