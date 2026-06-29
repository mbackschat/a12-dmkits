package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;

/**
 * MODEL-REVIEW spike — the structural model-diff engine (a library primitive, MODEL-REVIEW-SPEC §1/§5).
 * Parses BOTH model states into the model and compares <b>structure</b> (so JSON formatting noise never
 * reaches the reviewer), matching elements by id/path into added / removed / modified.
 *
 * <p>This first slice (spike task #3) covers <b>fields and rules</b> and a coarse "modified" verdict via a
 * stable fingerprint (kernel-canonical condition text + field config — both format-independent). Per-element
 * change <i>detail</i> (RenderedDiff of a rule's condition, the field config delta) is task #4;
 * computations/groups, risk ranking, and rename/move detection are later slices (LEDGER).
 */
public record ModelDiff(List<ElementChange> changes) {

    /** The element families this slice diffs (computation/group come later). */
    public enum ElementKind { FIELD, RULE }

    /** How an element changed between the two states. */
    public enum ChangeKind { ADDED, REMOVED, MODIFIED }

    /** One element-level change: {@code element} of kind, located at {@code id}, changed as {@code change}. */
    public record ElementChange(ElementKind element, String id, ChangeKind change) {}

    /** Diff {@code headJson} against {@code baseJson} (both must be kernel-loadable). */
    public static ModelDiff between(String baseJson, String headJson) {
        BoundModel base = BoundModel.ofJson(baseJson);
        BoundModel head = BoundModel.ofJson(headJson);
        List<ElementChange> changes = new ArrayList<>();
        match(ElementKind.FIELD, fieldFingerprints(base), fieldFingerprints(head), changes);
        match(ElementKind.RULE, ruleFingerprints(KernelAdapter.of(base)), ruleFingerprints(KernelAdapter.of(head)),
                changes);
        return new ModelDiff(List.copyOf(changes));
    }

    /** Base-order REMOVED/MODIFIED first, then head-only ADDED — deterministic for a stable input order. */
    private static void match(ElementKind kind, Map<String, String> base, Map<String, String> head,
            List<ElementChange> out) {
        for (Map.Entry<String, String> e : base.entrySet()) {
            String headPrint = head.get(e.getKey());
            if (headPrint == null) {
                out.add(new ElementChange(kind, e.getKey(), ChangeKind.REMOVED));
            } else if (!headPrint.equals(e.getValue())) {
                out.add(new ElementChange(kind, e.getKey(), ChangeKind.MODIFIED));
            }
        }
        for (String id : head.keySet()) {
            if (!base.containsKey(id)) {
                out.add(new ElementChange(kind, id, ChangeKind.ADDED));
            }
        }
    }

    private static Map<String, String> fieldFingerprints(BoundModel m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.fields().forEach(f -> out.put(f.path(), fieldFingerprint(f)));
        return out;
    }

    /** Kind + per-kind config — all format-independent, so a reformat is never seen as a change. */
    private static String fieldFingerprint(FieldInfo f) {
        StringBuilder sb = new StringBuilder(f.kind().name());
        if (f.kind() == ValueKind.NUMBER) {
            sb.append("|scale=").append(f.scale());
        }
        f.numberMin().ifPresent(v -> sb.append("|min=").append(v));
        f.numberMax().ifPresent(v -> sb.append("|max=").append(v));
        f.minLength().ifPresent(v -> sb.append("|minLen=").append(v));
        f.maxLength().ifPresent(v -> sb.append("|maxLen=").append(v));
        f.pattern().ifPresent(v -> sb.append("|pat=").append(v));
        return sb.toString();
    }

    private static Map<String, String> ruleFingerprints(KernelAdapter a) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : a.ruleFullNames()) {
            out.put(name, ruleFingerprint(a, name));
        }
        return out;
    }

    /** Kernel-canonical condition text (format-independent) + severity + error code. */
    private static String ruleFingerprint(KernelAdapter a, String name) {
        String condition;
        try {
            condition = a.canonicalConditionText(name);
        } catch (RuntimeException e) {
            condition = a.conditionText(name); // opaque/unparseable: fall back to the raw stored text
        }
        return condition + "||sev=" + a.ruleSeverity(name) + "||code=" + a.ruleErrorCode(name);
    }
}
