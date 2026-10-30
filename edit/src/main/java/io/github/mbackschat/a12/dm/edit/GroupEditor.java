package io.github.mbackschat.a12.dm.edit;

import io.github.mbackschat.a12.dm.core.EntityPath;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.mbackschat.a12.dm.modelkit.CustomFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DateFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DateFragmentFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DateRangeFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.DateTimeFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.EnumFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.FieldAttrs;
import io.github.mbackschat.a12.dm.modelkit.NumberFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.StringFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.TimeFieldBuilder;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConfirmRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateFragmentRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupReference;
import io.github.mbackschat.a12.dm.rulekit.dsl.MessageTokens;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeRef;

/**
 * L2c — a thin, session-bound <b>editor</b> over one group: the <em>one</em> fluent editing idiom
 * (PROJECT-DESIGN §18.7 / D7; the name {@code Editor}, not "cursor", was a deliberate choice — §18.7). It
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
public final class GroupEditor extends ElementMetadataEditor<GroupEditor> implements GroupReference {

    private final GroupRef location;

    GroupEditor(EditSession session, GroupRef location) {
        super(session, location.path().value());
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

    /**
     * A flattened repetition list relative to this group — the star-aggregate peer of the scalar navigation
     * above (mirrors {@link io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo#flatten(String)} on the read
     * surface). The {@code relativeStarPath} carries its own star on each repeatable segment (e.g. flattening
     * a child {@code Cost} across a repeatable {@code Subs} subgroup); this group's path is prepended.
     */
    public FieldListRef flatten(String relativeStarPath) {
        return session.boundModel().flatten(child(relativeStarPath));
    }

    /** A (possibly starred) group scope relative to this group — the group analogue of {@link #flatten(String)}. */
    public GroupListRef flattenGroup(String relativeStarPath) {
        return session.boundModel().flattenGroup(child(relativeStarPath));
    }

    /** The message tokens of this model's condition language, which a token in the other one is refused for (KF198). */
    public MessageTokens messageTokens() {
        return MessageTokens.of(session.conditionLanguage());
    }

    /**
     * A (possibly starred) group relative to this group as a {@code FirstFilledValue} operand, which visits the
     * group's descendant fields in owner order (mirrors {@link io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel#firstFilledGroup}).
     */
    public FirstFilledOperand firstFilledGroup(String relativeGroupPath) {
        return session.boundModel().firstFilledGroup(child(relativeGroupPath));
    }

    /**
     * The row of a keyed group relative to this group that the literal semantic-index {@code indexValue} selects, as
     * a {@code FirstFilledValue} operand (mirrors {@link io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel#firstFilledGroupFor(String, String)}).
     */
    public FirstFilledOperand firstFilledGroupFor(String relativeGroupPath, String indexValue) {
        return session.boundModel().firstFilledGroupFor(child(relativeGroupPath), indexValue);
    }

    // --- fluent structure edits (forward to the session; chain) --------------------------------------

    /** Add a field to this group, then return this editor so edits chain. */
    public GroupEditor addField(String name, FieldKind kind) {
        session.addField(location.path().value(), name, kind);
        return this;
    }

    /** Add a richly-typed field (scale, bounds, pattern, enum domain, …) via a {@link FieldTypeSpec}. Chains. */
    public GroupEditor addField(String name, FieldTypeSpec type) {
        session.addField(location.path().value(), name, type);
        return this;
    }

    /** Add a fully-configured field (type + metadata) built by a per-type builder below. Chains. */
    public GroupEditor addField(ElementDraft.Field field) {
        session.addField(location.path().value(), field);
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
        session.addTypedefField(location.path().value(), name, typeDefId);
        return this;
    }

    /**
     * Add a subgroup and configure it in place via {@code body} (mirrors the from-scratch builder's
     * {@code group(name, sub -> …)}), then return <em>this</em> editor so siblings chain. The child editor
     * handed to {@code body} is built without re-binding, so the model is never consistency-checked while
     * the new group sits empty — keep adding through it, then bind once at {@link EditSession#validate()}.
     */
    public GroupEditor addGroup(String name, Consumer<GroupEditor> body) {
        session.addGroup(location.path().value(), name);
        body.accept(new GroupEditor(session, new GroupRef(EntityPath.of(child(name)), false)));
        return this;
    }

    /** Like {@link #addGroup(String, Consumer)} but the subgroup is repeatable (up to {@code maxRepetitions}). */
    public GroupEditor addRepeatableGroup(String name, int maxRepetitions, Consumer<GroupEditor> body) {
        session.addRepeatableGroup(location.path().value(), name, maxRepetitions);
        body.accept(new GroupEditor(session, new GroupRef(EntityPath.of(child(name)), true)));
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
     * Author a computation for {@code computed} (a number field of this group or below it, obtained via
     * {@link #num(String)}): {@code body} receives a {@link Computation.Builder} already scoped to that field
     * and returns the built computation. Chains.
     *
     * <p><b>The DECLARING group — where the computation lives — defaults to the computed field's parent, not to
     * this editor's group,</b> and that asymmetry with {@link #addRule} is deliberate. For a rule the group IS
     * the iteration scope, so scoping it here is the only sensible reading; for a computation the group decides
     * whether it runs once or per row, so imposing the editor's group would move a computation authored over a
     * descendant field ({@code editor.num("Lines/Amount")}) out of a repeatable {@code Lines} and silently turn
     * a per-row computation into a single one.
     *
     * <p>To declare it <em>here</em> anyway — the cross-group shape A12 admits, where a computation lives in one
     * group and writes into another — hand this editor to {@link Computation.Builder#group}, which takes a
     * {@link io.github.mbackschat.a12.dm.rulekit.dsl.GroupReference} and which {@code GroupEditor} is:
     * {@code editor.addComputation(target, c -> c.group(editor)…)}. The path is still written once, as a ref.
     */
    public GroupEditor addComputation(NumberRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a STRING computed field (via {@link #str(String)})
     *  — e.g. a concatenation target. Chains. */
    public GroupEditor addComputation(StringRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a DATE computed field (via {@link #date(String)}),
     *  including a field another temporal kind declares with a date format. Chains. */
    public GroupEditor addComputation(DateRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for an ENUMERATION computed field. Chains. */
    public GroupEditor addComputation(EnumRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a BOOLEAN computed field. Chains. */
    public GroupEditor addComputation(BoolRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a CONFIRM computed field. Chains. */
    public GroupEditor addComputation(ConfirmRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a TIME computed field. Chains. */
    public GroupEditor addComputation(TimeRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a DATE_TIME computed field. Chains. */
    public GroupEditor addComputation(DateTimeRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a DATE_FRAGMENT computed field. Chains. */
    public GroupEditor addComputation(DateFragmentRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for a DATE_RANGE computed field. Chains. */
    public GroupEditor addComputation(DateRangeRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /** As {@link #addComputation(NumberRef, Function)} for any computed field kind (via {@link #field(String)})
     *  — the general form behind the typed conveniences. Chains. */
    public GroupEditor addComputation(FieldRef computed, Function<Computation.Builder, Computation> body) {
        session.addComputation(m -> body.apply(m.computation(computed)));
        return this;
    }

    /**
     * BUILD a rule scoped to this group and RETURN it (the rule is <b>not</b> added) — the compose peer of
     * {@link #addRule}. {@code body} receives a {@link Rule.Builder} already scoped here; the built {@link Rule}
     * carries its error field + condition as refs, with {@link Rule#errorEntityRelPath()} and {@link
     * Rule#render()} <em>derived</em>. Use it to obtain a typed rule for inspection or to hand to another
     * consumer (e.g. a differential harness) without mutating the session.
     */
    public Rule rule(Function<Rule.Builder, Rule> body) {
        return body.apply(session.boundModel().rule(this));
    }

    /**
     * BUILD a computation for {@code computed} (a number field of this group or below it, via
     * {@link #num(String)}) and RETURN it (not added) — the compose peer of {@link #addComputation}.
     * {@link Computation#computedFieldRelPath()} is derived from the ref, relative to the computation
     * ELEMENT; the session is left unchanged.
     *
     * <p>The declaring group defaults to the computed field's parent and is overridden by handing this editor
     * to {@link Computation.Builder#group} — see {@link #addComputation(NumberRef, Function)} for why the
     * default differs from {@link #addRule}'s.
     */
    public Computation computation(NumberRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a STRING computed field — the compose peer of
     *  {@link #addComputation(StringRef, Function)}. */
    public Computation computation(StringRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a DATE computed field — the compose peer of
     *  {@link #addComputation(DateRef, Function)}. */
    public Computation computation(DateRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for an ENUMERATION computed field — the compose peer of
     *  {@link #addComputation(EnumRef, Function)}. */
    public Computation computation(EnumRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a BOOLEAN computed field — the compose peer of
     *  {@link #addComputation(BoolRef, Function)}. */
    public Computation computation(BoolRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a CONFIRM computed field — the compose peer of
     *  {@link #addComputation(ConfirmRef, Function)}. */
    public Computation computation(ConfirmRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a TIME computed field — the compose peer of
     *  {@link #addComputation(TimeRef, Function)}. */
    public Computation computation(TimeRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a DATE_TIME computed field — the compose peer of
     *  {@link #addComputation(DateTimeRef, Function)}. */
    public Computation computation(DateTimeRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a DATE_FRAGMENT computed field — the compose peer of
     *  {@link #addComputation(DateFragmentRef, Function)}. */
    public Computation computation(DateFragmentRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for a DATE_RANGE computed field — the compose peer of
     *  {@link #addComputation(DateRangeRef, Function)}. */
    public Computation computation(DateRangeRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** As {@link #computation(NumberRef, Function)} for any computed field kind — the compose peer of
     *  {@link #addComputation(FieldRef, Function)}. */
    public Computation computation(FieldRef computed, Function<Computation.Builder, Computation> body) {
        return body.apply(session.boundModel().computation(computed));
    }

    /** Set this group's per-locale display labels (once any locale is set, all declared ones are required). Chains. */
    public GroupEditor setLabel(Map<String, String> localeToText) {
        session.setLabel(location.path().value(), localeToText);
        return this;
    }

    /** Make this group a repeatable list (up to {@code maxRepetitions}, ≥ 2). Chains. */
    public GroupEditor setRepeatable(int maxRepetitions) {
        session.setRepeatable(location.path().value(), maxRepetitions);
        return this;
    }

    /** Make this group non-repeating (the single default) — the inverse of {@link #setRepeatable(int)}. Chains. */
    public GroupEditor setNonRepeatable() {
        session.setNonRepeatable(location.path().value());
        return this;
    }

    /** Declare this group's direct-child index field, including on a non-repeatable group. Chains. */
    public GroupEditor setIndexField(String fieldName) {
        session.setIndexField(location.path().value(), fieldName);
        return this;
    }

    /** Declare this repeatable group's row {@code sortFields} (in order). Chains. */
    public GroupEditor setSortFields(List<ElementDraft.SortField> fields) {
        session.setSortFields(location.path().value(), fields);
        return this;
    }

    private String child(String name) {
        return location.path() + "/" + name;
    }
}
