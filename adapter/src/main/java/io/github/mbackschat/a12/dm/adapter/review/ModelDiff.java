package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;

/**
 * MODEL-REVIEW spike — the structural model-diff engine (a library primitive, MODEL-REVIEW-SPEC §1/§5).
 * Parses BOTH model states into the model and compares <b>structure</b> (so JSON formatting noise never
 * reaches the reviewer), matching elements by id/path into added / removed / modified, each carrying the
 * per-aspect {@link Delta}s that changed.
 *
 * <p>This slice (spike tasks #3/#4) covers <b>fields and rules</b>. An element's state is an ordered
 * <i>aspect map</i> of format-independent values (kernel-canonical condition text + severity + code for a
 * rule; kind + per-kind config for a field), so a pure reformat is never a change, and a MODIFIED element
 * reports exactly the aspects that differ. Risk ranking, rename/move detection, computations/groups, and the
 * AST-level condition diff are later slices (LEDGER).
 */
public record ModelDiff(List<ElementChange> changes) {

    /** The element families this slice diffs (computation/group come later). */
    public enum ElementKind { FIELD, RULE }

    /** How an element changed between the two states. */
    public enum ChangeKind { ADDED, REMOVED, MODIFIED }

    /** One aspect that changed: {@code before}/{@code after} are null on the side where the element is absent. */
    public record Delta(String aspect, String before, String after) {}

    /** One element-level change, with the aspects that differ (all aspects for ADDED/REMOVED). */
    public record ElementChange(ElementKind element, String id, ChangeKind change, List<Delta> deltas) {}

    /** Diff {@code headJson} against {@code baseJson} (both must be kernel-loadable). */
    public static ModelDiff between(String baseJson, String headJson) {
        BoundModel base = BoundModel.ofJson(baseJson);
        BoundModel head = BoundModel.ofJson(headJson);
        List<ElementChange> changes = new ArrayList<>();
        match(ElementKind.FIELD, fieldAspects(base), fieldAspects(head), changes);
        match(ElementKind.RULE, ruleAspects(KernelAdapter.of(base)), ruleAspects(KernelAdapter.of(head)), changes);
        return new ModelDiff(List.copyOf(changes));
    }

    /** Base-order REMOVED/MODIFIED first, then head-only ADDED — deterministic for a stable input order. */
    private static void match(ElementKind kind, Map<String, Map<String, String>> base,
            Map<String, Map<String, String>> head, List<ElementChange> out) {
        for (Map.Entry<String, Map<String, String>> e : base.entrySet()) {
            Map<String, String> headAspects = head.get(e.getKey());
            if (headAspects == null) {
                out.add(new ElementChange(kind, e.getKey(), ChangeKind.REMOVED, allDeltas(e.getValue(), false)));
            } else {
                List<Delta> deltas = aspectDeltas(e.getValue(), headAspects);
                if (!deltas.isEmpty()) {
                    out.add(new ElementChange(kind, e.getKey(), ChangeKind.MODIFIED, deltas));
                }
            }
        }
        for (Map.Entry<String, Map<String, String>> e : head.entrySet()) {
            if (!base.containsKey(e.getKey())) {
                out.add(new ElementChange(kind, e.getKey(), ChangeKind.ADDED, allDeltas(e.getValue(), true)));
            }
        }
    }

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
