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
 * The fuller AST edit-script (which operand/clause moved), rename/move, and computations/groups are later
 * slices (MODEL-REVIEW-LEDGER MR1–MR4).
 */
public record ModelDiff(List<ElementChange> changes) {

    /** The element families this slice diffs (computation/group come later). */
    public enum ElementKind { FIELD, RULE }

    /** How an element changed between the two states. */
    public enum ChangeKind { ADDED, REMOVED, MODIFIED }

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
        match(ElementKind.FIELD, fieldAspects(base), fieldAspects(head), ModelDiff::fieldReasons, changes);
        match(ElementKind.RULE, ruleAspects(baseRules), ruleAspects(headRules),
                (change, id, deltas) -> ruleReasons(change, id, deltas, baseRules, headRules), changes);
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

    // --- risk classification (the §5b taste: loosening > tightening) ------------------------------

    /** Maps a change to its reason codes — pluggable so the rule path can consult the two ASTs. */
    @FunctionalInterface
    private interface ReasonClassifier {
        List<String> reasons(ChangeKind change, String id, List<Delta> deltas);
    }

    private static ElementChange classified(ElementKind kind, String id, ChangeKind change,
            List<Delta> deltas, ReasonClassifier classifier) {
        List<String> reasons = classifier.reasons(change, id, deltas);
        Risk risk = reasons.stream().map(ModelDiff::riskOf).max(Comparator.naturalOrder()).orElse(Risk.LOW);
        return new ElementChange(kind, id, change, risk, List.copyOf(reasons), deltas);
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
     * A changed condition reads as {@code POLARITY_INVERTED} only when both sides convert to a typed AST
     * and the head is the clean logical complement of the base ({@link ConditionPolarity}); otherwise the
     * honest {@code CONDITION_CHANGED} — an opaque or non-flip change whose direction we can't prove. The
     * structural detector proves a flip, never sameness (that is the behavioral {@code --deep} path, SPEC §5c).
     */
    private static String conditionReason(String ruleName, KernelAdapter base, KernelAdapter head) {
        if (base.read(ruleName) instanceof ReadResult.Converted b
                && head.read(ruleName) instanceof ReadResult.Converted h
                && ConditionPolarity.isInversionOf(h.condition(), b.condition())) {
            return "POLARITY_INVERTED";
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
            case "RULE_ADDED", "CONDITION_CHANGED", "CONSTRAINT_TIGHTENED", "STRUCTURE_ADDED",
                    "STRUCTURE_REMOVED", "PATTERN_CHANGED", "SEVERITY_CHANGED", "SCALE_CHANGED" -> Risk.MEDIUM;
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
