package io.github.mbackschat.a12.dm.adapter.review;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEdit;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEditScript;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionPolarity;

/**
 * MODEL-REVIEW spike — the structural model-diff engine (a library primitive, MODEL-REVIEW-SPEC §1/§5).
 * Parses BOTH model states into the model and compares <b>structure</b> (so JSON formatting noise never
 * reaches the reviewer), matching elements by id/path into added / removed / modified, each carrying the
 * per-aspect {@link Delta}s that changed and a {@link Risk} review-impact tier with its reason codes.
 *
 * <p>An element's state is an ordered <i>aspect map</i> of
 * format-independent values (kernel-canonical condition text + severity + code for a rule; kind + per-kind
 * config for a field; authored properties for a group; the model configuration), so a pure reformat is never a change. Review impact follows the <b>loosening &gt; tightening</b>
 * asymmetry (a removed/loosened rule silently accepts bad data) — a subset of the SPEC §5b taxonomy.
 * A changed rule condition is further refined from the two typed ASTs ({@link ConditionPolarity}): a
 * provable structural flip is {@code POLARITY_INVERTED} (HIGH), otherwise the honest {@code CONDITION_CHANGED}.
 * A pure element <b>reorder</b> within a parent's mixed authored family sequence is flagged {@code REORDERED}
 * ({@code POSITION_CHANGED}, LOW — display-order is semantic, §2b), minimal and conservative so an index
 * shift never invents a move. A
 * removed+added pair that is the same element relocated is re-labelled {@code RENAMED}/{@code MOVED} by a
 * two-layer matcher — an exact-fingerprint fast path, then a mutual-best similarity matcher that survives a
 * rename+edit (§5) — always conservative (unsure ⇒ remove+add). A reference-rewriting refactor is folded
 * into one {@link Refactor} (§5g), and a changed condition carries a located {@link ConditionEditScript}
 * (which clause/operand moved, §5e).
 */
public final class ModelDiff {

    private final List<ElementChange> changes;
    private final List<Refactor> refactors;
    private final Map<String, ConditionEditScript> conditionEdits;
    // The gate both sides passed through, taken from the adapters — null iff either side went ungated. NOT read
    // from Backends, which describes the environment rather than a gate (see ReviewGateReceiptTest).
    private final Verification consistencyGate;

    // Private on purpose: the diff doubles as the CLI's proof that both sides were gated, and a receipt anyone
    // can construct is not proof. As a public record its canonical constructor could not be narrowed.
    private ModelDiff(List<ElementChange> changes, List<Refactor> refactors,
            Map<String, ConditionEditScript> conditionEdits, Verification consistencyGate) {
        this.changes = List.copyOf(changes);
        this.refactors = List.copyOf(refactors);
        this.conditionEdits = Map.copyOf(conditionEdits);
        this.consistencyGate = consistencyGate;
    }

    public List<ElementChange> changes() {
        return changes;
    }

    public List<Refactor> refactors() {
        return refactors;
    }

    public Map<String, ConditionEditScript> conditionEdits() {
        return conditionEdits;
    }

    /**
     * The consistency gate <b>both</b> sides passed through, or empty if either did not — the receipt the CLI
     * publishes the envelope's {@code verification} from. Present only when the two sides agree, since a diff
     * arbitrated by two different modes has no single one to report.
     */
    public Optional<Verification> consistencyGate() {
        return Optional.ofNullable(consistencyGate);
    }

    /** The element families currently projected into the structural diff. */
    public enum ElementKind { FIELD, GROUP, RULE, COMPUTATION, TYPEDEF, INCLUDE, CONFIG }

    /** Whether a relocation kept its parent (a {@code RENAME}) or its leaf name (a {@code MOVE}). */
    public enum RefactorKind { RENAME, MOVE }

    /**
     * A reference-rewriting refactor folded into ONE semantic operation (MR4): the renamed/moved element
     * ({@code from}→{@code to}) plus the {@code referrers} whose reference-delta is exactly that repoint. A
     * <b>non-destructive overlay</b> — the raw {@code RENAMED}/{@code MOVED} + per-referrer {@code MODIFIED}
     * changes stay in {@link #changes()}, so a consumer can verify the fold from the change-set itself.
     */
    public record Refactor(RefactorKind kind, String from, String to, List<String> referrers) {}

