package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.EntityPath;

import java.util.Arrays;
import java.util.List;

import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.core.Verification;

/**
 * The entry point for authoring against a specific model (L2a runtime binding — §5a). It hands out
 * typed field handles looked up from a loaded {@link ModelIndex}, so paths and operand kinds are
 * resolved at runtime with no codegen. Unknown paths / wrong kinds fail fast at handle creation.
 */
public final class BoundModel {

    private final ModelIndex index;
    // Retained for the F6 write path; null for read-only bindings. This is the AUTHORED (unexpanded)
    // DM-JSON — writes edit it and never the kernel's expanded model, so includes/modelReferences are
    // preserved verbatim, never inlined. The resolver is kept so re-binding a model with includes works.
    private final String sourceJson;
    private final ModelResolver resolver;
    // The consistency gate's RECEIPT, captured at bind time (KERNEL-DECOUPLING-PROPOSAL §2b): null when no gate
    // ran for this binding at all, else how it was arbitrated (KERNEL_CONFIRMED with a backend present,
    // STRUCTURAL_ONLY without). Null and STRUCTURAL_ONLY are DIFFERENT facts — see #consistencyGate().
    private final Verification consistencyGate;

    private BoundModel(ModelIndex index, String sourceJson, ModelResolver resolver, Verification consistencyGate) {
        this.index = index;
        this.sourceJson = sourceJson;
        this.resolver = resolver;
        this.consistencyGate = consistencyGate;
    }

    public static BoundModel of(ModelIndex index) {
        return new BoundModel(index, null, null, null);   // no gate ran — an index is not a verdict
    }

    /**
     * Bind from <em>self-contained</em> DM-JSON, retaining the source so the model can be
     * {@link #add(Rule) written} back (F6). For a model with includes / {@code modelReferences}, use
     * {@link #ofJson(String, ModelResolver)}.
     */
    public static BoundModel ofJson(String modelJson) {
        return ofJson(modelJson,
                io.github.mbackschat.a12.dm.core.ReferenceResolvers.selfContained());
    }

    /**
     * Bind from DM-JSON with a <b>kernel-free</b> {@link ModelResolver} ({@code id → DM-JSON}) for includes /
     * type-def imports — the common multi-model case without touching {@code com.mgmtp.a12.kernel.*} —
     * retaining both so the write path round-trips an included model correctly (loads → expands against the
     * resolver → {@code checkConsistency}). The retained source is the unexpanded authored JSON, so
     * {@link #toJson()} / {@link #writeTo} preserve the includes rather than inlining them.
     */
    public static BoundModel ofJson(String modelJson, ModelResolver resolver) {
        // R3 — the consistency gate, routed through the kernel backend when present (deserialize → expand →
        // checkConsistency; throws on ERROR). With no backend it degrades to a STRUCTURAL_ONLY expand — no
        // MVK verdict is fabricated — and the binding carries that mode (KERNEL-DECOUPLING-PROPOSAL §2c). The
        // index/read surface is kernel-free either way (decoupling Stage 2).
        // The receipt comes from the gate that just ran, not from a second ambient read of Backends — the two
        // agree today, but only the former is still true if this path ever stops gating.
        Verification gate = ModelLoader.loadAndCheck(modelJson, resolver);
        return new BoundModel(ModelIndex.of(modelJson, resolver), modelJson, resolver, gate);
    }

    /** Bind from a DM-JSON file on disk — sugar for {@code ofJson(Files.readString(path))}. The source is
     *  retained for the write path (F6), exactly as {@link #ofJson(String)}. */
    public static BoundModel load(java.nio.file.Path path) {
        try {
            return ofJson(java.nio.file.Files.readString(path));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("failed to read model from " + path, e);
        }
    }

    /**
     * Bind <b>without</b> the kernel consistency gate (expand-only) — for <em>read-only</em> inspection of a
     * model that may be inconsistent (e.g. a candidate rule whose error field is not yet valid, G4: read its
     * condition / references before it would validate). Makes <b>no validity promise</b>; never use it for the
     * write path — the kernel-free reader expands (includes / imported type-defs) and indexes, no consistency gate.
     */
    public static BoundModel ofJsonLenient(String modelJson, ModelResolver resolver) {
        // No consistency gate — the kernel-free reader expands (includes / imported type-defs) and indexes;
        // it makes no validity promise, exactly as the expand-only path did (Stage 2). No gate ran, so there is
        // NO receipt: a consumer must not be able to read this binding as verified in any mode.
        return new BoundModel(ModelIndex.of(modelJson, resolver), modelJson, resolver, null);
    }

