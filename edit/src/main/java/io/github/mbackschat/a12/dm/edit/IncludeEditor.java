package io.github.mbackschat.a12.dm.edit;

import io.github.mbackschat.a12.dm.modelkit.MountOptions;

/**
 * L2c — a held reference to a declared include (by alias), so mounting it (again), re-pointing it,
 * renaming it, or removing it doesn't re-type the alias. Obtain from {@link EditSession#include(String)}.
 * Existence is checked when the edit applies, not at construction. {@link #rename(String)} moves the
 * header alias and every mount's {@code modelAlias} in step — mount paths are unchanged, so rules over
 * mounted content (keyed by path, not alias) are unaffected.
 */
public final class IncludeEditor {

    private final EditSession session;
    private final String alias;

    IncludeEditor(EditSession session, String alias) {
        this.session = session;
        this.alias = alias;
    }

    /** This include's alias. */
    public String alias() {
        return alias;
    }

    /** Mount this include at {@code parentPath} as a group named {@code name}. Chains. */
    public IncludeEditor mount(String parentPath, String name) {
        session.mount(parentPath, name, alias);
        return this;
    }

    /** Mount this include as a repeatable sub-form ({@code repeatability} rows; FEATURE-GAPS). Chains. */
    public IncludeEditor mount(String parentPath, String name, int repeatability) {
        session.mount(parentPath, name, alias, repeatability);
        return this;
    }

    /**
     * Mount this include with explicit {@link MountOptions} — repeatability plus whether to exclude the
     * included model's rules / computations on expand (FEATURE-GAPS). Chains.
     */
    public IncludeEditor mount(String parentPath, String name, MountOptions options) {
        session.mount(parentPath, name, alias, options);
        return this;
    }

    /** Re-point this include at a different referenced model (the alias and its mounts are unchanged). Chains. */
    public IncludeEditor setReference(String reference) {
        session.setIncludeReference(alias, reference);
        return this;
    }

    /** Rename this include's alias (header + every mount move together); returns an editor on the new alias. */
    public IncludeEditor rename(String newAlias) {
        session.renameInclude(alias, newAlias);
        return new IncludeEditor(session, newAlias);
    }

    /** Remove this include and all its mount groups; returns the session. */
    public EditSession remove() {
        return session.removeInclude(alias);
    }
}