    /** How an element changed between the two states. */
    public enum ChangeKind { ADDED, REMOVED, MODIFIED, REORDERED, RENAMED, MOVED }

    /** Review-impact tier — ordered so the highest reason wins ({@code HIGH} has the greatest ordinal). */
    public enum Risk { LOW, MEDIUM, HIGH }

    /** A typed delta value; the sealed variants let a headless consumer switch exhaustively without parsing text. */
    public sealed interface DeltaValue permits TextValue, BooleanValue, IntegerValue, DecimalValue, StringListValue {}

    public record TextValue(String value) implements DeltaValue {}

    public record BooleanValue(boolean value) implements DeltaValue {}

    public record IntegerValue(int value) implements DeltaValue {}

    public record DecimalValue(BigDecimal value) implements DeltaValue {}

    public record StringListValue(List<String> value) implements DeltaValue {
        public StringListValue {
            value = List.copyOf(value);
        }
    }

    /**
     * One aspect that changed. The stable {@code before}/{@code after} strings remain the portable rendering;
     * {@link #beforeValue()}/{@link #afterValue()} add a typed, sealed view for headless consumers.
     */
    public record Delta(String aspect, String before, String after) {
        public Optional<DeltaValue> beforeValue() {
            return DeltaValueParser.parse(aspect, before);
        }

        public Optional<DeltaValue> afterValue() {
            return DeltaValueParser.parse(aspect, after);
        }
    }

    /** One element-level change: its review-impact tier + reason codes, and the aspects that differ. */
    public record ElementChange(ElementKind element, String id, ChangeKind change, Risk risk,
            List<String> reasons, List<Delta> deltas) {}

    /** Diff {@code headJson} against {@code baseJson} (both must be kernel-loadable and <b>self-contained</b> —
     *  an includes-bearing model needs a resolver overload below, else binding throws). */
    public static ModelDiff between(String baseJson, String headJson) {
        return between(ModelDiffState.load(baseJson, null), ModelDiffState.load(headJson, null));
    }

    /**
     * Diff two models whose {@code include}s/imports resolve through a <b>kernel-free</b>
     * {@link io.github.mbackschat.a12.dm.core.ModelResolver} (the mirror of {@link ModelReport}'s resolver
     * overload — a consumer diffs a multi-model model without importing {@code com.mgmtp.*}). Use the
     * four-argument overload when the two states come from different dependency snapshots. {@code null} = none.
     */
    public static ModelDiff between(String baseJson, String headJson,
            ModelResolver resolver) {
        return between(baseJson, headJson, resolver, resolver);
    }

    /**
     * Diff two model states against their own dependency snapshots. This is the review-time form for separate
     * worktrees, revisions, or service snapshots where the same referenced id can resolve differently per side.
     */
    public static ModelDiff between(String baseJson, String headJson,
            ModelResolver baseResolver,
            ModelResolver headResolver) {
        return between(ModelDiffState.load(baseJson, baseResolver), ModelDiffState.load(headJson, headResolver));
    }

