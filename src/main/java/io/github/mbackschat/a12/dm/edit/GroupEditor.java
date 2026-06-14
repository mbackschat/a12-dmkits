package io.github.mbackschat.a12.dm.edit;

import java.util.function.Consumer;
import java.util.function.Function;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.CustomFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.DateFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.DateFragmentFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.DateRangeFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.DateTimeFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.EnumFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.FieldAttrs;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.NumberFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.StringFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.TimeFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
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
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
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

    /** Add a fully-configured field (type + metadata) built by a per-type builder below. Chains. */
    public GroupEditor addField(ElementDraft.Field field) {
        session.addField(location.path(), field);
        return this;
    }

    // --- per-type field builders (the from-scratch builders, on the EDIT surface — DG1) --------------
    // Editing reads like authoring: configure scale/bounds/pattern/enum domain + metadata fluently,
    // instead of hand-building a FieldTypeSpec. Each reuses the from-scratch builder, so a field added
    // here is byte-identical to one built from scratch (the shared FieldAttrs.toField assembly).

    /** Add a configured string field — {@code stringField("Code", s -> s.length(3, 10).pattern("[A-Z]+"))}. */
    public GroupEditor stringField(String name, Consumer<StringFieldBuilder> body) {
        return configured(name, new StringFieldBuilder(), body);
    }

    /** Add a configured number field — {@code numberField("Fee", n -> n.fractionDigits(2).min("0"))}. */
    public GroupEditor numberField(String name, Consumer<NumberFieldBuilder> body) {
        return configured(name, new NumberFieldBuilder(), body);
    }

    /** Add a configured enum field — {@code enumField("Tier", e -> e.value("BASIC").value("PRO"))}. */
    public GroupEditor enumField(String name, Consumer<EnumFieldBuilder> body) {
        return configured(name, new EnumFieldBuilder(), body);
    }

    /** Add a configured date field — {@code dateField("Due", d -> d.required())}. */
    public GroupEditor dateField(String name, Consumer<DateFieldBuilder> body) {
        return configured(name, new DateFieldBuilder(), body);
    }

    /** Add a configured time-of-day field. */
    public GroupEditor timeField(String name, Consumer<TimeFieldBuilder> body) {
        return configured(name, new TimeFieldBuilder(), body);
    }

    /** Add a configured date-time field. */
    public GroupEditor dateTimeField(String name, Consumer<DateTimeFieldBuilder> body) {
        return configured(name, new DateTimeFieldBuilder(), body);
    }

    /** Add a configured date-range field. */
    public GroupEditor dateRangeField(String name, Consumer<DateRangeFieldBuilder> body) {
        return configured(name, new DateRangeFieldBuilder(), body);
    }

    /** Add a configured date-fragment field. */
    public GroupEditor dateFragmentField(String name, Consumer<DateFragmentFieldBuilder> body) {
        return configured(name, new DateFragmentFieldBuilder(), body);
    }

    /** Add a configured custom (host-typed) field. */
    public GroupEditor customField(String name, Consumer<CustomFieldBuilder> body) {
        return configured(name, new CustomFieldBuilder(), body);
    }

    private <B extends FieldAttrs<B>> GroupEditor configured(String name, B builder, Consumer<B> body) {
        body.accept(builder);
        return addField(builder.toField(name));
    }

    /** Add a field typed by a declared type definition {@code typeDefId}. Chains. */
    public GroupEditor addTypedefField(String name, String typeDefId) {
        session.addTypedefField(location.path(), name, typeDefId);
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

    // --- editor-scoped authoring (the builder arrives already scoped to this group) -----------------

    /**
     * Author a rule scoped to this group: {@code body} receives a {@link Rule.Builder} already scoped here
     * (no {@code m.rule(group)} boilerplate) and returns the built rule. Chains. This is what lets a
     * reusable document-part builder take just a {@code GroupEditor} and emit structure <em>and</em> rules.
     */
    public GroupEditor addRule(Function<Rule.Builder, Rule> body) {
        session.addRule(m -> body.apply(m.rule(this)));
        return this;
    }

    /**
     * Author a computation for {@code computed} (a number field of this group, obtained via {@link
     * #num(String)}): {@code body} receives a {@link Computation.Builder} already scoped to that field
     * and returns the built computation. Chains.
     */
    public GroupEditor addComputation(NumberRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** Set a name/value annotation on this group. Chains. */
    public GroupEditor setAnnotation(String name, String value) {
        session.setAnnotation(location.path(), name, value);
        return this;
    }

    /** Remove the annotation {@code name} from this group. Chains. */
    public GroupEditor removeAnnotation(String name) {
        session.removeAnnotation(location.path(), name);
        return this;
    }

    /** Upsert one per-locale internal description (authoring/maintenance text) on this group. Chains. */
    public GroupEditor setInternalDescription(String locale, String text) {
        session.setInternalDescription(location.path(), locale, text);
        return this;
    }

    /** Upsert one per-locale external description (end-user-facing text) on this group. Chains. */
    public GroupEditor setExternalDescription(String locale, String text) {
        session.setExternalDescription(location.path(), locale, text);
        return this;
    }

    private String child(String name) {
        return location.path() + "/" + name;
    }
}
