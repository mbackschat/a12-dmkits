package io.github.mbackschat.a12.dm.edit;

/**
 * L2c — a held reference to a declared include (by alias), so mounting it (again) or removing it doesn't
 * re-type the alias. Obtain from {@link EditSession#include(String)}. Existence is checked when the edit
 * applies, not at construction.
 *
 * <p>Re-aliasing / re-referencing an include is deferred: it would ripple to every mount group and is
 * rarely needed (proposal §18.7) — so this editor offers {@code mount} / {@code remove}, not re-alias.
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

    /** Remove this include and all its mount groups; returns the session. */
    public EditSession remove() {
        return session.removeInclude(alias);
    }
}