    /** The shared structural diff over two already-bound models — the entry points differ only in how each
     *  {@link BoundModel} was resolved. */
    private static ModelDiff between(ModelDiffState baseState, ModelDiffState headState) {
        KernelAdapter baseRules = baseState.adapter();
        KernelAdapter headRules = headState.adapter();
        List<ElementChange> changes = new ArrayList<>();
        Map<String, Map<String, String>> baseFields = FieldDiffProjector.project(baseState);
        Map<String, Map<String, String>> headFields = FieldDiffProjector.project(headState);
        match(ElementKind.FIELD, baseFields, headFields,
                (change, id, deltas) -> FieldDiffProjector.reasons(change, id, deltas,
                        baseState.referrers(), headState.referrers(), baseState.adapter().uniquenessCriteria()), changes);
        Map<String, Map<String, String>> baseGroups = GroupDiffProjector.project(baseState);
        Map<String, Map<String, String>> headGroups = GroupDiffProjector.project(headState);
        match(ElementKind.GROUP, baseGroups, headGroups,
                (change, id, deltas) -> GroupDiffProjector.reasons(change, id, deltas, baseState.referrers()),
                changes);
        Map<String, Map<String, String>> baseRuleAspects = RuleDiffProjector.project(baseState);
        Map<String, Map<String, String>> headRuleAspects = RuleDiffProjector.project(headState);
        match(ElementKind.RULE, baseRuleAspects, headRuleAspects,
                (change, id, deltas) -> ruleReasons(change, id, deltas, baseRules, headRules), changes);
        Map<String, Map<String, String>> baseComputations = ComputationDiffProjector.project(baseState);
        Map<String, Map<String, String>> headComputations = ComputationDiffProjector.project(headState);
        match(ElementKind.COMPUTATION, baseComputations, headComputations,
                (change, id, deltas) -> ComputationDiffProjector.reasons(change, deltas), changes);
        Map<String, Map<String, String>> baseTypeDefinitions = TypeDefinitionDiffProjector.project(baseState);
        Map<String, Map<String, String>> headTypeDefinitions = TypeDefinitionDiffProjector.project(headState);
        match(ElementKind.TYPEDEF, baseTypeDefinitions, headTypeDefinitions,
                (change, id, deltas) -> TypeDefinitionDiffProjector.reasons(change, deltas), changes);
        Map<String, Map<String, String>> baseIncludes = IncludeDiffProjector.project(baseState);
        Map<String, Map<String, String>> headIncludes = IncludeDiffProjector.project(headState);
        match(ElementKind.INCLUDE, baseIncludes, headIncludes,
                (change, id, deltas) -> IncludeDiffProjector.reasons(change), changes);
        Map<String, Map<String, String>> baseConfiguration = ConfigurationDiffProjector.project(baseState);
        Map<String, Map<String, String>> headConfiguration = ConfigurationDiffProjector.project(headState);
        match(ElementKind.CONFIG, baseConfiguration, headConfiguration,
                (change, id, deltas) -> ConfigurationDiffProjector.reasons(change, deltas), changes);
        detectReorders(authoredOrder(baseState.authored()), authoredOrder(headState.authored()), changes);
        RelocationMatcher.pair(changes, baseRules, headRules);
        return new ModelDiff(List.copyOf(changes), groupRefactors(changes, baseRules, headRules),
                locatedConditionEdits(changes, baseRules, headRules), sharedGate(baseRules, headRules));
    }

    /** The receipt the two sides agree on, or {@code null} when they don't — including when either went ungated. */
    private static Verification sharedGate(KernelAdapter base, KernelAdapter head) {
        Optional<Verification> baseGate = base.consistencyGate();
        Optional<Verification> headGate = head.consistencyGate();
        return baseGate.isPresent() && baseGate.equals(headGate) ? baseGate.get() : null;
    }

    /**
     * The located condition edit-script (MR1) per rule change that touched its condition, keyed by change id —
     * a precise refinement over the coarse {@code riskReasons} ({@link ConditionEditScript}). The head
     * condition is read under the rule's head name (its new name for a {@code RENAMED}/{@code MOVED} rule).
     */
    private static Map<String, ConditionEditScript> locatedConditionEdits(List<ElementChange> changes,
            KernelAdapter base, KernelAdapter head) {
        Map<String, ConditionEditScript> out = new LinkedHashMap<>();
        for (ElementChange c : changes) {
            // only a rule present on BOTH sides has a base→head condition to locate (an ADDED/REMOVED rule's
            // condition is one-sided — there is nothing to diff, and reading the absent side would fail)
            boolean bothSides = c.change() == ChangeKind.MODIFIED || c.change() == ChangeKind.RENAMED
                    || c.change() == ChangeKind.MOVED;
            if (c.element() != ElementKind.RULE || !bothSides
                    || c.deltas().stream().noneMatch(d -> d.aspect().equals("condition"))) {
                continue;
            }
            String headName = c.change() == ChangeKind.RENAMED || c.change() == ChangeKind.MOVED
                    ? RelocationMatcher.pathAfter(c) : c.id();
            if (base.read(c.id()) instanceof ReadResult.Converted b
                    && head.read(headName) instanceof ReadResult.Converted h) {
                ConditionEditScript script = ConditionEditScript.diff(b.condition(), h.condition());
                if (!script.edits().isEmpty()) {
                    out.put(c.id(), script);
                }
            }
        }
        return out;
    }