    // --- write path (F6) — every write yields a NEW binding (snapshot semantics, F0) -----------

    /**
     * Adds {@code rule} to the model and returns a <em>new</em> binding over the updated DM-JSON;
     * this binding is unchanged. The result is re-validated (deserialize → expand →
     * {@code checkConsistency}), so a write that would break the model fails loudly here.
     */
    public BoundModel add(Rule rule) {
        // re-bind with the SAME resolver, so a model with includes re-expands correctly
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(requireWritable(), rule), resolver);
    }

    /**
     * Adds {@code computation} to the model (in its computed field's group) and returns a <em>new</em>
     * binding over the updated DM-JSON; this binding is unchanged. Re-validated on re-bind (a write that
     * breaks the model fails loudly here), the computation twin of {@link #add(Rule)}.
     */
    public BoundModel add(Computation computation) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addComputation(requireWritable(),
                computation), resolver);
    }

    /**
     * Adds a complete typed rule/computation population with one DM-JSON parse, one serialization, and one
     * terminal consistency gate. The source binding remains unchanged.
     *
     * <p>This is the large-model peer of repeated {@link #add(Rule)}/{@link #add(Computation)} calls: every
     * item is still authored through the typed surface, but program count no longer multiplies whole-model
     * parse/pretty-print work.
     */
    public BoundModel addAll(List<Rule> rules, List<Computation> computations) {
        return ofJson(
                io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addAll(
                        requireWritable(), rules, computations),
                resolver);
    }

    /**
     * Removes the rule at {@code ruleFullName}, returning a <em>new</em> binding (this one is unchanged).
     * Re-validated on re-bind. Throws if no rule exists at that path.
     */
    public BoundModel removeRule(String ruleFullName) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.removeRule(requireWritable(),
                ruleFullName), resolver);
    }

    /**
     * Removes the computation at {@code computationFullName}, returning a <em>new</em> binding (this one
     * is unchanged). Re-validated on re-bind. The computation twin of {@link #removeRule(String)}.
     */
    public BoundModel removeComputation(String computationFullName) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.removeComputation(requireWritable(),
                computationFullName), resolver);
    }

    /**
     * Replaces the computation at {@code computationFullName} with the re-expressed {@code computation},
     * returning a <em>new</em> binding (this one is unchanged); the F8 #3 write-back for computations
     * (remove the old element, add the new). Re-validated on re-bind, so a write that breaks the
     * computation fails loudly here. The computation twin of {@link #replaceCondition(String, Condition)}.
     */
    public BoundModel replaceComputation(String computationFullName, Computation computation) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.replaceComputation(
                requireWritable(), computationFullName, computation), resolver);
    }

    /**
     * Replaces the condition of the rule at {@code ruleFullName} with {@code newCondition}, returning
     * a <em>new</em> binding (this one is unchanged); the F8 #3 write-back that applies a reviewed
     * re-express. The condition is rendered relative to the rule's group (fork-9), like any authored
     * rule; only the {@code errorCondition} slot changes. Re-validated on re-bind, so a write that
     * breaks the rule fails loudly here.
     */
    public BoundModel replaceCondition(String ruleFullName, Condition newCondition) {
        return ofJson(io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.replaceCondition(
                requireWritable(), ruleFullName, newCondition), resolver);
    }

    /** The model as DM-JSON text (the retained source, with any writes applied). */
    public String toJson() {
        return requireWritable();
    }

    /** Persists the model's DM-JSON to {@code path} (the portable artifact). */
    public void writeTo(java.nio.file.Path path) {
        try {
            java.nio.file.Files.writeString(path, requireWritable());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("failed to write model to " + path, e);
        }
    }

    private String requireWritable() {
        if (sourceJson == null) {
            throw new IllegalStateException("this binding has no source JSON — use BoundModel.ofJson(...) "
                    + "to enable the write path (F6)");
        }
        return sourceJson;
    }

    /**
     * The reference resolver this binding loads / re-binds against (F-RES) — used so a consumer (e.g.
     * the kernel adapter) expands the model against the <em>same</em> resolver. Null for a binding made
     * directly from a {@code ModelIndex} (no source JSON); non-null whenever {@link #toJson()} works.
     */
    public ModelResolver resolver() {
        return resolver;
    }

    /**
     * The consistency gate's <b>receipt</b> for this binding (KERNEL-DECOUPLING-PROPOSAL §2c) — <b>empty when no
     * gate ran</b>, else how it was arbitrated: {@link Verification#KERNEL_CONFIRMED} when a kernel backend
     * arbitrated the {@link #ofJson(String, ModelResolver) gated} bind, {@link Verification#STRUCTURAL_ONLY} when
     * that same gated bind found no backend (a library consumer's degraded posture — never read a successful bind
     * as kernel-valid). The CLI always bundles the backend, so a gated bind there is always
     * {@code KERNEL_CONFIRMED}.
     *
     * <p><b>Empty is a third, distinct answer</b> and the reason this returns an {@link java.util.Optional}:
     * {@link #ofJsonLenient} and {@link #of(ModelIndex)} never invoke the gate, so they have nothing to attest.
     * The predecessor accessor returned a bare {@code Verification} and reported {@code STRUCTURAL_ONLY} for both
     * "gate ran, unbacked" and "gate never ran", which let a consumer publish verification provenance for an
     * invocation that was never gated. Only ever attach evidence you were handed here.
     */
    public java.util.Optional<Verification> consistencyGate() {
        return java.util.Optional.ofNullable(consistencyGate);
    }

    /** A generic handle to the field at {@code path} (any kind). Throws if the path is not a field. */
    public FieldRef field(String path) {
        return new FieldRef(path, index.kindOf(path));
    }

    /** Package-private access to the underlying index — lets {@link FieldInfo} surface declared field config
     *  (numeric bounds, string length/pattern) without exposing the kernel-aware index publicly. */
    ModelIndex index() {
        return index;
    }

    /** A typed number handle. Throws if the field at {@code path} is not a number. */
    public NumberRef num(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.NUMBER) {
            throw new IllegalArgumentException(path + " is not a number field (kind=" + kind + ")");
        }
        return new NumberRef(field(path), index.numberScale(path));
    }

    /** A typed string handle. Throws if the field at {@code path} is not a string. */
    public StringRef str(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.STRING) {
            throw new IllegalArgumentException(path + " is not a string field (kind=" + kind + ")");
        }
        return new StringRef(field(path));
    }

    /**
     * A string-shaped handle to a custom-typed field. A {@code Custom} field's value is text, and the kernel
     * grammar compares it like any string ({@code [Code] == "x"}), so it reuses the {@link StringRef} surface.
     * Distinct from {@link #str(String)}, which keeps its {@code STRING}-only contract. Throws if the field at
     * {@code path} is not a custom field.
     */
    public StringRef custom(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.CUSTOM) {
            throw new IllegalArgumentException(path + " is not a custom field (kind=" + kind + ")");
        }
        return new StringRef(field(path));
    }

    /** A typed date handle. Throws if the field at {@code path} is not a date. */
    public DateRef date(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE) {
            throw new IllegalArgumentException(path + " is not a date field (kind=" + kind + ")");
        }
        return new DateRef(field(path));
    }

    /** A typed time-of-day handle. Throws if the field at {@code path} is not a time. */
    public TimeRef time(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.TIME) {
            throw new IllegalArgumentException(path + " is not a time field (kind=" + kind + ")");
        }
        return new TimeRef(field(path));
    }

    /** A typed date-time handle (the source a date-time is decomposed from). Throws if not a date-time. */
    public DateTimeRef dateTime(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE_TIME) {
            throw new IllegalArgumentException(path + " is not a date-time field (kind=" + kind + ")");
        }
        return new DateTimeRef(field(path));
    }

    /** A typed date-fragment handle (a year-month partial date; an extraction source). Throws if not a date fragment. */
    public DateFragmentRef dateFragment(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE_FRAGMENT) {
            throw new IllegalArgumentException(path + " is not a date-fragment field (kind=" + kind + ")");
        }
        return new DateFragmentRef(field(path));
    }

    /** A typed date-range handle (an interval of two dates; source of Start/EndOfDateRange). Throws if not a date range. */
    public DateRangeRef dateRange(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.DATE_RANGE) {
            throw new IllegalArgumentException(path + " is not a date-range field (kind=" + kind + ")");
        }
        return new DateRangeRef(field(path));
    }

    /** A typed group handle (knows repeatability from the model). Throws if the path is not a group. */
    public GroupRef group(String path) {
        return new GroupRef(EntityPath.of(path), index.isRepeating(path));
    }

    /**
     * Whether a rule whose error entity is {@code errorFieldPath} <em>iterates</em> — i.e. a
     * repeatable group lies on the path to it (the §5a error-field rule). The F4a lint layer uses
     * this to pre-warn the kernel-delegated {@code MVK_NEG_CONDITION_IN_ITERATION}.
     */
    public boolean iteratesForErrorField(String errorFieldPath) {
        return nearestRepeatableAncestor(errorFieldPath).isPresent();
    }

    /** The nearest (deepest) repeatable group on the path to {@code fieldPath}, if any. */
    public java.util.Optional<String> nearestRepeatableAncestor(String fieldPath) {
        String path = fieldPath;
        int slash;
        while ((slash = path.lastIndexOf('/')) > 0) {
            path = path.substring(0, slash);
            if (index.isRepeating(path)) {
                return java.util.Optional.of(path);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * A flattened repetition list from a star path, e.g. {@code /Subscription/Addons*&#47;Name}
     * (KERNEL-SEMANTICS §9). Every starred segment must be a repeatable group — a star on a
     * non-repeatable group is rejected here with the kernel's code named
     * ({@code MVK_INVALID_WILDCARD}); the base path must resolve to a field.
     */
    public FieldListRef flatten(String starPath) {
        if (!starPath.contains("*")) {
            throw new IllegalArgumentException("no '*' in " + starPath
                    + " — a plain field reference is field(path)/num(path)/…");
        }
        requireAbsolute(starPath, "flatten");
        var flattened = new java.util.ArrayList<GroupRef>();
        StringBuilder base = new StringBuilder();
        for (String segment : starPath.substring(1).split("/")) {
            boolean starred = segment.endsWith("*");
            String name = starred ? segment.substring(0, segment.length() - 1) : segment;
            base.append('/').append(name);
            if (starred) {
                String groupPath = base.toString();
                if (!index.isRepeating(groupPath)) {
                    throw new IllegalArgumentException("'" + groupPath + "' is not repeatable —"
                            + " a star is only allowed on repeatable groups (MVK_INVALID_WILDCARD)");
                }
                flattened.add(new GroupRef(EntityPath.of(groupPath), true));
            }
        }
        return new FieldListRef(field(base.toString()), flattened);
    }

    /**
     * An exact group scope from a (possibly starred) group path, e.g.
     * {@code /MVK/Categories*&#47;Values*} or a bare {@code /MVK/SortFields}. Group-scoped field-fill
     * quantifiers use it for contained fields; {@code NoGroupFilled}/{@code AtLeastOneGroupFilled}
     * use the legal starred single-group form to count repetitions. Every starred segment must be a
     * repeatable group ({@code MVK_INVALID_WILDCARD}); the path resolves to a group (the group
     * analogue of {@link #flatten(String)}).
     */
    public GroupListRef flattenGroup(String starGroupPath) {
        requireAbsolute(starGroupPath, "flattenGroup");
        var flattened = new java.util.ArrayList<GroupRef>();
        StringBuilder base = new StringBuilder();
        for (String segment : starGroupPath.substring(1).split("/")) {
            boolean starred = segment.endsWith("*");
            String name = starred ? segment.substring(0, segment.length() - 1) : segment;
            base.append('/').append(name);
            if (starred) {
                String groupPath = base.toString();
                if (!index.isRepeating(groupPath)) {
                    throw new IllegalArgumentException("'" + groupPath + "' is not repeatable —"
                            + " a star is only allowed on repeatable groups (MVK_INVALID_WILDCARD)");
                }
                flattened.add(new GroupRef(EntityPath.of(groupPath), true));
            }
        }
        return new GroupListRef(group(base.toString()), flattened);
    }

    /**
     * A group operand for {@code FirstFilledValue}, preserving the group as one owner entity while deriving
     * its recursively expanded fields from this live model for result-family validation. A star on a
     * repeatable segment is retained, so read-back does not replace one owner entity with another shape.
     */
    public FirstFilledOperand firstFilledGroup(String starGroupPath) {
        GroupListRef scope = firstFilledGroupScope(starGroupPath);
        return FirstFilledOperand.group(scope, firstFilledDescendants(scope));
    }

    /** A group selected by a literal semantic-index key for {@code FirstFilledValue}. */
    public FirstFilledOperand firstFilledGroupFor(String groupPath, String indexValue) {
        GroupListRef scope = firstFilledGroupScope(groupPath);
        requireExactSemanticIndexGroup(scope);
        return FirstFilledOperand.group(scope, firstFilledDescendants(scope)).forIndex(indexValue);
    }

    /** A group selected by a field-valued semantic-index key for {@code FirstFilledValue}. */
    public FirstFilledOperand firstFilledGroupFor(String groupPath, FieldRef indexField) {
        GroupListRef scope = firstFilledGroupScope(groupPath);
        requireExactSemanticIndexGroup(scope);
        return FirstFilledOperand.group(scope, firstFilledDescendants(scope)).forIndex(indexField);
    }

    /**
     * Both starred-path entry points read the model root-first and drop the leading {@code /} before
     * splitting, so a RELATIVE path loses its first character instead of failing: {@code "Claims*&#47;X"}
     * became {@code /laims} and reported "no element at path", which reads as a broken model rather than a
     * mis-spelled argument. (Relative navigation is the editor's {@code GroupInfo.flatten}; this one is
     * model-absolute.)
     */
    private static void requireAbsolute(String starPath, String method) {
        if (!starPath.startsWith("/")) {
            throw new IllegalArgumentException(method + " takes a MODEL-ABSOLUTE path and got '" + starPath
                    + "' — write it from the root (e.g. \"/Order/Items*/Count\"), or navigate relatively off"
                    + " an editor/info instead");
        }
    }

    private GroupListRef firstFilledGroupScope(String starGroupPath) {
        GroupListRef scope = flattenGroup(starGroupPath);
        if (groupAt(scope.path()).isEmpty()) {
            throw new IllegalArgumentException(scope.path() + " is not a group");
        }
        if (scope.path().indexOf('/', 1) < 0) {
            throw new IllegalArgumentException("FirstFilledValue does not accept a root group"
                    + " (MVK_ROOT_GROUP_REFERENCED): " + scope.path());
        }
        return scope;
    }

    private List<FieldRef> firstFilledDescendants(GroupListRef scope) {
        String prefix = scope.path() + "/";
        return fields().filter(field -> field.path().startsWith(prefix)).map(FieldInfo::ref).toList();
    }

    /** Package-visible so {@link IndexedGroupValue}'s leaf records share this guard rather than each
     *  re-stating it — an index and a wildcard can never address the same group, regardless of which
     *  construct (this class's own {@code firstFilledGroupFor}, or the general entity-list arm) builds
     *  the indexed group. */
    static void requireExactSemanticIndexGroup(GroupListRef scope) {
        if (!scope.flattened().isEmpty()) {
            throw new IllegalArgumentException("a semantic index and wildcard may not address the same group"
                    + " (MVK_SEMANTIC_INDEX_AND_WILDCARD): " + scope.renderedPath());
        }
    }

    // --- navigation (Level 1, study finding 9): streams of info handles; filter is the matcher --

    /** Every field, in model order, as rich {@link FieldInfo} descriptors. */
    public java.util.stream.Stream<FieldInfo> fields() {
        return index.fieldPaths().map(this::fieldInfo);
    }

    /** The fields directly contained in {@code group}, in model order. */
    public java.util.stream.Stream<FieldInfo> fields(GroupRef group) {
        return index.fieldPaths(group.path().value()).map(this::fieldInfo);
    }

    /** Every group (root groups included), in model order. */
    public java.util.stream.Stream<GroupInfo> groups() {
        return index.groupPaths().map(this::groupInfo);
    }

    /** The declared message-locale codes (header {@code locales}), in document order — every rule and
     *  computation must carry a message for each (MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING). */
    public java.util.List<String> locales() {
        return index.locales();
    }

    /** The group's maximum repetitions — the repeatability count ({@code 1} = non-repeatable). */
    public int maxRepetitions(String path) {
        return index.maxRepetitions(path);
    }

    /** The non-throwing peek — exploring is lenient; narrowing (on {@link FieldInfo}) is strict. */
    public java.util.Optional<FieldInfo> fieldAt(String path) {
        return index.isField(path) ? java.util.Optional.of(fieldInfo(path)) : java.util.Optional.empty();
    }

    /** The non-throwing group peek — the group peer of {@link #fieldAt}; relative navigation hangs off
     *  {@link GroupInfo} (so a combinator can be written against a root group and applied to any subtree). */
    public java.util.Optional<GroupInfo> groupAt(String path) {
        return index.isGroup(path) ? java.util.Optional.of(groupInfo(path)) : java.util.Optional.empty();
    }

    private FieldInfo fieldInfo(String path) {
        return new FieldInfo(this, path, index.nameOf(path), index.kindOf(path));
    }

    private GroupInfo groupInfo(String path) {
        return new GroupInfo(this, path, index.nameOf(path), index.isRepeating(path));
    }

    /** A field's element-level annotations, read back kernel-free through the {@link ModelIndex} (the slot the
     *  silent-ignore deserializer would otherwise hide). Backs {@link FieldInfo#annotations()}. */
    java.util.List<Annotation> annotationsOf(String fieldPath) {
        return map(index.annotationsOf(fieldPath));
    }

    /** A group's element-level annotations (kernel-free, via {@link ModelIndex}). Backs {@link GroupInfo#annotations()}. */
    java.util.List<Annotation> annotationsOfGroup(String groupPath) {
        return map(index.annotationsOfGroup(groupPath));
    }

    java.util.Optional<String> usageTypeOfGroup(String groupPath) {
        return index.usageTypeOfGroup(groupPath);
    }

    private static java.util.List<Annotation> map(java.util.List<io.github.mbackschat.a12.dm.core.Annotation> as) {
        return as.stream().map(a -> new Annotation(a.name(), a.value())).toList();
    }

    /** A typed boolean handle ({@code isTrue()}/{@code isFalse()}). Throws if not a boolean field. */
    public BoolRef bool(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.BOOLEAN) {
            throw new IllegalArgumentException(path + " is not a boolean field (kind=" + kind + ")");
        }
        return new BoolRef(field(path));
    }

    /**
     * A typed confirm handle — a distinct kind from boolean (§5b): offers {@code confirmed()} /
     * {@code notConfirmed()} only (confirm never compares to {@code False}). Throws if not a
     * confirm field.
     */
    public ConfirmRef confirm(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.CONFIRM) {
            throw new IllegalArgumentException(path + " is not a confirm field (kind=" + kind + ")");
        }
        return new ConfirmRef(field(path));
    }

    /** A typed enumeration handle carrying the model's allowed values. Throws if not an enum. */
    public EnumRef enumOf(String path) {
        ValueKind kind = index.kindOf(path);
        if (kind != ValueKind.ENUMERATION) {
            throw new IllegalArgumentException(path + " is not an enumeration field (kind=" + kind + ")");
        }
        return new EnumRef(field(path), index.enumValues(path), index.enumCategories(path));
    }

    // --- authoring objects (F2/F2a) — model-aware so messages check against DECLARED locales ----

    /** Start a rule in {@code group}; messages are checked against the model's declared locales. */
    public Rule.Builder rule(GroupReference group) {
        return new Rule.Builder(group.ref(), index.locales());
    }

    /** Start a computation for {@code computed}; the element is placed in the field's group. */
    public Computation.Builder computation(NumberRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(StringRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(EnumRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(BoolRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(ConfirmRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(DateRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(TimeRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(DateTimeRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(DateFragmentRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(DateRangeRef computed) {
        return computation(computed.field());
    }

    public Computation.Builder computation(FieldRef computed) {
        return new Computation.Builder(computed, index.locales());
    }

    // --- field-list quantifiers (PROJECT-DESIGN §5) — each validates every path against the model -------

    public Condition allFieldsFilled(String... paths) {
        return fieldList(Quantifier.ALL_FIELDS_FILLED, paths);
    }

    public Condition noFieldFilled(String... paths) {
        return fieldList(Quantifier.NO_FIELD_FILLED, paths);
    }

    public Condition atLeastOneFieldFilled(String... paths) {
        return fieldList(Quantifier.AT_LEAST_ONE_FIELD_FILLED, paths);
    }

    public Condition notAllFieldsFilled(String... paths) {
        return fieldList(Quantifier.NOT_ALL_FIELDS_FILLED, paths);
    }

    public Condition fieldsNotCollectivelyFilled(String... paths) {
        return fieldList(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, paths);
    }

    public Condition notExactlyOneFieldFilled(String... paths) {
        return fieldList(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, paths);
    }

    public Condition moreThanOneFieldFilled(String... paths) {
        return fieldList(Quantifier.MORE_THAN_ONE_FIELD_FILLED, paths);
    }

    private Condition fieldList(Quantifier quantifier, String[] paths) {
        if (paths.length == 0) {
            throw new IllegalArgumentException(quantifier.operatorId() + " needs at least one field");
        }
        return Dsl.fieldList(quantifier, Arrays.stream(paths).map(this::field).toList());
    }
}
