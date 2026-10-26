package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.EntityPath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

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
    public static BoundModel load(Path path) {
        try {
            return ofJson(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read model from " + path, e);
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
        return ofJson(ModelWriter.addRule(requireWritable(), rule), resolver);
    }

    /**
     * Adds {@code computation} to the model (in its own declaring group) and returns a <em>new</em>
     * binding over the updated DM-JSON; this binding is unchanged. Re-validated on re-bind (a write that
     * breaks the model fails loudly here), the computation twin of {@link #add(Rule)}.
     */
    public BoundModel add(Computation computation) {
        return ofJson(ModelWriter.addComputation(requireWritable(),
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
                ModelWriter.addAll(
                        requireWritable(), rules, computations),
                resolver);
    }

    /**
     * Removes the rule at {@code ruleFullName}, returning a <em>new</em> binding (this one is unchanged).
     * Re-validated on re-bind. Throws if no rule exists at that path.
     */
    public BoundModel removeRule(String ruleFullName) {
        return ofJson(ModelWriter.removeRule(requireWritable(),
                ruleFullName), resolver);
    }

    /**
     * Removes the computation at {@code computationFullName}, returning a <em>new</em> binding (this one
     * is unchanged). Re-validated on re-bind. The computation twin of {@link #removeRule(String)}.
     */
    public BoundModel removeComputation(String computationFullName) {
        return ofJson(ModelWriter.removeComputation(requireWritable(),
                computationFullName), resolver);
    }

    /**
     * Replaces the computation at {@code computationFullName} with the re-expressed {@code computation},
     * returning a <em>new</em> binding (this one is unchanged); the F8 #3 write-back for computations
     * (remove the old element, add the new). Re-validated on re-bind, so a write that breaks the
     * computation fails loudly here. The computation twin of {@link #replaceCondition(String, Condition)}.
     */
    public BoundModel replaceComputation(String computationFullName, Computation computation) {
        return ofJson(ModelWriter.replaceComputation(
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
        return ofJson(ModelWriter.replaceCondition(
                requireWritable(), ruleFullName, newCondition), resolver);
    }

    /** The model as DM-JSON text (the retained source, with any writes applied). */
    public String toJson() {
        return requireWritable();
    }

    /** Persists the model's DM-JSON to {@code path} (the portable artifact). */
    public void writeTo(Path path) {
        try {
            Files.writeString(path, requireWritable());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write model to " + path, e);
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
    public Optional<Verification> consistencyGate() {
        return Optional.ofNullable(consistencyGate);
    }

    /** A generic handle to the field at {@code path} (any kind). Throws if the path is not a field. */
    public FieldRef field(String path) {
        ValueKind kind = index.kindOf(path);
        var ancestors = new java.util.HashSet<EntityPath>();
        String parent = path;
        while (parent.lastIndexOf('/') > 0) {
            parent = parent.substring(0, parent.lastIndexOf('/'));
            if (index.isRepeating(parent)) ancestors.add(EntityPath.of(parent));
        }
        return new FieldRef(EntityPath.of(path), kind, PathStyle.CANONICAL, false, Optional.of(ancestors));
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
     * <p>The declared class is the whole test, and a matching kind is not a second way in: A12 refuses a DATE
     * field declared {@code HH:mm:ss} as a date operand, compared or extracted, and admits it as a time, and
     * likewise refuses a TIME or DATE_TIME field declared {@code yyyy-MM-dd} as its own kind
     * ({@code validate.laws.ClockDeclaredDateFieldLawsTest}). Admitting by kind handed out a handle its field
     * could not be (DEV-API-GAPS DG132).
     */
    public DateRef date(String path) {
        requireTemporalShape(path, ValueKind.DATE, "date");
        return new DateRef(field(path));
    }

    /** A typed time-of-day handle — a TIME field, or a temporal field declared with a clock format ({@link #date}). */
    public TimeRef time(String path) {
        requireTemporalShape(path, ValueKind.TIME, "time");
        return new TimeRef(field(path));
    }

    /**
     * A typed date-time handle (the source a date-time is decomposed from) — a DATE_TIME field, or a temporal
     * field whose declared format carries both halves ({@link #date}).
     */
    public DateTimeRef dateTime(String path) {
        requireTemporalShape(path, ValueKind.DATE_TIME, "date-time");
        return new DateTimeRef(field(path));
    }

    /** The admission shared by {@link #date}/{@link #time}/{@link #dateTime} — see {@link #date} for the why. */
    private void requireTemporalShape(String path, ValueKind wantedClass, String label) {
        FieldInfo info = fieldInfo(path);
        if (info.temporalClass() == wantedClass) {
            return;
        }
        throw new IllegalArgumentException(path + " is not a " + label + " field (kind=" + info.kind()
                + info.format().map(format -> ", declared format=" + format).orElse("") + ")");
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
     * Whether a rule whose error entity, a field or a group, is at {@code errorEntityPath} <em>iterates</em>: a
     * repeatable group lies on the path to it (the §5a error-field rule), or it is itself a repeating group, which
     * A12 guards at its own level as it guards a field's (KERNEL-FINDINGS KF601). The F4a lint layer uses this to
     * pre-warn the kernel-delegated {@code MVK_NEG_CONDITION_IN_ITERATION}.
     */
    public boolean iteratesForErrorEntity(String errorEntityPath) {
        return nearestRepeatableAncestor(errorEntityPath).isPresent()
                || groupAt(errorEntityPath).filter(GroupInfo::repeating).isPresent();
    }

    /** Whether the group at {@code groupPath} itself repeats — the peer of {@link #nearestRepeatableAncestor}
     *  for an operand that IS a group rather than a field. */
    public boolean isRepeatingGroup(String groupPath) {
        return index.isRepeating(groupPath);
    }

    /** The nearest (deepest) repeatable group on the path to {@code fieldPath}, if any. */
    public Optional<String> nearestRepeatableAncestor(String fieldPath) {
        String path = fieldPath;
        int slash;
        while ((slash = path.lastIndexOf('/')) > 0) {
            path = path.substring(0, slash);
            if (index.isRepeating(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
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
        var flattened = new ArrayList<GroupRef>();
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
        return new FieldListRef(operandField(base.toString()), flattened);
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
        var flattened = new ArrayList<GroupRef>();
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
        return firstFilledGroupOperand(firstFilledGroupScope(starGroupPath), null);
    }

    /** A group selected by a literal semantic-index key for {@code FirstFilledValue}. */
    public FirstFilledOperand firstFilledGroupFor(String groupPath, String indexValue) {
        GroupListRef scope = firstFilledGroupScope(groupPath);
        requireNoStarAtOrAboveIndexGroup(scope);
        return firstFilledGroupOperand(scope, new FirstFilledOperand.LiteralIndex(indexValue));
    }

    /** A group selected by a field-valued semantic-index key for {@code FirstFilledValue}. */
    public FirstFilledOperand firstFilledGroupFor(String groupPath, FieldRef indexField) {
        GroupListRef scope = firstFilledGroupScope(groupPath);
        requireNoStarAtOrAboveIndexGroup(scope);
        return firstFilledGroupOperand(scope, new FirstFilledOperand.FieldIndex(indexField));
    }

    private FirstFilledOperand firstFilledGroupOperand(GroupListRef scope,
            FirstFilledOperand.SemanticIndexKey semanticIndex) {
        return FirstFilledOperand.group(scope, firstFilledDescendants(scope), crossesUnstarredRepetition(scope),
                semanticIndex);
    }

    /** Whether some level of {@code scope}, the group itself included, repeats but carries no star. */
    boolean crossesUnstarredRepetition(GroupListRef scope) {
        return crossesUnstarredRepetition(scope.path(), scope.flattened(), true);
    }

    /** Whether some group above {@code field} repeats; a plain reference stars none of them. */
    boolean crossesUnstarredRepetition(FieldRef field) {
        return crossesUnstarredRepetition(field.path().value(), List.of(), false);
    }

    /** Whether some group above the list's field repeats but carries no star. */
    boolean crossesUnstarredRepetition(FieldListRef list) {
        return crossesUnstarredRepetition(list.field().path().value(), list.flattened(), false);
    }

    private boolean crossesUnstarredRepetition(String path, List<GroupRef> flattened, boolean includeLast) {
        var starred = flattened.stream().map(level -> level.path().value()).toList();
        String[] segments = path.substring(1).split("/");
        StringBuilder level = new StringBuilder();
        for (int i = 0; i < (includeLast ? segments.length : segments.length - 1); i++) {
            level.append('/').append(segments[i]);
            if (index.isRepeating(level.toString()) && !starred.contains(level.toString())) {
                return true;
            }
        }
        return false;
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
        // A root group is refused by the list, not here: A12 asks a duplicate first, and an empty root, a filter,
        // a category or an index answers ahead of both (validate.laws.FirstFilledValueGateOrderLawsTest).
        return scope;
    }

    private List<FieldRef> firstFilledDescendants(GroupListRef scope) {
        return descendantFields(scope.group()).map(info -> operandField(info.path())).toList();
    }

    /**
     * The field at {@code path} typed as the operand A12 reads it as: a temporal field by its declared class,
     * since its declared format decides and its kind is never read (KERNEL-FINDINGS KF211, KF550). A list or a
     * group has no handle to prove that class the way a {@link DateRef} does, so the operand carries it itself
     * (DEV-API-GAPS DG129).
     */
    private FieldRef operandField(String path) {
        return field(path).asKind(fieldInfo(path).temporalClass());
    }

    /**
     * A star beside a semantic index is judged against the INDEX GROUP, the nearest group on the scope's own
     * chain that declares an index field ({@code CheckEntityUtils.getIndexGroup}): A12 refuses a star on its
     * level or above it, and admits one below, where the index binds the index group's level and the star
     * iterates beneath it ({@code validate.laws.SemanticIndexWildcardPlacementLawsTest}).
     *
     * <p>Uncoded, because a key, containment or iteration gate answers ahead of both star classes (KERNEL-FINDINGS
     * KF351) and whether a literal key is applicable to the index field is not decided here. With no index group
     * the index itself is refused, and that stays the kernel's, starred or not.
     */
    private void requireNoStarAtOrAboveIndexGroup(GroupListRef scope) {
        Optional<String> indexGroup = indexGroupOf(scope.path());
        for (GroupRef starred : scope.flattened()) {
            if (indexGroup.filter(group -> EntityPath.of(group).isInside(starred.path())).isPresent()) {
                throw new IllegalArgumentException("a semantic index may not sit beside a wildcard on or above its"
                        + " index group " + indexGroup.get() + ": " + scope.renderedPath());
            }
        }
    }

    /** The nearest group on {@code groupPath}'s own chain, itself included, that declares an index field. */
    private Optional<String> indexGroupOf(String groupPath) {
        for (String level = groupPath; !level.isEmpty(); level = level.substring(0, level.lastIndexOf('/'))) {
            if (index.declaresIndexField(level)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    // --- navigation (Level 1, study finding 9): streams of info handles; filter is the matcher --

    /** Every field, in model order, as rich {@link FieldInfo} descriptors. */
    public Stream<FieldInfo> fields() {
        return index.fieldPaths().map(this::fieldInfo);
    }

    /** The fields directly contained in {@code group}, in model order. */
    public Stream<FieldInfo> fields(GroupRef group) {
        return index.fieldPaths(group.path().value()).map(this::fieldInfo);
    }

    /**
     * Every field beneath {@code group} at any depth, in model order. It is the expansion A12 reads for a group
     * operand of a list aggregate, which it judges as a flat list of these fields (KERNEL-FINDINGS KF193).
     */
    public Stream<FieldInfo> descendantFields(GroupRef group) {
        return fields().filter(field -> EntityPath.of(field.path()).isStrictlyInside(group.path()));
    }

    /**
     * The list A12 types an entity list as: each group operand replaced, in its place, by the fields beneath it
     * at any depth, index field included, and every other operand kept ({@code validate.laws.EntityListGroupExpansionKindLawsTest}).
     */
    List<AggregateOperand> expansionOf(List<? extends AggregateOperand> operands) {
        var expanded = new ArrayList<AggregateOperand>();
        for (AggregateOperand operand : operands) {
            switch (operand) {
                case GroupListRef scope -> descendantFields(scope.group()).map(FieldInfo::ref).forEach(expanded::add);
                case IndexedGroupValue indexed ->
                        descendantFields(indexed.group().group()).map(FieldInfo::ref).forEach(expanded::add);
                case FieldOperand field -> expanded.add(field);
            }
        }
        return List.copyOf(expanded);
    }

    /** Every group (root groups included), in model order. */
    public Stream<GroupInfo> groups() {
        return index.groupPaths().map(this::groupInfo);
    }

    /** The declared message-locale codes (header {@code locales}), in document order — every rule and
     *  computation must carry a message for each (MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING). */
    public List<String> locales() {
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
    public Optional<FieldInfo> fieldAt(String path) {
        return index.isField(path) ? Optional.of(fieldInfo(path)) : Optional.empty();
    }

    /** The non-throwing group peek — the group peer of {@link #fieldAt}; relative navigation hangs off
     *  {@link GroupInfo} (so a combinator can be written against a root group and applied to any subtree). */
    public Optional<GroupInfo> groupAt(String path) {
        return index.isGroup(path) ? Optional.of(groupInfo(path)) : Optional.empty();
    }

    private FieldInfo fieldInfo(String path) {
        return new FieldInfo(this, path, index.nameOf(path), index.kindOf(path));
    }

    private GroupInfo groupInfo(String path) {
        return new GroupInfo(this, path, index.nameOf(path), index.isRepeating(path));
    }

    /** A field's element-level annotations, read back kernel-free through the {@link ModelIndex} (the slot the
     *  silent-ignore deserializer would otherwise hide). Backs {@link FieldInfo#annotations()}. */
    List<Annotation> annotationsOf(String fieldPath) {
        return map(index.annotationsOf(fieldPath));
    }

    /** A group's element-level annotations (kernel-free, via {@link ModelIndex}). Backs {@link GroupInfo#annotations()}. */
    List<Annotation> annotationsOfGroup(String groupPath) {
        return map(index.annotationsOfGroup(groupPath));
    }

    Optional<String> usageTypeOfGroup(String groupPath) {
        return index.usageTypeOfGroup(groupPath);
    }

    private static List<Annotation> map(List<io.github.mbackschat.a12.dm.core.Annotation> as) {
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
    // on the operands' declared component sets and date precision, and reads each group operand's expansion
    // ahead of every list gate (KERNEL-FINDINGS KF458), none of which the operands themselves carry. `Dsl`
    // constructs from resolved operands and stays model-free, which is what the inbound converters need —
    // reconstructing a kernel-accepted condition must never refuse. This is the authoring layer, so it is the
    // one that checks. (PROJECT-DESIGN: rulekit is model-bound at its core.)
    //
    // Operand-taking rather than path-taking, unlike most of this class: an index-selected cell is a legal
    // operand and has no path spelling.

    /** {@code MaxValue(…)} over DATE operands, checked against this model. catalog:MaxValue */
    public DateExpr latestOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireComparableTemporalList("MaxValue", this, first, rest);
        return Dsl.latestOf(first, rest);
    }

    /** {@code MinValue(…)} over DATE operands, checked against this model. catalog:MinValue */
    public DateExpr earliestOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireComparableTemporalList("MinValue", this, first, rest);
        return Dsl.earliestOf(first, rest);
    }

    /** {@code MaxValue(…)} over TIME operands, checked against this model. catalog:MaxValue */
    public TimeExpr latestTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireComparableTemporalList("MaxValue", this, first, rest);
        return Dsl.latestTimeOf(first, rest);
    }

    /** {@code MinValue(…)} over TIME operands, checked against this model. catalog:MinValue */
    public TimeExpr earliestTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireComparableTemporalList("MinValue", this, first, rest);
        return Dsl.earliestTimeOf(first, rest);
    }

    /** {@code MaxValue(…)} over DATETIME operands, checked against this model. catalog:MaxValue */
    public DateTimeExpr latestDateTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireComparableTemporalList("MaxValue", this, first, rest);
        return Dsl.latestDateTimeOf(first, rest);
    }

    /** {@code MinValue(…)} over DATETIME operands, checked against this model. catalog:MinValue */
    public DateTimeExpr earliestDateTimeOf(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireComparableTemporalList("MinValue", this, first, rest);
        return Dsl.earliestDateTimeOf(first, rest);
    }

    /** {@code NumberOfDifferentValues(…)}, checked against this model. catalog:NumberOfDifferentValues */
    public NumberExpr numberOfDifferentValues(AggregateOperand first, AggregateOperand... rest) {
        Dsl.requireDifferentValuesList(this, first, rest);
        return Dsl.numberOfDifferentValues(first, rest);
    }

    /**
     * {@code Sum(…)}, checked against this model. The {@link Dsl#sum} record withholds every class it would
     * cite on a list holding a group, because A12 reads each group's expansion first and answers an empty one
     * ahead of every list gate (KERNEL-FINDINGS KF458). This model reads that expansion, so it cites the
     * empty-group classes, and the list gates' own once every group holds a field. Behind a semantic index it
     * withholds as the record does: whether a key resolves is not read here. catalog:Sum
     */
    public NumberExpr sum(AggregateOperand first, AggregateOperand... rest) {
        List<AggregateOperand> operands = listOf(first, rest);
        Dsl.readOperandsThen("Sum", this, operands, () -> requireListOverExpansion(ListAggregate.AggOp.SUM, operands));
        return Dsl.sum(first, rest);
    }

    /** {@code MaxValue(…)} over numbers, checked against this model as {@link #sum} is. catalog:MaxValue */
    public NumberExpr maxValue(AggregateOperand first, AggregateOperand... rest) {
        List<AggregateOperand> operands = listOf(first, rest);
        Dsl.readOperandsThen("MaxValue", this, operands,
                () -> requireListOverExpansion(ListAggregate.AggOp.MAX_VALUE, operands));
        return Dsl.maxValue(first, rest);
    }

    /** {@code MinValue(…)} over numbers, checked against this model as {@link #sum} is. catalog:MinValue */
    public NumberExpr minValue(AggregateOperand first, AggregateOperand... rest) {
        List<AggregateOperand> operands = listOf(first, rest);
        Dsl.readOperandsThen("MinValue", this, operands,
                () -> requireListOverExpansion(ListAggregate.AggOp.MIN_VALUE, operands));
        return Dsl.minValue(first, rest);
    }

    /** {@code NumberOfFilledFields(…)}, checked against this model as {@link #sum} is. catalog:NumberOfFilledFields */
    public NumberExpr numberOfFilledFields(AggregateOperand first, AggregateOperand second, AggregateOperand... rest) {
        List<AggregateOperand> operands = listOf(first, second, rest);
        Dsl.readOperandsThen("NumberOfFilledFields", this, operands,
                () -> CountAggregate.requireList(CountAggregate.CountOp.FILLED_FIELDS, operands));
        return Dsl.numberOfFilledFields(first, second, rest);
    }

    /** {@code NumberOfFilledFields(group, …)}, checked against this model as {@link #sum} is. */
    public NumberExpr numberOfFilledFields(GroupRef first, GroupRef... rest) {
        List<AggregateOperand> scopes = CountAggregate.scopesOf(listOf(first, rest));
        Dsl.readOperandsThen("NumberOfFilledFields", this, scopes,
                () -> CountAggregate.requireList(CountAggregate.CountOp.FILLED_FIELDS, scopes));
        return Dsl.numberOfFilledFields(first, rest);
    }

    /** {@code NumberOfFilledGroups(g1, g2, …)}, checked against this model as {@link #sum} is. */
    public NumberExpr numberOfFilledGroups(GroupRef first, GroupRef second, GroupRef... rest) {
        List<AggregateOperand> scopes = CountAggregate.scopesOf(listOf(first, second, rest));
        Dsl.readOperandsThen("NumberOfFilledGroups", this, scopes,
                () -> CountAggregate.requireList(CountAggregate.CountOp.FILLED_GROUPS, scopes));
        return Dsl.numberOfFilledGroups(first, second, rest);
    }

    /**
     * {@code NumberOfFilledGroups(group*)}, checked against this model. A lone group that does not repeat is
     * {@code MVK_PARAMSIZE_INVALIDGN} when it holds a field and {@code MVK_GROUP_IS_EMPTY} when it does not,
     * which is why only a model-bound factory can cite either (KERNEL-FINDINGS KF458). catalog:NumberOfFilledGroups
     */
    public NumberExpr numberOfFilledGroups(GroupRef repeatableGroup) {
        List<AggregateOperand> scopes = CountAggregate.scopesOf(List.of(repeatableGroup));
        Dsl.readOperandsThen("NumberOfFilledGroups", this, scopes,
                () -> CountAggregate.requireList(CountAggregate.CountOp.FILLED_GROUPS, scopes));
        return Dsl.numberOfFilledGroups(repeatableGroup);
    }

    /**
     * {@code NumberOfFilledGroups(s1, s2, …)} over group scopes, checked against this model as {@link #sum} is:
     * the form for a group below a starred repeatable level ({@link Dsl#numberOfFilledGroups(GroupListRef,
     * GroupListRef...)}). catalog:NumberOfFilledGroups
     */
    public NumberExpr numberOfFilledGroups(GroupListRef first, GroupListRef... rest) {
        List<AggregateOperand> scopes = List.copyOf(listOf(first, rest));
        Dsl.readOperandsThen("NumberOfFilledGroups", this, scopes,
                () -> CountAggregate.requireList(CountAggregate.CountOp.FILLED_GROUPS, scopes));
        return Dsl.numberOfFilledGroups(first, rest);
    }

    /**
     * {@code NumberOfFilledGroups(/Root*)}, checked against this model. The star on a root that does not repeat
     * answers first, as the record asks it; an empty repeatable root is {@code MVK_ROOT_GROUP_IS_EMPTY}, which only
     * the model shows (KERNEL-FINDINGS KF458).
     */
    public NumberExpr numberOfFilledRootGroups(GroupRef rootGroup) {
        RootGroupCount.requireRepeatable(rootGroup);
        Dsl.readOperandsThen("NumberOfFilledGroups", this, CountAggregate.scopesOf(List.of(rootGroup)), () -> { });
        return Dsl.numberOfFilledRootGroups(rootGroup);
    }

    /** The shape gates over the operands as written, then the kind gate over their expansion, as A12 asks them. */
    private void requireListOverExpansion(ListAggregate.AggOp op, List<AggregateOperand> operands) {
        EntityListRoute.requireShape(op.operatorId(), operands);
        ListAggregate.requireKinds(op, expansionOf(operands));
    }

    @SafeVarargs
    private static <T> List<T> listOf(T first, T... rest) {
        var all = new ArrayList<T>();
        all.add(first);
        all.addAll(List.of(rest));
        return List.copyOf(all);
    }

    private static <T> List<T> listOf(T first, T second, T[] rest) {
        var all = new ArrayList<T>(List.of(first, second));
        all.addAll(List.of(rest));
        return List.copyOf(all);
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

    /** The two-component form, whose year is the model's Base Year; {@code MVK_MISSING_YEAR} stays the kernel's. */
    public DateConstruction dateOf(Calculation day, Calculation month) {
        DateConstruction construction = new DateConstruction(day, month, new DateConstruction.BaseYearImplied());
        requireSlotCompatibleNumberBounds(day, DAY_SLOT_MAX, "day");
        requireSlotCompatibleNumberBounds(month, MONTH_SLOT_MAX, "month");
        return construction;
    }

    /** The four-component form. Its century and decade slots carry no measured bound rule, so they delegate. */
    public DateConstruction dateOf(Calculation day, Calculation month, Calculation century, Calculation decade) {
        DateConstruction construction =
                new DateConstruction(day, month, new DateConstruction.SplitYear(century, decade));
        requireSlotCompatibleNumberBounds(day, DAY_SLOT_MAX, "day");
        requireSlotCompatibleNumberBounds(month, MONTH_SLOT_MAX, "month");
        return construction;
    }

    private static final BigDecimal DAY_SLOT_MAX = BigDecimal.valueOf(31);
    private static final BigDecimal MONTH_SLOT_MAX = BigDecimal.valueOf(12);
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
            BigDecimal slotMax, String slot) {
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
        Set<TemporalFormats.Component> start = endpointComponents(construction.start());
        Set<TemporalFormats.Component> end = endpointComponents(construction.end());
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
        Set<TemporalFormats.Component> a = rangeComponents(left);
        Set<TemporalFormats.Component> b = rangeComponents(right);
        if (a == null || b == null || a.equals(b)) {
            return;
        }
        throw Refusals.of(KernelCode.MVK_INVALID_COMPARE_TO_DATE_RANGE,
                "a date-range comparison needs both operands to declare the same "
                        + "date components; got " + a + " and " + b);
    }

    /** The operand's declared component set, or {@code null} when there is nothing to read. */
    private Set<TemporalFormats.Component> rangeComponents(DateRangeOperand operand) {
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

    private Set<TemporalFormats.Component> componentsOfField(String path) {
        String declared = temporalFormat(path);
        return declared == null ? null : TemporalFormats.componentsOf(declared);
    }

    private Set<TemporalFormats.Component> endpointComponents(Calculation endpoint) {
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

    /** {@code ValueAsDate(Rows/Field For key, FirstDay)}, checked like {@link #valueAsDateFirstDay(FieldRef)}. */
    public DateExpr valueAsDateFirstDay(SemanticIndex partiallyKnownDate) {
        requirePartiallyKnownDate(partiallyKnownDate.index().field());
        return Dsl.valueAsDateFirstDay(partiallyKnownDate);
    }

    /** {@code ValueAsDate(Rows/Field For key, LastDay)}, checked like {@link #valueAsDateLastDay(FieldRef)}. */
    public DateExpr valueAsDateLastDay(SemanticIndex partiallyKnownDate) {
        requirePartiallyKnownDate(partiallyKnownDate.index().field());
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