    /** Base-order REMOVED/MODIFIED first, then head-only ADDED — deterministic for a stable input order. */
    private static void match(ElementKind kind, Map<String, Map<String, String>> base,
            Map<String, Map<String, String>> head, ReasonClassifier classifier, List<ElementChange> out) {
        for (Map.Entry<String, Map<String, String>> e : base.entrySet()) {
            Map<String, String> headAspects = head.get(e.getKey());
            if (headAspects == null) {
                out.add(classified(kind, e.getKey(), ChangeKind.REMOVED, allDeltas(e.getValue(), false), classifier));
            } else {
                List<Delta> deltas = aspectDeltas(e.getValue(), headAspects);
                if (!deltas.isEmpty()) {
                    out.add(classified(kind, e.getKey(), ChangeKind.MODIFIED, deltas, classifier));
                }
            }
        }
        for (Map.Entry<String, Map<String, String>> e : head.entrySet()) {
            if (!base.containsKey(e.getKey())) {
                out.add(classified(kind, e.getKey(), ChangeKind.ADDED, allDeltas(e.getValue(), true), classifier));
            }
        }
    }

    // --- reorder detection (MR2: display-order is semantic, §2b/§5a `element position`) -----------

    /**
     * Flags the common siblings whose <b>relative</b> order changed within a parent as {@code REORDERED}
     * ({@code POSITION_CHANGED}, LOW) carrying a {@code position} before→after delta. Conservative and
     * minimal: only the smallest set that must move (the complement of the longest common subsequence per
     * parent) is flagged, so an add/remove that merely shifts absolute indices never invents a reorder. An
     * element that also changed content folds the position into its existing change (never double-listed).
     */
    private static void detectReorders(List<ElementPosition> baseOrder, List<ElementPosition> headOrder,
            List<ElementChange> out) {
        Map<String, List<ElementPosition>> baseByParent = commonByParent(baseOrder, headOrder);
        Map<String, List<ElementPosition>> headByParent = commonByParent(headOrder, baseOrder);
        for (Map.Entry<String, List<ElementPosition>> e : baseByParent.entrySet()) {
            List<ElementPosition> baseSeq = e.getValue();
            List<ElementPosition> headSeq = headByParent.get(e.getKey());
            if (baseSeq.size() < 2 || baseSeq.equals(headSeq)) {
                continue; // nothing common to reorder, or the order is unchanged
            }
            Set<ElementPosition> anchored = longestCommonSubsequence(baseSeq, headSeq);
            Map<ElementPosition, String> baseOrdinals = ordinals(baseSeq);
            Map<ElementPosition, String> headOrdinals = ordinals(headSeq);
            for (ElementPosition element : baseSeq) {
                if (!anchored.contains(element)) {
                    recordReorder(element.kind(), element.id(), baseOrdinals.get(element),
                            headOrdinals.get(element), out);
                }
            }
        }
    }

    private record ElementPosition(ElementKind kind, String id) {}

    /** Every authored element family in one document-order sequence. */
    private static List<ElementPosition> authoredOrder(ModelDocument.AuthoredState state) {
        return state.elements().stream()
                .map(element -> new ElementPosition(switch (element.type()) {
                    case FIELD -> ElementKind.FIELD;
                    case GROUP -> ElementKind.GROUP;
                    case RULE -> ElementKind.RULE;
                    case COMPUTATION -> ElementKind.COMPUTATION;
                }, element.path()))
                .toList();
    }

    /** The elements present in BOTH sides, grouped by parent scope, in the primary side's order. */
    private static Map<String, List<ElementPosition>> commonByParent(
            List<ElementPosition> primary, List<ElementPosition> other) {
        Set<ElementPosition> otherElements = Set.copyOf(other);
        Map<String, List<ElementPosition>> byParent = new LinkedHashMap<>();
        for (ElementPosition element : primary) {
            if (otherElements.contains(element)) {
                byParent.computeIfAbsent(parentOf(element.id()), key -> new ArrayList<>()).add(element);
            }
        }
        return byParent;
    }

