package io.github.mbackschat.a12.dm.adapter.review;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionEdit;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionPolarity;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;

/**
 * MODEL-REVIEW spike — the structural model-diff engine (a library primitive, MODEL-REVIEW-SPEC §1/§5).
 * Parses BOTH model states into the model and compares <b>structure</b> (so JSON formatting noise never
 * reaches the reviewer), matching elements by id/path into added / removed / modified, each carrying the
 * per-aspect {@link Delta}s that changed and a {@link Risk} tier with its reason codes.
 *
 * <p>This slice covers <b>fields and rules</b>. An element's state is an ordered <i>aspect map</i> of
 * format-independent values (kernel-canonical condition text + severity + code for a rule; kind + per-kind
 * config for a field), so a pure reformat is never a change. Risk follows the <b>loosening &gt; tightening</b>
 * asymmetry (a removed/loosened rule silently accepts bad data) — a subset of the SPEC §5b taxonomy.
 * A changed rule condition is further refined from the two typed ASTs ({@link ConditionPolarity}): a
 * provable structural flip is {@code POLARITY_INVERTED} (HIGH), otherwise the honest {@code CONDITION_CHANGED}.
 * A pure element <b>reorder</b> within a parent is flagged {@code REORDERED} ({@code POSITION_CHANGED}, LOW —
 * display-order is semantic, §2b), minimal and conservative so an index shift never invents a move. The
 * fuller AST edit-script (which operand/clause moved), rename/move, and computations/groups are later
 * slices (MODEL-REVIEW-GAPS MR1/MR3/MR4).
 */
public record ModelDiff(List<ElementChange> changes) {

    /** The element families this slice diffs (computation/group come later). */
    public enum ElementKind { FIELD, RULE }

    /** How an element changed between the two states. */
    public enum ChangeKind { ADDED, REMOVED, MODIFIED, REORDERED }

    /** Risk tier — ordered so the highest reason wins ({@code HIGH} has the greatest ordinal). */
    public enum Risk { LOW, MEDIUM, HIGH }

    /** One aspect that changed: {@code before}/{@code after} are null on the side where the element is absent. */
    public record Delta(String aspect, String before, String after) {}

    /** One element-level change: its risk tier + reason codes, and the aspects that differ. */
    public record ElementChange(ElementKind element, String id, ChangeKind change, Risk risk,
            List<String> reasons, List<Delta> deltas) {}

    /** Diff {@code headJson} against {@code baseJson} (both must be kernel-loadable). */
    public static ModelDiff between(String baseJson, String headJson) {
        BoundModel base = BoundModel.ofJson(baseJson);
        BoundModel head = BoundModel.ofJson(headJson);
        KernelAdapter baseRules = KernelAdapter.of(base);
        KernelAdapter headRules = KernelAdapter.of(head);
        List<ElementChange> changes = new ArrayList<>();
        Map<String, Map<String, String>> baseFields = fieldAspects(base);
        Map<String, Map<String, String>> headFields = fieldAspects(head);
        match(ElementKind.FIELD, baseFields, headFields, ModelDiff::fieldReasons, changes);
        detectReorders(ElementKind.FIELD, baseFields.keySet(), headFields.keySet(), changes);
        Map<String, Map<String, String>> baseRuleAspects = ruleAspects(baseRules);
        Map<String, Map<String, String>> headRuleAspects = ruleAspects(headRules);
        match(ElementKind.RULE, baseRuleAspects, headRuleAspects,
                (change, id, deltas) -> ruleReasons(change, id, deltas, baseRules, headRules), changes);
        detectReorders(ElementKind.RULE, baseRuleAspects.keySet(), headRuleAspects.keySet(), changes);
        return new ModelDiff(List.copyOf(changes));
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
    private static void detectReorders(ElementKind kind, Set<String> baseKeys, Set<String> headKeys,
            List<ElementChange> out) {
        Map<String, List<String>> baseByParent = commonByParent(baseKeys, headKeys);
        Map<String, List<String>> headByParent = commonByParent(headKeys, baseKeys);
        for (Map.Entry<String, List<String>> e : baseByParent.entrySet()) {
            List<String> baseSeq = e.getValue();
            List<String> headSeq = headByParent.get(e.getKey());
            if (baseSeq.size() < 2 || baseSeq.equals(headSeq)) {
                continue; // nothing common to reorder, or the order is unchanged
            }
            Set<String> anchored = longestCommonSubsequence(baseSeq, headSeq);
            for (String id : baseSeq) {
                if (!anchored.contains(id)) {
                    recordReorder(kind, id, ordinal(baseSeq, id), ordinal(headSeq, id), out);
                }
            }
        }
    }

    /** The ids present in BOTH sides, grouped by parent scope, in {@code primary}'s order (deterministic). */
    private static Map<String, List<String>> commonByParent(Set<String> primary, Set<String> other) {
        Map<String, List<String>> byParent = new LinkedHashMap<>();
        for (String id : primary) {
            if (other.contains(id)) {
                byParent.computeIfAbsent(parentOf(id), k -> new ArrayList<>()).add(id);
            }
        }
        return byParent;
    }

    /** The containing scope of a path/full-name — everything before the last {@code /} ({@code ""} at root). */
    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    /** The element's 1-based position among its common siblings, as a string (for the {@code position} delta). */
    private static String ordinal(List<String> siblings, String id) {
        return Integer.toString(siblings.indexOf(id) + 1);
    }

    /**
     * The set of ids kept in place by a longest common subsequence of the two sibling orders — the anchors.
     * Both sequences are permutations of the same set here, so the complement is the minimal move set.
     * Deterministic backtrack (prefer advancing {@code a} on ties), so the flagged element is stable.
     */
    private static Set<String> longestCommonSubsequence(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j))
                        ? 1 + dp[i + 1][j + 1]
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        Set<String> lcs = new LinkedHashSet<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                lcs.add(a.get(i));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return lcs;
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

