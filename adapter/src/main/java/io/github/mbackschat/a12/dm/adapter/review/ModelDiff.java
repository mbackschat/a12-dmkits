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
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEdit;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEditScript;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionPolarity;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;

/**
 * MODEL-REVIEW spike — the structural model-diff engine (a library primitive, MODEL-REVIEW-SPEC §1/§5).
 * Parses BOTH model states into the model and compares <b>structure</b> (so JSON formatting noise never
 * reaches the reviewer), matching elements by id/path into added / removed / modified, each carrying the
 * per-aspect {@link Delta}s that changed and a {@link Risk} tier with its reason codes.
 *
 * <p>An element's state is an ordered <i>aspect map</i> of
 * format-independent values (kernel-canonical condition text + severity + code for a rule; kind + per-kind
 * config for a field; authored properties for a group), so a pure reformat is never a change. Risk follows the <b>loosening &gt; tightening</b>
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
public record ModelDiff(List<ElementChange> changes, List<Refactor> refactors,
        Map<String, ConditionEditScript> conditionEdits) {

    /** The element families currently projected into the structural diff. */
    public enum ElementKind { FIELD, GROUP, RULE, COMPUTATION }

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

    /** Risk tier — ordered so the highest reason wins ({@code HIGH} has the greatest ordinal). */
    public enum Risk { LOW, MEDIUM, HIGH }

    /** One aspect that changed: {@code before}/{@code after} are null on the side where the element is absent. */
    public record Delta(String aspect, String before, String after) {}

    /** One element-level change: its risk tier + reason codes, and the aspects that differ. */
    public record ElementChange(ElementKind element, String id, ChangeKind change, Risk risk,
            List<String> reasons, List<Delta> deltas) {}

    /** Diff {@code headJson} against {@code baseJson} (both must be kernel-loadable and <b>self-contained</b> —
     *  an includes-bearing model needs a resolver overload below, else binding throws). */
    public static ModelDiff between(String baseJson, String headJson) {
        return between(ModelDiffState.of(baseJson, BoundModel.ofJson(baseJson)),
                ModelDiffState.of(headJson, BoundModel.ofJson(headJson)));
    }

    /**
     * Diff two models whose {@code include}s/imports resolve through a <b>kernel-free</b>
     * {@link io.github.mbackschat.a12.dm.core.ModelResolver} (the mirror of {@link ModelReport}'s resolver
     * overload — a consumer diffs a multi-model model without importing {@code com.mgmtp.*}). {@code null} = none.
     */
    public static ModelDiff between(String baseJson, String headJson,
            io.github.mbackschat.a12.dm.core.ModelResolver resolver) {
        return between(ModelDiffState.of(baseJson,
                        resolver == null ? BoundModel.ofJson(baseJson) : BoundModel.ofJson(baseJson, resolver)),
                ModelDiffState.of(headJson,
                        resolver == null ? BoundModel.ofJson(headJson) : BoundModel.ofJson(headJson, resolver)));
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
                        baseState.referrers(), headState.referrers()), changes);
        Map<String, Map<String, String>> baseGroups = GroupDiffProjector.project(baseState);
        Map<String, Map<String, String>> headGroups = GroupDiffProjector.project(headState);
        match(ElementKind.GROUP, baseGroups, headGroups,
                (change, id, deltas) -> GroupDiffProjector.reasons(change, deltas), changes);
        Map<String, Map<String, String>> baseRuleAspects = RuleDiffProjector.project(baseState);
        Map<String, Map<String, String>> headRuleAspects = RuleDiffProjector.project(headState);
        match(ElementKind.RULE, baseRuleAspects, headRuleAspects,
                (change, id, deltas) -> ruleReasons(change, id, deltas, baseRules, headRules), changes);
        Map<String, Map<String, String>> baseComputations = ComputationDiffProjector.project(baseState);
        Map<String, Map<String, String>> headComputations = ComputationDiffProjector.project(headState);
        match(ElementKind.COMPUTATION, baseComputations, headComputations,
                (change, id, deltas) -> ComputationDiffProjector.reasons(change, deltas), changes);
        detectReorders(authoredOrder(baseState.authored()), authoredOrder(headState.authored()), changes);
        pairRenames(changes, baseRules, headRules);
        pairSimilar(changes, baseRules, headRules);
        return new ModelDiff(List.copyOf(changes), groupRefactors(changes, baseRules, headRules),
                locatedConditionEdits(changes, baseRules, headRules));
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
                    ? pathAfter(c) : c.id();
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
    private static String parentOf(String path) {
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

    // --- rename/move pairing (MR3-v1: the conservative exact-fingerprint matcher, §5) -------------

    /**
     * Re-labels a REMOVED@P + ADDED@P′ pair as {@code RENAMED}/{@code MOVED} when it is unambiguously the
     * SAME element relocated: identical content fingerprint (the element's aspect map), exactly one↔one of
     * that kind+fingerprint, and a locality match — same parent XOR same leaf name (what {@code field
     * rename}/{@code field move} do). Everything else stays remove+add (unsure ⇒ the safe direction). A
     * content edit breaks the identical-fingerprint test, so a rename+edit is left to the similarity upgrade.
     */
    private static void pairRenames(List<ElementChange> changes, KernelAdapter base, KernelAdapter head) {
        Map<FpKey, List<ElementChange>> removed = new LinkedHashMap<>();
        Map<FpKey, List<ElementChange>> added = new LinkedHashMap<>();
        for (ElementChange c : changes) {
            if (c.change() == ChangeKind.REMOVED) {
                removed.computeIfAbsent(fpKey(c), k -> new ArrayList<>()).add(c);
            } else if (c.change() == ChangeKind.ADDED) {
                added.computeIfAbsent(fpKey(c), k -> new ArrayList<>()).add(c);
            }
        }
        Map<ElementChange, ElementChange> replaceWith = new LinkedHashMap<>();
        List<ElementChange> drop = new ArrayList<>();
        for (Map.Entry<FpKey, List<ElementChange>> e : removed.entrySet()) {
            List<ElementChange> rs = e.getValue();
            List<ElementChange> as = added.getOrDefault(e.getKey(), List.of());
            if (rs.size() != 1 || as.size() != 1) {
                continue; // ambiguous (or unmatched) fingerprint → do not pair
            }
            ChangeKind relocation = relocationKind(rs.get(0).id(), as.get(0).id());
            if (relocation != null) {
                replaceWith.put(rs.get(0), relocated(rs.get(0), as.get(0), relocation, base, head));
                drop.add(as.get(0));
            }
        }
        applyPairing(changes, replaceWith, drop);
    }

    /** Replace each paired REMOVED with its relocated change (in place, base order) and drop the paired ADDEDs. */
    private static void applyPairing(List<ElementChange> changes,
            Map<ElementChange, ElementChange> replaceWith, List<ElementChange> drop) {
        for (int i = 0; i < changes.size(); i++) {
            ElementChange replacement = replaceWith.get(changes.get(i));
            if (replacement != null) {
                changes.set(i, replacement);
            }
        }
        changes.removeAll(drop);
    }

    /** Kind + content fingerprint; equality is content-based (the Map's), so identical elements bucket together. */
    private record FpKey(ElementKind kind, Map<String, String> fingerprint) {}

    /** The bucket key: an element's kind + its content fingerprint. */
    private static FpKey fpKey(ElementChange c) {
        return new FpKey(c.element(), fingerprint(c));
    }

    /** An element's content fingerprint (aspect → value), read off the one-sided deltas of a REMOVED/ADDED change. */
    private static Map<String, String> fingerprint(ElementChange c) {
        Map<String, String> fingerprint = new LinkedHashMap<>();
        for (Delta d : c.deltas()) {
            if (!d.aspect().equals("path")) { // a relocation's path delta is not content
                fingerprint.put(d.aspect(), c.change() == ChangeKind.REMOVED ? d.before() : d.after());
            }
        }
        return fingerprint;
    }

    /**
     * {@code RENAMED} when only the leaf name changed (same parent), {@code MOVED} when only the parent
     * changed (same leaf) — the two safe locality shapes; {@code null} (do not pair) when BOTH differ (too
     * weak a signal for v1). {@code P == P′} can't occur here (it would have matched as a common element).
     */
    private static ChangeKind relocationKind(String base, String head) {
        boolean sameParent = parentOf(base).equals(parentOf(head));
        boolean sameLeaf = leafOf(base).equals(leafOf(head));
        if (sameParent && !sameLeaf) {
            return ChangeKind.RENAMED;
        }
        if (!sameParent && sameLeaf) {
            return ChangeKind.MOVED;
        }
        return null;
    }

    /**
     * The relocated element as ONE change: a {@code path} before→after delta PLUS any content aspect deltas
     * (so a rename+edit never hides the edit), with reasons = the relocation reason + the per-aspect reason
     * for what else changed. For a v1 (identical-fingerprint) pair the content deltas are empty, so it reduces
     * to a lone {@code path} delta; a similarity pair (fingerprint differs) carries the edit too.
     */
    private static ElementChange relocated(ElementChange removed, ElementChange added, ChangeKind kind,
            KernelAdapter base, KernelAdapter head) {
        List<Delta> contentDeltas = aspectDeltas(fingerprint(removed), fingerprint(added));
        List<Delta> deltas = new ArrayList<>();
        deltas.add(new Delta("path", removed.id(), added.id()));
        deltas.addAll(contentDeltas);
        List<String> reasons = new ArrayList<>();
        reasons.add(kind == ChangeKind.RENAMED ? "ELEMENT_RENAMED" : "ELEMENT_MOVED");
        for (Delta d : contentDeltas) {
            reasons.add(relocationContentReason(removed.element(), removed.id(), added.id(), d, base, head));
        }
        List<String> distinctReasons = distinctReasons(reasons);
        return new ElementChange(removed.element(), removed.id(), kind, riskFor(distinctReasons),
                distinctReasons, List.copyOf(deltas));
    }

    /** The reason for one aspect that changed across a relocation — a condition is classified across the two names. */
    private static String relocationContentReason(ElementKind element, String oldId, String newId, Delta d,
            KernelAdapter base, KernelAdapter head) {
        if (element == ElementKind.RULE && d.aspect().equals("condition")) {
            return conditionReason(oldId, newId, base, head);
        }
        if (element == ElementKind.COMPUTATION) {
            return ComputationDiffProjector.modifiedReason(d);
        }
        if (element == ElementKind.GROUP) {
            return GroupDiffProjector.modifiedReason(d);
        }
        return element == ElementKind.FIELD ? FieldDiffProjector.modifiedReason(d) : modifiedReason(d);
    }

    /** The last path segment (leaf name). */
    private static String leafOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // --- similarity matcher (MR3: survives a rename AND edit; the fingerprint fast path handles the clean case) ---

    /** Minimum score to pair, and the margin the winner must beat the runner-up by — a clear winner, not a toss-up. */
    static final double PAIR_MIN = 0.7;
    static final double PAIR_MARGIN = 0.2;
    /** A pair needs a distinctive link beyond a generic type match: a similar name, or (rules) shared references. */
    private static final double NAME_FLOOR = 0.5;
    private static final double REF_FLOOR = 0.5;

    /**
     * The similarity upgrade: pairs a leftover REMOVED with a leftover ADDED only when they are each other's
     * <b>mutual clear best</b> — the winner on BOTH sides, score ≥ {@link #PAIR_MIN}, and ≥ {@link #PAIR_MARGIN}
     * above the runner-up on <em>each</em> side. Requiring the margin symmetrically means an ambiguous set
     * (two removeds competing for one added, or vice versa) never pairs: unsure ⇒ remove+add, NEVER a wrong
     * pair. This catches the rename+edit the exact-fingerprint fast path misses; the relocated change carries
     * BOTH the path move and the content deltas, so the edit is never hidden.
     */
    private static void pairSimilar(List<ElementChange> changes, KernelAdapter base, KernelAdapter head) {
        List<ElementChange> removed = changes.stream()
                .filter(c -> c.change() == ChangeKind.REMOVED && supportsSimilarityPairing(c.element())).toList();
        List<ElementChange> added = changes.stream()
                .filter(c -> c.change() == ChangeKind.ADDED && supportsSimilarityPairing(c.element())).toList();
        if (removed.isEmpty() || added.isEmpty()) {
            return;
        }
        double[][] score = scoreMatrix(removed, added, base, head);
        Map<ElementChange, ElementChange> replaceWith = new LinkedHashMap<>();
        List<ElementChange> consumed = new ArrayList<>();
        for (int i = 0; i < removed.size(); i++) {
            int j = bestIndex(score[i]);
            if (j < 0 || score[i][j] < PAIR_MIN || margin(score[i], j) < PAIR_MARGIN) {
                continue; // no clear best on the removed side
            }
            double[] column = column(score, j);
            if (bestIndex(column) != i || margin(column, i) < PAIR_MARGIN) {
                continue; // not the mutual clear best — the added side is ambiguous
            }
            ElementChange r = removed.get(i);
            ElementChange a = added.get(j);
            ChangeKind kind = parentOf(r.id()).equals(parentOf(a.id())) ? ChangeKind.RENAMED : ChangeKind.MOVED;
            replaceWith.put(r, relocated(r, a, kind, base, head));
            consumed.add(a);
        }
        applyPairing(changes, replaceWith, consumed);
    }

    /** Fuzzy relocation pairing is evidence-backed only for the original field/rule families. */
    private static boolean supportsSimilarityPairing(ElementKind element) {
        return element == ElementKind.FIELD || element == ElementKind.RULE;
    }

    /** {@code score[i][j]} = similarity of {@code removed[i]} to {@code added[j]} ({@code -1} across kinds). */
    private static double[][] scoreMatrix(List<ElementChange> removed, List<ElementChange> added,
            KernelAdapter base, KernelAdapter head) {
        List<Set<String>> refsAdded = added.stream()
                .map(a -> a.element() == ElementKind.RULE ? refsOf(head, a.id()) : Set.<String>of()).toList();
        double[][] score = new double[removed.size()][added.size()];
        for (int i = 0; i < removed.size(); i++) {
            ElementChange r = removed.get(i);
            Set<String> refsR = r.element() == ElementKind.RULE ? refsOf(base, r.id()) : Set.of();
            Map<String, String> fpR = fingerprint(r);
            for (int j = 0; j < added.size(); j++) {
                ElementChange a = added.get(j);
                score[i][j] = a.element() == r.element()
                        ? similarityScore(r.element(), fpR, fingerprint(a), refsR, refsAdded.get(j),
                                leafOf(r.id()), leafOf(a.id()))
                        : -1.0;
            }
        }
        return score;
    }

    /** The index of the highest score (smallest index on a tie); {@code -1} if empty. */
    private static int bestIndex(double[] scores) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < scores.length; k++) {
            if (scores[k] > bestScore) {
                bestScore = scores[k];
                best = k;
            }
        }
        return best;
    }

    /** How far the best score beats the runner-up ({@code +∞} when it is the only candidate). */
    private static double margin(double[] scores, int bestIdx) {
        double second = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < scores.length; k++) {
            if (k != bestIdx && scores[k] > second) {
                second = scores[k];
            }
        }
        return scores[bestIdx] - second;
    }

    private static double[] column(double[][] matrix, int j) {
        double[] col = new double[matrix.length];
        for (int i = 0; i < matrix.length; i++) {
            col[i] = matrix[i][j];
        }
        return col;
    }

    /**
     * Similarity of a removed element to an added candidate in [0,1], weighted toward CONTENT over name (a
     * rename must not hide the match). Rules: reference-set overlap (the robust "same rule, edited" signal) +
     * fingerprint overlap + name. Fields: fingerprint (kind/config) + name. Returns 0 unless a distinctive
     * link is present (a similar name, or shared references), so a generic same-type match alone never pairs.
     */
    static double similarityScore(ElementKind kind, Map<String, String> fpRemoved, Map<String, String> fpAdded,
            Set<String> refsRemoved, Set<String> refsAdded, String leafRemoved, String leafAdded) {
        double nameSim = nameSimilarity(leafRemoved, leafAdded);
        double fpSim = jaccard(fingerprintEntries(fpRemoved), fingerprintEntries(fpAdded));
        if (kind == ElementKind.RULE) {
            double refSim = jaccard(refsRemoved, refsAdded);
            if (nameSim < NAME_FLOOR && refSim < REF_FLOOR) {
                return 0.0; // no distinctive link — a generic rule match is not enough
            }
            return 0.5 * refSim + 0.3 * fpSim + 0.2 * nameSim;
        }
        if (nameSim < NAME_FLOOR) {
            return 0.0; // a field with a dissimilar name and only a generic type match is not a pair
        }
        return 0.8 * fpSim + 0.2 * nameSim;
    }

    /** Structured aspect/value entries avoid separator escaping and remain deterministic. */
    private static Set<Map.Entry<String, String>> fingerprintEntries(Map<String, String> fingerprint) {
        return new LinkedHashSet<>(fingerprint.entrySet());
    }

    private static <T> double jaccard(Set<T> a, Set<T> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<T> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<T> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /** Name closeness in [0,1] — {@code 1 - normalized Levenshtein}. */
    private static double nameSimilarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1.0 : 1.0 - (double) levenshtein(a, b) / max;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
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
                Set<String> b = refsOf(base, c.element(), c.id());
                Set<String> h = refsOf(head, c.element(), c.id());
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
            String to = pathAfter(c);
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

    /** A rule's referenced field paths (empty for an opaque/unconvertible condition — can't attribute it). */
    private static Set<String> refsOf(KernelAdapter adapter, String ruleName) {
        return adapter.read(ruleName) instanceof ReadResult.Converted cv
                ? ConditionReferences.of(cv.condition()).fieldPaths()
                : Set.of();
    }

    /** Referenced fields of either reader family; opaque readers stay unattributed. */
    private static Set<String> refsOf(KernelAdapter adapter, ElementKind element, String name) {
        return switch (element) {
            case RULE -> refsOf(adapter, name);
            case COMPUTATION -> ComputationDiffProjector.references(adapter.readComputation(name));
            case FIELD, GROUP -> Set.of();
        };
    }

    /** The head path carried by a relocation's {@code path} delta. */
    private static String pathAfter(ElementChange relocation) {
        return relocation.deltas().stream().filter(d -> d.aspect().equals("path"))
                .map(Delta::after).findFirst().orElse(null);
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
    private static List<String> distinctReasons(List<String> reasons) {
        return List.copyOf(new LinkedHashSet<>(reasons));
    }

    /** The change's risk tier — the max over its reason codes (loosening &gt; tightening, §5b). */
    private static Risk riskFor(List<String> reasons) {
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
    private static String conditionReason(String baseName, String headName, KernelAdapter base, KernelAdapter head) {
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

    private static String modifiedReason(Delta d) {
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
                    "TYPE_CHANGED", "FIELD_CONFIG_CHANGED" -> Risk.MEDIUM;
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
    private static List<Delta> aspectDeltas(Map<String, String> base, Map<String, String> head) {
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