    /** The containing scope of a path/full-name — everything before the last {@code /} ({@code ""} at root). */
    static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    /** Each common sibling's 1-based position, computed once for the parent. */
    private static Map<ElementPosition, String> ordinals(List<ElementPosition> siblings) {
        Map<ElementPosition, String> positions = new LinkedHashMap<>();
        for (int i = 0; i < siblings.size(); i++) {
            positions.put(siblings.get(i), Integer.toString(i + 1));
        }
        return positions;
    }

    /**
     * The set of ids kept in place by a longest common subsequence of the two sibling orders — the anchors.
     * Both sequences are permutations of the same set here, so the complement is the minimal move set.
     * Map the head order to integer positions, then find the longest increasing subsequence in the base order:
     * equivalent for unique elements, but O(n log n) time and O(n) memory instead of a quadratic DP table.
     * Replacing a same-length tail keeps the result deterministic and preserves the established swap choice.
     */
    private static <T> Set<T> longestCommonSubsequence(List<T> a, List<T> b) {
        Map<T, Integer> headPositions = new LinkedHashMap<>();
        for (int i = 0; i < b.size(); i++) {
            headPositions.put(b.get(i), i);
        }

        int[] tails = new int[a.size()];
        int[] previous = new int[a.size()];
        Arrays.fill(previous, -1);
        int length = 0;
        for (int i = 0; i < a.size(); i++) {
            int headPosition = headPositions.get(a.get(i));
            int low = 0;
            int high = length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (headPositions.get(a.get(tails[middle])) < headPosition) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            previous[i] = low == 0 ? -1 : tails[low - 1];
            tails[low] = i;
            length = Math.max(length, low + 1);
        }

        Set<T> anchors = new LinkedHashSet<>();
        for (int i = tails[length - 1]; i >= 0; i = previous[i]) {
            anchors.add(a.get(i));
        }
        return anchors;
    }

    /** Fold the position into an existing change for {@code id}, else add a standalone {@code REORDERED}. */
    private static void recordReorder(ElementKind kind, String id, String before, String after,
            List<ElementChange> out) {
        Delta position = new Delta("position", before, after);
        for (int i = 0; i < out.size(); i++) {
            ElementChange c = out.get(i);
            if (c.element() == kind && c.id().equals(id)) {
                List<String> reasons = new ArrayList<>(c.reasons());
                reasons.add("POSITION_CHANGED");
                List<Delta> deltas = new ArrayList<>(c.deltas());
                deltas.add(position);
                out.set(i, new ElementChange(kind, id, c.change(), riskFor(reasons),
                        List.copyOf(reasons), List.copyOf(deltas)));
                return;
            }
        }
        out.add(new ElementChange(kind, id, ChangeKind.REORDERED, Risk.LOW,
                List.of("POSITION_CHANGED"), List.of(position)));
    }

    // --- relational refactor grouping (MR4: fold a rename/move + the referrers it rewrote) --------

