package io.github.mbackschat.a12.dm.edit;

import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupReference;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

/**
 * L2c — a thin, session-bound <b>editor</b> over one group: the <em>one</em> fluent editing idiom
 * (proposal §18.7 / D7; the name {@code Editor}, not "cursor", was a deliberate choice — §18.7). It
 * pairs a pure {@link GroupRef} location with the live {@link EditSession}, so it
 * <ol>
 *   <li>carries the structure-edit verbs, which <b>chain</b> (each returns {@code this}, mirroring
 *       modelkit's from-scratch builder);</li>
 *   <li>offers <b>relative navigation</b> to a pure typed ref for any child — existing or
 *       just-created (e.g. {@code addons.num("Discount")});</li>
 *   <li><b>downgrades</b> to its pure {@link GroupRef} via {@link #ref()} to enter the immutable AST.</li>
 * </ol>
 *
 * <p>The single safety rule (functional core / effects at the edge): effects live here, on the session;
 * the refs this editor yields are pure values. Rules are authored over those refs, so the session never
 * leaks into the immutable AST — the editor enters a rule only through the explicit {@link #ref()}
 * downgrade ({@code m.rule(editor.ref())}).
 *
 * <p>Navigation resolves against the session's <em>current</em> state, so a field added through this
 * editor is immediately referenceable by name — the create→reference flow the dropped L2b codegen could
 * never do, since the new field exists only at runtime. Obtain an editor from {@link EditSession#group(String)}.
 */
public final class GroupEditor implements GroupReference {

    private final EditSession session;
    private final GroupRef location;

    GroupEditor(EditSession session, GroupRef location) {
        this.session = session;
        this.location = location;
    }

    /** Downgrade to the pure group ref — what enters the AST (a rule's scope, a group operand). */
    @Override
    public GroupRef ref() {
        return location;
    }

    // --- relative navigation → pure typed refs (resolved against the session's current state) --------

    /** A pure {@link NumberRef} for the child number field {@code name}; fails fast if it is not one. */
    public NumberRef num(String name) {
        return session.boundModel().num(child(name));
    }

    /** A pure {@link StringRef} for the child string field {@code name}; fails fast if it is not one. */
    public StringRef str(String name) {
        return session.boundModel().str(child(name));
    }

    /** A pure {@link GroupRef} for the child group {@code name}; fails fast if it is not one. */
    public GroupRef group(String name) {
        return session.boundModel().group(child(name));
    }

    // --- fluent structure edits (forward to the session; chain) --------------------------------------

    /** Add a field to this group, then return this editor so edits chain. */
    public GroupEditor addField(String name, FieldKind kind) {
        session.addField(location.path(), name, kind);
        return this;
    }

    private String child(String name) {
        return location.path() + "/" + name;
    }
}