    // --- risk classification (the §5b taste: loosening > tightening) ------------------------------

    /** Maps a change to its reason codes — pluggable so the rule path can consult the two ASTs. */
    @FunctionalInterface
    private interface ReasonClassifier {
        List<String> reasons(ChangeKind change, String id, List<Delta> deltas);
    }

    private static ElementChange classified(ElementKind kind, String id, ChangeKind change,
            List<Delta> deltas, ReasonClassifier classifier) {
        List<String> reasons = classifier.reasons(change, id, deltas);
        return new ElementChange(kind, id, change, riskFor(reasons), List.copyOf(reasons), deltas);
    }

    /** The change's risk tier — the max over its reason codes (loosening &gt; tightening, §5b). */
    private static Risk riskFor(List<String> reasons) {
        return reasons.stream().map(ModelDiff::riskOf).max(Comparator.naturalOrder()).orElse(Risk.LOW);
    }

    /** Field reasons: structure add/remove + the per-aspect config reasons. */
    private static List<String> fieldReasons(ChangeKind change, String id, List<Delta> deltas) {
        List<String> r = new ArrayList<>();
        switch (change) {
            case ADDED -> r.add("STRUCTURE_ADDED");
            case REMOVED -> r.add("STRUCTURE_REMOVED");
            case MODIFIED -> deltas.forEach(d -> r.add(modifiedReason(d)));
        }
        return r;
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
        if (base.read(ruleName) instanceof ReadResult.Converted b
                && head.read(ruleName) instanceof ReadResult.Converted h) {
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
            case "kind" -> "KIND_CHANGED";
            case "severity" -> downgraded(d.before(), d.after()) ? "SEVERITY_DOWNGRADED" : "SEVERITY_CHANGED";
            case "min", "minLength" -> loosened(true, d) ? "CONSTRAINT_LOOSENED" : "CONSTRAINT_TIGHTENED";
            case "max", "maxLength" -> loosened(false, d) ? "CONSTRAINT_LOOSENED" : "CONSTRAINT_TIGHTENED";
            case "pattern" -> patternReason(d);
            case "scale" -> "SCALE_CHANGED";
            case "code" -> "CODE_CHANGED";
            default -> "METADATA_CHANGED";
        };
    }

    private static Risk riskOf(String reason) {
        return switch (reason) {
            case "RULE_REMOVED", "KIND_CHANGED", "SEVERITY_DOWNGRADED", "CONSTRAINT_LOOSENED",
                    "POLARITY_INVERTED" -> Risk.HIGH;
            case "RULE_ADDED", "CONDITION_CHANGED", "CONDITION_RESTRUCTURED", "REFERENCE_CHANGED",
                    "OPERATOR_CHANGED", "THRESHOLD_CHANGED", "CONSTRAINT_TIGHTENED", "STRUCTURE_ADDED",
                    "STRUCTURE_REMOVED", "PATTERN_CHANGED", "SEVERITY_CHANGED", "SCALE_CHANGED" -> Risk.MEDIUM;
            // POSITION_CHANGED (a reorder) is a display-order change, not a validation loosening → LOW
            default -> Risk.LOW;
        };
    }

    /** A numeric bound is looser when it admits MORE values. {@code minSide}: lowering a min/minLength loosens. */
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

    private static String patternReason(Delta d) {
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

    private static Map<String, Map<String, String>> fieldAspects(BoundModel m) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        m.fields().forEach(f -> out.put(f.path(), fieldAspectMap(f)));
        return out;
    }

    /** Kind + per-kind config — all format-independent, so a reformat is never seen as a change. */
    private static Map<String, String> fieldAspectMap(FieldInfo f) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("kind", f.kind().name());
        if (f.kind() == ValueKind.NUMBER) {
            a.put("scale", Integer.toString(f.scale()));
        }
        f.numberMin().ifPresent(v -> a.put("min", v.toString()));
        f.numberMax().ifPresent(v -> a.put("max", v.toString()));
        f.minLength().ifPresent(v -> a.put("minLength", Integer.toString(v)));
        f.maxLength().ifPresent(v -> a.put("maxLength", Integer.toString(v)));
        f.pattern().ifPresent(v -> a.put("pattern", v));
        return a;
    }

    private static Map<String, Map<String, String>> ruleAspects(KernelAdapter a) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String name : a.ruleFullNames()) {
            out.put(name, ruleAspectMap(a, name));
        }
        return out;
    }

    /** Kernel-canonical condition text (format-independent) + severity + error code. */
    private static Map<String, String> ruleAspectMap(KernelAdapter a, String name) {
        String condition;
        try {
            condition = a.canonicalConditionText(name);
        } catch (RuntimeException e) {
            condition = a.conditionText(name); // opaque/unparseable: fall back to the raw stored text
        }
        Map<String, String> aspects = new LinkedHashMap<>();
        aspects.put("condition", condition);
        aspects.put("severity", a.ruleSeverity(name));
        aspects.put("code", a.ruleErrorCode(name));
        return aspects;
    }
}