    /**
     * Folds each {@code RENAMED}/{@code MOVED} that rewrote referrers into ONE {@link Refactor}: its
     * referrers are the {@code MODIFIED} rules/computations whose reference-delta is <b>exactly</b> that {@code from}→{@code to}
     * repoint (dropped {@code from}, gained {@code to}) — so a coincidental reference change (no matching
     * relocation) is never fabricated into a refactor. A relocation with no referrer yields no group (nothing
     * relational to fold). This is a read-only overlay: {@code changes} is left intact.
     */
    private static List<Refactor> groupRefactors(List<ElementChange> changes, KernelAdapter base, KernelAdapter head) {
        Map<String, Set<String>> dropped = new LinkedHashMap<>();
        Map<String, Set<String>> gained = new LinkedHashMap<>();
        for (ElementChange c : changes) {
            if (c.change() == ChangeKind.MODIFIED
                    && (c.element() == ElementKind.RULE || c.element() == ElementKind.COMPUTATION)) {
                Set<String> b = RelocationMatcher.refsOf(base, c.element(), c.id());
                Set<String> h = RelocationMatcher.refsOf(head, c.element(), c.id());
                dropped.put(c.id(), difference(b, h));
                gained.put(c.id(), difference(h, b));
            }
        }
        List<Refactor> refactors = new ArrayList<>();
        for (ElementChange c : changes) {
            RefactorKind kind = switch (c.change()) {
                case RENAMED -> RefactorKind.RENAME;
                case MOVED -> RefactorKind.MOVE;
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            String from = c.id();
            String to = RelocationMatcher.pathAfter(c);
            List<String> referrers = new ArrayList<>();
            for (String rule : dropped.keySet()) {
                if (dropped.get(rule).contains(from) && gained.get(rule).contains(to)) {
                    referrers.add(rule);
                }
            }
            if (!referrers.isEmpty()) {
                refactors.add(new Refactor(kind, from, to, List.copyOf(referrers)));
            }
        }
        return List.copyOf(refactors);
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> d = new LinkedHashSet<>(a);
        d.removeAll(b);
        return d;
    }

    // --- risk classification (the §5b taste: loosening > tightening) ------------------------------

    /** Maps a change to its reason codes — pluggable so the rule path can consult the two ASTs. */
    @FunctionalInterface
    private interface ReasonClassifier {
        List<String> reasons(ChangeKind change, String id, List<Delta> deltas);
    }

    private static ElementChange classified(ElementKind kind, String id, ChangeKind change,
            List<Delta> deltas, ReasonClassifier classifier) {
        List<String> reasons = distinctReasons(classifier.reasons(change, id, deltas));
        return new ElementChange(kind, id, change, riskFor(reasons), reasons, deltas);
    }

    /** Reason codes describe classifications; multiple changed aspects must not repeat the same code. */
    static List<String> distinctReasons(List<String> reasons) {
        return List.copyOf(new LinkedHashSet<>(reasons));
    }

    /** The change's review-impact tier — the max over its reason codes (loosening &gt; tightening, §5b). */
    static Risk riskFor(List<String> reasons) {
        return reasons.stream().map(ModelDiff::riskOf).max(Comparator.naturalOrder()).orElse(Risk.LOW);
    }

    /**
     * Rule reasons: add/remove + per-aspect, but a changed {@code condition} is refined from the two
     * ASTs — {@code POLARITY_INVERTED} (HIGH) on a provable structural flip, else {@code CONDITION_CHANGED}.
     */
    private static List<String> ruleReasons(ChangeKind change, String name, List<Delta> deltas,
            KernelAdapter base, KernelAdapter head) {
        List<String> r = new ArrayList<>();
        switch (change) {
            case ADDED -> r.add("RULE_ADDED");
            case REMOVED -> r.add("RULE_REMOVED");
            case MODIFIED -> {
                for (Delta d : deltas) {
                    r.add(d.aspect().equals("condition") ? conditionReason(name, base, head) : modifiedReason(d));
                }
            }
        }
        return r;
    }

    /**
     * Refines a changed condition from the two typed ASTs: {@code POLARITY_INVERTED} (HIGH) when the head
     * is the clean logical complement of the base ({@link ConditionPolarity}); otherwise the edit kind
     * ({@link ConditionEdit}, all MEDIUM) — restructured / reference / operator / threshold. An opaque or
     * unconvertible condition stays the generic {@code CONDITION_CHANGED} (can't analyze). The structural
     * detector proves a flip, never sameness (that is the behavioral {@code --deep} path, SPEC §5c).
     */
    private static String conditionReason(String ruleName, KernelAdapter base, KernelAdapter head) {
        return conditionReason(ruleName, ruleName, base, head);
    }

    /**
     * As above, but reads the base condition from {@code baseName} and the head condition from {@code headName}
     * — so a <b>renamed</b> rule's condition change is still classified across the two names (an inversion or
     * threshold move under a rename is never hidden).
     */
    static String conditionReason(String baseName, String headName, KernelAdapter base, KernelAdapter head) {
        if (base.read(baseName) instanceof ReadResult.Converted b
                && head.read(headName) instanceof ReadResult.Converted h) {
            if (ConditionPolarity.isInversionOf(h.condition(), b.condition())) {
                return "POLARITY_INVERTED";
            }
            return switch (ConditionEdit.classify(b.condition(), h.condition())) {
                case STRUCTURE -> "CONDITION_RESTRUCTURED";
                case REFERENCES -> "REFERENCE_CHANGED";
                case OPERATOR -> "OPERATOR_CHANGED";
                case OPERAND -> "THRESHOLD_CHANGED";
            };
        }
        return "CONDITION_CHANGED";
    }

    static String modifiedReason(Delta d) {
        return switch (d.aspect()) {
            case "severity" -> downgraded(d.before(), d.after()) ? "SEVERITY_DOWNGRADED" : "SEVERITY_CHANGED";
            case "code" -> "CODE_CHANGED";
            case "errorEntity" -> "REFERENCE_CHANGED";
            default -> d.aspect().startsWith("message.") ? "MESSAGE_CHANGED" : "METADATA_CHANGED";
        };
    }

    private static Risk riskOf(String reason) {
        return switch (reason) {
            case "RULE_REMOVED", "KIND_CHANGED", "SEVERITY_DOWNGRADED", "CONSTRAINT_LOOSENED",
                    "POLARITY_INVERTED", "REFERENCED_ELEMENT_REMOVED", "RETYPE_AFFECTS_READERS" -> Risk.HIGH;
            case "RULE_ADDED", "CONDITION_CHANGED", "CONDITION_RESTRUCTURED", "REFERENCE_CHANGED",
                    "OPERATOR_CHANGED", "THRESHOLD_CHANGED", "CONSTRAINT_TIGHTENED", "STRUCTURE_ADDED",
                    "STRUCTURE_REMOVED", "PATTERN_CHANGED", "SEVERITY_CHANGED", "SCALE_CHANGED",
                    "ELEMENT_MOVED", "COMPUTATION_CHANGED", "GROUP_CHANGED", "REPEATABILITY_CHANGED",
                    "TYPE_CHANGED", "FIELD_CONFIG_CHANGED", "TYPEDEF_ADDED", "TYPEDEF_REMOVED",
                    "INCLUDE_CHANGED", "CONFIG_CHANGED", "LOCALE_REMOVED" -> Risk.MEDIUM;
            // POSITION_CHANGED (a reorder) / ELEMENT_RENAMED are content-preserving, not a loosening → LOW
            default -> Risk.LOW;
        };
    }

    /** A numeric bound is looser when it admits MORE values. {@code minSide}: lowering a min/minLength loosens. */
    static String constraintReason(boolean minSide, Delta d) {
        return loosened(minSide, d) ? "CONSTRAINT_LOOSENED" : "CONSTRAINT_TIGHTENED";
    }

    private static boolean loosened(boolean minSide, Delta d) {
        if (d.after() == null) {
            return true; // the constraint was removed → the field accepts more
        }
        if (d.before() == null) {
            return false; // a constraint was added → the field accepts less
        }
        try {
            int cmp = new BigDecimal(d.after()).compareTo(new BigDecimal(d.before()));
            return minSide ? cmp < 0 : cmp > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String patternChangeReason(Delta d) {
        if (d.after() == null) {
            return "CONSTRAINT_LOOSENED"; // pattern removed → accepts more
        }
        if (d.before() == null) {
            return "CONSTRAINT_TIGHTENED"; // pattern added → accepts less
        }
        return "PATTERN_CHANGED"; // changed shape → direction unknown
    }

    private static boolean downgraded(String before, String after) {
        return severityRank(after) < severityRank(before);
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity) {
            case "ERROR" -> 3;
            case "WARNING" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    // --- aspect extraction ------------------------------------------------------------------------

    /** The aspects that differ between two aspect maps (deterministic order: base keys, then head-only keys). */
    static List<Delta> aspectDeltas(Map<String, String> base, Map<String, String> head) {
        Set<String> aspects = new LinkedHashSet<>(base.keySet());
        aspects.addAll(head.keySet());
        List<Delta> deltas = new ArrayList<>();
        for (String aspect : aspects) {
            String b = base.get(aspect);
            String h = head.get(aspect);
            if (!Objects.equals(b, h)) {
                deltas.add(new Delta(aspect, b, h));
            }
        }
        return deltas;
    }

    /** Every aspect as a one-sided delta — for an ADDED ({@code asAfter}) or REMOVED element. */
    private static List<Delta> allDeltas(Map<String, String> aspects, boolean asAfter) {
        List<Delta> deltas = new ArrayList<>();
        aspects.forEach((aspect, value) ->
                deltas.add(asAfter ? new Delta(aspect, null, value) : new Delta(aspect, value, null)));
        return deltas;
    }

}
