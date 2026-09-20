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
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

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
     * Adds {@code computation} to the model (in its own declaring group) and returns a <em>new</em>
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

    /**
     * A typed date handle — for a DATE field, or for any temporal field whose DECLARED FORMAT is date-bearing.
     * Throws otherwise.
     *
     * <p>The second half exists because A12's temporal format vocabulary is kind-independent and everything
     * below the declaration reads the format: a TIME field declared {@code yyyy-MM-dd} carries the Gregorian
     * floor, compares directly against a DATE, and answers {@code YearFromDate}, while its own kind's
     * {@code HoursFromTime} is refused (KERNEL-FINDINGS KF211, measured on both codegen strategies). Admitting
     * it here is what makes that kernel-legal condition authorable at all; before, there was no error to read,
     * only a missing factory, so the shape read as "not a thing" rather than "refused".
     *
     * <p>The kind test is kept as an alternative rather than replaced, deliberately: refusing a kind-matching
     * field because its declared format disagrees would be a client-side refusal with no measured kernel basis,
     * and the kernel's own authoring gate still reports any operand it does not accept.
     */
    public DateRef date(String path) {
        requireTemporalShape(path, ValueKind.DATE, TemporalFormats.Shape.DATE, "date");
        return new DateRef(field(path));
    }

    /** A typed time-of-day handle — a TIME field, or a temporal field declared with a clock format ({@link #date}). */
    public TimeRef time(String path) {
        requireTemporalShape(path, ValueKind.TIME, TemporalFormats.Shape.TIME, "time");
        return new TimeRef(field(path));
    }

    /**
     * A typed date-time handle (the source a date-time is decomposed from) — a DATE_TIME field, or a temporal
     * field whose declared format carries both halves ({@link #date}).
     */
    public DateTimeRef dateTime(String path) {
        requireTemporalShape(path, ValueKind.DATE_TIME, TemporalFormats.Shape.DATE_TIME, "date-time");
        return new DateTimeRef(field(path));
    }

    /** The admission shared by {@link #date}/{@link #time}/{@link #dateTime} — see {@link #date} for the why. */
    private void requireTemporalShape(
            String path, ValueKind wantedKind, TemporalFormats.Shape wantedShape, String label) {
        ValueKind kind = index.kindOf(path);
        if (kind == wantedKind) {
            return;
        }
        if (TemporalFormats.isTemporal(kind)) {
            String declared = index.temporalFormat(path).orElseGet(() -> TemporalFormats.defaultFormat(kind));
            if (TemporalFormats.shapeOf(declared) == wantedShape) {
                return;
            }
            throw new IllegalArgumentException(path + " is not a " + label + " field (kind=" + kind
                    + ", declared format=" + declared + ")");
        }
        throw new IllegalArgumentException(path + " is not a " + label + " field (kind=" + kind + ")");
    }

    /**
     * Whether the model declares a base year. The kit-internal read of
     * {@link io.github.mbackschat.a12.dm.core.ModelIndex#baseYear}, for the temporal gates that supplement an
     * operand's component set with YEAR when one is declared.
     */
    boolean hasBaseYear() {
        return index.baseYear().isPresent();
    }

    // --- BaseYear, checked against the model's declaration --------------------------------------------
    //
    // The precondition is the model's, not the operand's, so it is the same check for every carrier and it is
    // EXACTLY decidable — a model either declares a base year or it does not. That is what separates it from a
    // format gate over the same constant, which is measured NOT to exist
    // (`validate.laws.BaseYearExtractionFormatLawsTest`: the kernel admits month, day and quarter extraction
    // over `BaseYear` while refusing all three over a `yyyy` field). One rule is a property of the model and
    // safe to state; the other would be an invention. The four factories mirror `Dsl`'s one-for-one, and each
    // is reached by the condition-text consumer — `PromotedGateIsReachedGuardTest` keeps it that way.

    /** {@code BaseYear} as a comparable date, checked against this model's declaration. catalog:BaseYear */
    public DateExpr baseYear() {
        requireDeclaredBaseYear("BaseYear");
        return Dsl.baseYear();
    }

    /** {@code BaseYear} as the year number, checked against this model's declaration. catalog:BaseYear */
    public NumberExpr baseYearNumber() {
        requireDeclaredBaseYear("BaseYear");
        return Dsl.baseYearNumber();
    }

    /** {@code StartOfDateRange(BaseYear)}, checked against this model's declaration. catalog:StartOfDateRange */
    public DateExpr startOfBaseYear() {
        requireDeclaredBaseYear("StartOfDateRange(BaseYear)");
        return Dsl.startOfBaseYear();
    }

    /** {@code EndOfDateRange(BaseYear)}, checked against this model's declaration. catalog:EndOfDateRange */
    public DateExpr endOfBaseYear() {
        requireDeclaredBaseYear("EndOfDateRange(BaseYear)");
        return Dsl.endOfBaseYear();
    }

    /**
     * Every carrier of the constant needs the model to declare a base year, measured uniformly over all six the
     * kits can build — the date, numeric and date-time roles, an extraction over it, and both range bounds
     * ({@code validate.laws.BaseYearDeclarationLawsTest}, each row with its admitted control on a fixture
     * differing in one builder call).
     *
     * <p>The extraction carrier needs no gate of its own: it takes a {@link DateOperand}, so it can only be
     * built over a constant one of these factories already returned.
     */
    private void requireDeclaredBaseYear(String construct) {
        if (hasBaseYear()) {
            return;
        }
        throw Refusals.of(KernelCode.MVK_NO_BASE_YEAR,
                construct + " needs the model to declare a base year; this model declares none");
    }

    /**
     * The declared temporal format at {@code fieldPath}, or {@code null} when the field declares none. The
     * kit-internal read of {@link io.github.mbackschat.a12.dm.core.ModelIndex#temporalFormat}, for the gates
     * that key on the declaration rather than the kind.
     */
    String temporalFormat(String fieldPath) {
        return index.temporalFormat(fieldPath).orElse(null);
    }

    /**
     * The field's declared date precision, or {@code null} when it declares none — which means FULL, since
     * A12's serializer omits the key for the default (see {@code ModelIndex.datePrecision}).
     */
    String datePrecision(String fieldPath) {
        return index.datePrecision(fieldPath).orElse(null);
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

    /** Whether the group at {@code groupPath} itself repeats — the peer of {@link #nearestRepeatableAncestor}
     *  for an operand that IS a group rather than a field. */
    public boolean isRepeatingGroup(String groupPath) {
        return index.isRepeating(groupPath);
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
                    throw Refusals.of(KernelCode.MVK_INVALID_WILDCARD, "'" + groupPath + "' is not repeatable —"
                            + " a star is only allowed on repeatable groups");
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
                    throw Refusals.of(KernelCode.MVK_INVALID_WILDCARD, "'" + groupPath + "' is not repeatable —"
                            + " a star is only allowed on repeatable groups");
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
            throw Refusals.of(KernelCode.MVK_ROOT_GROUP_REFERENCED,
                    "FirstFilledValue does not accept a root group: " + scope.path());
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
            throw Refusals.of(KernelCode.MVK_SEMANTIC_INDEX_AND_WILDCARD,
                    "a semantic index and wildcard may not address the same group: " + scope.renderedPath());
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

    /** Whether the group declares an index field — half of the iteration-admission rule (§9). */
    public boolean declaresIndexField(String groupPath) {
        return index.declaresIndexField(groupPath);
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

    /** Start a computation for {@code computed}; the element lands in the field's group unless
     *  {@link Computation.Builder#group(GroupReference)} declares it elsewhere. */
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
        // The target's declared temporal format, not just its kind: the kernel's assignment gate reads the
        // format string and the kind reaches it nowhere ([KF222]), so the builder is given what the kernel
        // reads. Empty for a non-temporal field, and for a temporal one that declares no format and so takes
        // its kind's default — where the two agree by construction.
        return new Computation.Builder(computed, index.locales(),
                index.temporalFormat(computed.path().value()).orElse(null));
    }

    // --- field-list quantifiers (PROJECT-DESIGN §5) — each validates every path against the model -------

    // --- The entity-list aggregates, checked ------------------------------------------------------------
    //
    // These live here rather than on `Dsl` because their admissibility is CONTEXTUAL: the kernel gates them
    // on the operands' declared component sets and date precision, which the operands themselves do not
    // carry. `Dsl` constructs from resolved operands and stays model-free, which is what the inbound
    // converters need — reconstructing a kernel-accepted condition must never refuse. This is the authoring
    // layer, so it is the one that checks. (PROJECT-DESIGN: rulekit is model-bound at its core.)
    //
    // Operand-taking rather than path-taking, unlike most of this class: an index-selected cell is a legal
    // operand and has no path spelling.

    /** {@code MaxValue(…)} over DATE operands, checked against this model. catalog:MaxValue */
    public DateExpr latestOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireFullDatePrecision("MaxValue", this, first, rest);
        Dsl.requireComparableComponentSets("MaxValue", this, first, rest);
        return Dsl.latestOf(first, rest);
    }

    /** {@code MinValue(…)} over DATE operands, checked against this model. catalog:MinValue */
    public DateExpr earliestOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireFullDatePrecision("MinValue", this, first, rest);
        Dsl.requireComparableComponentSets("MinValue", this, first, rest);
        return Dsl.earliestOf(first, rest);
    }

    /** {@code MaxValue(…)} over TIME operands, checked against this model. catalog:MaxValue */
    public TimeExpr latestTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireFullDatePrecision("MaxValue", this, first, rest);
        Dsl.requireComparableComponentSets("MaxValue", this, first, rest);
        return Dsl.latestTimeOf(first, rest);
    }

    /** {@code MinValue(…)} over TIME operands, checked against this model. catalog:MinValue */
    public TimeExpr earliestTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireFullDatePrecision("MinValue", this, first, rest);
        Dsl.requireComparableComponentSets("MinValue", this, first, rest);
        return Dsl.earliestTimeOf(first, rest);
    }

    /** {@code MaxValue(…)} over DATETIME operands, checked against this model. catalog:MaxValue */
    public DateTimeExpr latestDateTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireFullDatePrecision("MaxValue", this, first, rest);
        Dsl.requireComparableComponentSets("MaxValue", this, first, rest);
        return Dsl.latestDateTimeOf(first, rest);
    }

    /** {@code MinValue(…)} over DATETIME operands, checked against this model. catalog:MinValue */
    public DateTimeExpr earliestDateTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireFullDatePrecision("MinValue", this, first, rest);
        Dsl.requireComparableComponentSets("MinValue", this, first, rest);
        return Dsl.earliestDateTimeOf(first, rest);
    }

    /** {@code NumberOfDifferentValues(…)}, checked against this model. catalog:NumberOfDifferentValues */
    public NumberExpr numberOfDifferentValues(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireFullDatePrecision("NumberOfDifferentValues", this, first, rest);
        Dsl.requireComparableComponentSets("NumberOfDifferentValues", this, first, rest);
        return Dsl.numberOfDifferentValues(first, rest);
    }

    // --- Date-range comparability, checked --------------------------------------------------------------
    //
    // Contextual, so it lives here rather than on DateRangeOperand.eq/ne: the same operand pair is
    // admissible or not depending on the model. The ungated eq/ne stay for the inbound converters.

    /** {@code left == right} between two date ranges, checked against this model's declarations. */
    public Condition rangesEqual(DateRangeOperand left, DateRangeOperand right) {
        requireComparableRangeComponents(left, right);
        return left.eq(right);
    }

    /** {@code left != right} between two date ranges, checked against this model's declarations. */
    public Condition rangesNotEqual(DateRangeOperand left, DateRangeOperand right) {
        requireComparableRangeComponents(left, right);
        return left.ne(right);
    }

    /**
     * {@code DateRange(start, end)}, checked against this model's declarations. Contextual for the same reason
     * the comparison above is: an endpoint's component set comes from its field's declared format.
     *
     * <p>Distinctly named rather than an overload of {@link #dateRange(String)} so that a read path reaching
     * the checked form is visible to a text scan ({@code dsl.ReadPathFormatGateIsolationTest}).
     * {@link Dsl#dateRange} stays ungated for the inbound converters.
     */
    public DateRangeConstruction dateRangeOf(DateOperand start, DateOperand end) {
        return dateRangeOf(start.expression(), end.expression());
    }

    /**
     * The same construction from two OPAQUE expressions — the form a condition-text converter holds, which has
     * no typed date handle to pass. It resolves rather than merely validates, which is why it takes the model
     * (the reasoning A3's {@code Dsl.dateExtraction} records). The record's own compact constructor still
     * applies the intrinsic endpoint gate first.
     */
    public DateRangeConstruction dateRangeOf(Calculation start, Calculation end) {
        DateRangeConstruction construction = new DateRangeConstruction(start, end);
        requireAgreeingEndpointComponents(construction);
        return construction;
    }

    /**
     * {@code Date(day, month, year)}, checked against this model's declarations. A NUMBER component must
     * declare bounds that fit its own SLOT; the record's compact constructor keeps the intrinsic
     * extractor-position gate, which needs no model.
     */
    public DateConstruction dateOf(Calculation day, Calculation month, Calculation year) {
        DateConstruction construction = new DateConstruction(day, month, year);
        requireSlotCompatibleNumberBounds(day, DAY_SLOT_MAX, "day");
        requireSlotCompatibleNumberBounds(month, MONTH_SLOT_MAX, "month");
        return construction;
    }

    /** The four-component form. Its century and decade slots carry no measured bound rule, so they delegate. */
    public DateConstruction dateOf(Calculation day, Calculation month, Calculation century, Calculation decade) {
        DateConstruction construction =
                new DateConstruction(day, month, century, java.util.Optional.of(decade));
        requireSlotCompatibleNumberBounds(day, DAY_SLOT_MAX, "day");
        requireSlotCompatibleNumberBounds(month, MONTH_SLOT_MAX, "month");
        return construction;
    }

    private static final java.math.BigDecimal DAY_SLOT_MAX = java.math.BigDecimal.valueOf(31);
    private static final java.math.BigDecimal MONTH_SLOT_MAX = java.math.BigDecimal.valueOf(12);
    /** Both gated slots are two digits wide, which is the length form of their ceiling. */
    private static final int SLOT_MAX_LENGTH = 2;

    /**
     * A NUMBER component must satisfy THREE conjuncts, and two of them have two satisfying forms each
     * ({@code validate.laws.DateConstructionComponentDeclarationLawsTest}, kernel
     * {@code MVK_WRONG_DATE_FORMAT_FOR_OP}):
     *
     * <ol>
     * <li>no declared FRACTION — the component must be a whole number;</li>
     * <li>a CEILING, as either a {@code maxLength} of the slot's width or a {@code maxValue} equal to the
     *     slot's own maximum. The value form is per-slot and exact rather than containment: a 1..31 field is
     *     admitted as a day and refused as a month, and 1..30 is refused as a day although every value it
     *     holds is a legal day;</li>
     * <li>a LOW SIDE, as either a declared NON-NEGATIVE minimum or an UNSIGNED declaration
     *     ({@code positivesOnly}). Either alone suffices, which is why a field with a ceiling and no minimum
     *     at all is a legal slot — the shape real models use.</li>
     * </ol>
     *
     * <p>The year slot is not gated here: its ceiling is flexible in a way these two are not, and nothing
     * measures it.
     */
    private void requireSlotCompatibleNumberBounds(Calculation component,
            java.math.BigDecimal slotMax, String slot) {
        String path = declaredNumberFieldPath(component);
        if (path == null) {
            return;
        }
        if (index.numberScale(path) != 0) {
            throw slotRefusal(slot, path, "declares a fraction; a date component must be a whole number");
        }
        var maxLength = index.numberMaxLength(path);
        var max = index.numberMax(path);
        boolean ceiling = (maxLength.isPresent() && maxLength.getAsInt() == SLOT_MAX_LENGTH)
                || (max.isPresent() && max.get().compareTo(slotMax) == 0);
        if (!ceiling) {
            throw slotRefusal(slot, path, "needs a ceiling: either maxLength " + SLOT_MAX_LENGTH
                    + " or maxValue " + slotMax.toPlainString());
        }
        var min = index.numberMin(path);
        boolean lowSide = (min.isPresent() && min.get().signum() >= 0) || index.numberPositivesOnly(path);
        if (!lowSide) {
            throw slotRefusal(slot, path, "needs either a non-negative minValue or positivesOnly");
        }
    }

    private static IllegalArgumentException slotRefusal(String slot, String path, String why) {
        return Refusals.of(KernelCode.MVK_WRONG_DATE_FORMAT_FOR_OP,
                "the " + slot + " component '" + path + "' " + why);
    }

    /** The component's field path when it is a declared NUMBER entity reference, else {@code null}. */
    private static String declaredNumberFieldPath(Calculation component) {
        FieldRef field = switch (component) {
            case FieldValue value -> value.field();
            case IndexedFieldValue value -> value.field();
            default -> null;
        };
        return field != null && field.kind() == ValueKind.NUMBER ? field.path().value() : null;
    }

    /**
     * Both endpoints must expose the same declared date-component set. The kernel reports
     * {@code MVK_WRONG_DATE_FORMAT_FOR_OP} <b>at the construction</b>, naming the offending endpoint, and
     * reports it whatever the construction is later compared against — so this is not the comparison gate
     * above under another name, and the two are held apart by
     * {@code validate.laws.DateRangeConstructionEndpointComponentLawsTest}, which asserts the comparison's
     * code absent.
     *
     * <p>An endpoint whose declaration cannot be resolved is DELEGATED rather than refused.
     */
    private void requireAgreeingEndpointComponents(DateRangeConstruction construction) {
        java.util.Set<TemporalFormats.Component> start = endpointComponents(construction.start());
        java.util.Set<TemporalFormats.Component> end = endpointComponents(construction.end());
        if (start == null || end == null || start.equals(end)) {
            return;
        }
        throw Refusals.of(KernelCode.MVK_WRONG_DATE_FORMAT_FOR_OP,
                "a date-range construction needs both endpoints to declare the same date components; got "
                        + start + " and " + end);
    }

    /**
     * Both range operands must expose the same declared date-component set (KERNEL-FINDINGS
     * {@code DateRangeComparabilityLawsTest}). An operand whose declaration cannot be resolved — a computed
     * range — is DELEGATED rather than refused.
     *
     * <p><b>Base-year completion is deliberately NOT applied</b>, unlike the entity-list aggregates' gate:
     * the kernel passes {@code false} here, and a declared base year is measured not to make a yearless range
     * comparable. The two rules look alike and differ on exactly that, so they do not share a helper.
     */
    private void requireComparableRangeComponents(DateRangeOperand left, DateRangeOperand right) {
        java.util.Set<TemporalFormats.Component> a = rangeComponents(left);
        java.util.Set<TemporalFormats.Component> b = rangeComponents(right);
        if (a == null || b == null || a.equals(b)) {
            return;
        }
        throw Refusals.of(KernelCode.MVK_INVALID_COMPARE_TO_DATE_RANGE,
                "a date-range comparison needs both operands to declare the same "
                        + "date components; got " + a + " and " + b);
    }

    /** The operand's declared component set, or {@code null} when there is nothing to read. */
    private java.util.Set<TemporalFormats.Component> rangeComponents(DateRangeOperand operand) {
        return switch (operand) {
            case DateRangeRef ref -> componentsOfField(ref.field().path().value());
            case DateRangeConstruction construction -> {
                // A construction's profile is its endpoints'. Disagreeing endpoints are refused by
                // dateRangeOf, proven by dsl.DateRangeEndpointComponentGateTest — but only on THAT route: one
                // built through the deliberately ungated Dsl.dateRange reaches no client gate at all, so this
                // arm delegates rather than assuming the pair was checked.
                var start = endpointComponents(construction.start());
                var end = endpointComponents(construction.end());
                yield start != null && start.equals(end) ? start : null;
            }
            // An INDEX-SELECTED range delegates: A9 measured that carrier for the extraction-format and
            // aggregate component-set gates, not for this one, and reusing that verdict here would be a
            // scope transfer rather than a measurement.
            default -> null;
        };
    }

    private java.util.Set<TemporalFormats.Component> componentsOfField(String path) {
        String declared = temporalFormat(path);
        return declared == null ? null : TemporalFormats.componentsOf(declared);
    }

    private java.util.Set<TemporalFormats.Component> endpointComponents(Calculation endpoint) {
        return switch (endpoint) {
            case FieldValue value -> componentsOfField(value.field().path().value());
            case IndexedFieldValue indexed -> componentsOfField(indexed.field().path().value());
            default -> null;
        };
    }

    // --- ValueAsDate: the REQUIRING direction of the precision declaration ------------------------------

    /** {@code ValueAsDate(field, FirstDay)}, checked to require a partially known date against this model. */
    public DateExpr valueAsDateFirstDay(FieldRef partiallyKnownDate) {
        requirePartiallyKnownDate(partiallyKnownDate);
        return Dsl.valueAsDateFirstDay(partiallyKnownDate);
    }

    /** {@code ValueAsDate(field, LastDay)}, checked to require a partially known date against this model. */
    public DateExpr valueAsDateLastDay(FieldRef partiallyKnownDate) {
        requirePartiallyKnownDate(partiallyKnownDate);
        return Dsl.valueAsDateLastDay(partiallyKnownDate);
    }

    /**
     * {@code ValueAsDate} admits only a partly-known date; a full-precision DateType and a DATE_FRAGMENT are
     * both {@code MVK_INVALID_DATE_TYPE_FOR_OP} (`validate.laws.ValueAsDateOperandPrecisionLawsTest`). Scoped
     * to those two kinds: neither the factory nor the record kind-checks, and no other kind was measured, so
     * refusing one here would state a rule nothing measured.
     */
    private void requirePartiallyKnownDate(FieldRef field) {
        ValueKind kind = field.kind();
        if (kind != ValueKind.DATE && kind != ValueKind.DATE_FRAGMENT) {
            return;
        }
        String path = field.path().value();
        String precision = datePrecision(path);
        if (!DatePrecisions.isRecognised(precision)) {
            return;
        }
        if (!DatePrecisions.isPartiallyKnown(precision)) {
            throw Refusals.of(KernelCode.MVK_INVALID_DATE_TYPE_FOR_OP,
                    "ValueAsDate needs a partially known date; '" + path
                            + "' declares no optional component");
        }
    }

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
