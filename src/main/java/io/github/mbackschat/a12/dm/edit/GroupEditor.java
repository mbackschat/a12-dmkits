package io.github.mbackschat.a12.dm.edit;

import java.util.function.Consumer;

import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConfirmRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateFragmentRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupReference;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeRef;

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

    /** A kind-agnostic {@link FieldRef} for the child field {@code name}; fails fast if it is not a field. */
    public FieldRef field(String name) {
        return session.boundModel().field(child(name));
    }

    /** A pure {@link BoolRef} for the child boolean field {@code name}; fails fast if it is not one. */
    public BoolRef bool(String name) {
        return session.boundModel().bool(child(name));
    }

    /** A pure {@link ConfirmRef} for the child confirm field {@code name}; fails fast if it is not one. */
    public ConfirmRef confirm(String name) {
        return session.boundModel().confirm(child(name));
    }

    /** A pure {@link EnumRef} for the child enum field {@code name}; fails fast if it is not one. */
    public EnumRef enumOf(String name) {
        return session.boundModel().enumOf(child(name));
    }

    /** A pure {@link DateRef} for the child date field {@code name}; fails fast if it is not one. */
    public DateRef date(String name) {
        return session.boundModel().date(child(name));
    }

    /** A pure {@link TimeRef} for the child time field {@code name}; fails fast if it is not one. */
    public TimeRef time(String name) {
        return session.boundModel().time(child(name));
    }

    /** A pure {@link DateTimeRef} for the child date-time field {@code name}; fails fast if it is not one. */
    public DateTimeRef dateTime(String name) {
        return session.boundModel().dateTime(child(name));
    }

    /** A pure {@link DateFragmentRef} for the child date-fragment field {@code name}; fails fast otherwise. */
    public DateFragmentRef dateFragment(String name) {
        return session.boundModel().dateFragment(child(name));
    }

    /** A pure {@link DateRangeRef} for the child date-range field {@code name}; fails fast otherwise. */
    public DateRangeRef dateRange(String name) {
        return session.boundModel().dateRange(child(name));
    }

    // --- fluent structure edits (forward to the session; chain) --------------------------------------

    /** Add a field to this group, then return this editor so edits chain. */
    public GroupEditor addField(String name, FieldKind kind) {
        session.addField(location.path(), name, kind);
        return this;
    }

    /** Add a richly-typed field (scale, bounds, pattern, enum domain, …) via a {@link FieldTypeSpec}. Chains. */
    public GroupEditor addField(String name, FieldTypeSpec type) {
        session.addField(location.path(), name, type);
        return this;
    }

    /**
     * Add a subgroup and configure it in place via {@code body} (mirrors the from-scratch builder's
     * {@code group(name, sub -> …)}), then return <em>this</em> editor so siblings chain. The child editor
     * handed to {@code body} is built without re-binding, so the model is never consistency-checked while
     * the new group sits empty — keep adding through it, then bind once at {@link EditSession#validate()}.
     */
    public GroupEditor addGroup(String name, Consumer<GroupEditor> body) {
        session.addGroup(location.path(), name);
        body.accept(new GroupEditor(session, new GroupRef(child(name), false)));
        return this;
    }

    /** Like {@link #addGroup(String, Consumer)} but the subgroup is repeatable (up to {@code maxRepetitions}). */
    public GroupEditor addRepeatableGroup(String name, int maxRepetitions, Consumer<GroupEditor> body) {
        session.addRepeatableGroup(location.path(), name, maxRepetitions);
        body.accept(new GroupEditor(session, new GroupRef(child(name), true)));
        return this;
    }

    private String child(String name) {
        return location.path() + "/" + name;
    }
}
