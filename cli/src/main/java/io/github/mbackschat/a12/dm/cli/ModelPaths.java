package io.github.mbackschat.a12.dm.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * Pre-flight resolution for the verbs' <b>model-entity-path</b> arguments ({@code --field}, {@code --group},
 * {@code --rule}). These name a path <em>inside the model</em> ({@code /Group/Field}); a frequent slip is
 * passing the <em>model file</em> there instead (N6 runs repeatedly put the {@code .json} file path into
 * {@code --field}). A model entity path and a Unix file path both start with {@code /}, so a syntactic
 * "starts with /" check can't tell them apart — but the loaded model can: if the path doesn't resolve to a
 * real field/group/rule, fail fast here with a message that names the file-vs-entity distinction, instead of
 * deferring to a generic downstream kernel error (or, for {@code --rule}, a murky {@code Opaque}). When the
 * value <em>looks like a file</em> (ends in {@code .json} or names an existing file) the message is
 * sharpened to call out the exact confusion (the recurring N6 gap).
 *
 * <p>Each method returns {@code null} when the path resolves, or an actionable error string when it doesn't.
 */
public final class ModelPaths {

    private ModelPaths() {
    }

    /**
     * True when a value has the syntax required of a non-root model entity path. Checking only for a slash is
     * insufficient: {@code Order/Quantity} has one but is still relative.
     *
     * <p><b>An EMPTY segment is refused, and that is A12's own contract rather than a rule invented here.</b>
     * {@code ElementPathUtils.splitAbsPath} — the kernel utility that owns absolute-path splitting — states it
     * outright: <em>"must not end with '/', and must start with '/' unless empty; '/A/B/C' and '/A' are valid,
     * '/A/B/' and '/' are not"</em>. Its implementation nonetheless splits with Java's default {@code split},
     * which silently drops a trailing empty, so the kernel <em>accepts</em> such a path and resolves it to the
     * same entity — measured 2026-08-29: {@code computedField:"/Probe/Store/StockLevel/"} previews
     * {@code KERNEL_CONFIRMED}. Delegating would therefore let us persist a {@code computedFieldRelPath} A12
     * itself would never write (a trailing empty segment), under a computation named {@code Comp} derived from
     * the empty last segment.
     *
     * <p>This is checked here rather than left to the kernel because the accidental guard that used to catch it
     * is gone: before the computation spec accepted a declaring {@code group}, a trailing slash made the derived
     * group a FIELD path, which {@code RuleValidator.addPrecheck} refused as {@code RK_NO_SUCH_GROUP}. Supplying
     * {@code group} bypasses that derivation, so the input class reached the kernel for the first time. The
     * refusal is safe in the direction that matters: every caller applies it to a caller-authored SPEC value
     * ({@code field} / {@code computedField}), never to a path read back out of a model, so no legal model
     * becomes unreadable.
     */
    public static boolean isAbsoluteEntityPath(String path) {
        if (path == null || !path.startsWith("/")) {
            return false;
        }
        // segment 0 is the empty string ahead of the leading '/'; an entity below the root has at least two more.
        var segments = io.github.mbackschat.a12.dm.core.EntityPath.of(path).segments();
        return segments.size() >= 3 && segments.subList(1, segments.size()).stream().noneMatch(String::isEmpty);
    }

    /**
     * The refusal message for a value {@link #isAbsoluteEntityPath} rejected, named by the spec KEY that
     * carried it. It lives beside the predicate rather than at either call site so the two verbs applying it —
     * {@code rule add}'s {@code field} and {@code computation add}'s {@code computedField} — cannot state the
     * same contract two ways.
     */
    public static String notAnAbsoluteEntityPath(String key, String value) {
        return key + " must be an absolute path with no empty segment (e.g. /Group/Field), got: " + value;
    }

    public static String fieldProblem(BoundModel model, String path) {
        return model.fieldAt(path).isPresent() ? null : problem(path, "field", "/Group/Field");
    }

    /**
     * A concrete "did you mean" cue for a rejected {@code --field}: the field(s) the rule's
     * {@code condition} references, resolved to their full model paths. The generic {@link #fieldProblem}
     * message tells the agent <em>what</em> {@code --field} must be (an entity path); this hands it a
     * <em>value to copy</em> — the recurring N6 slip is a weaker model that re-reads the rule but never
     * acts, so naming the path it already wrote in the condition closes the loop. A field is referenced
     * if its full path or its short name appears as a word in the condition. Returns {@code null} when the
     * condition names no model field (no confident suggestion to make).
     */
    public static String suggestField(BoundModel model, String condition) {
        if (condition == null || condition.isBlank()) {
            return null;
        }
        java.util.List<String> refs = model.fields()
                .map(io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo::path)
                .filter(p -> conditionReferences(condition, p))
                .distinct()
                .toList();
        if (refs.isEmpty()) {
            return null;
        }
        if (refs.size() == 1) {
            return "Did you mean --field " + refs.get(0) + "? (it is the field your --condition references).";
        }
        return "Did you mean one of the fields your --condition references: " + refs + "?";
    }

    private static boolean conditionReferences(String condition, String fieldPath) {
        if (condition.contains(fieldPath)) {
            return true;   // the full /Group/Field path is written out verbatim
        }
        String shortName = fieldPath.substring(fieldPath.lastIndexOf('/') + 1);
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(shortName) + "\\b")
                .matcher(condition).find();
    }

    public static String groupProblem(BoundModel model, String path) {
        boolean resolves = model.groups().anyMatch(g -> g.path().equals(path));
        return resolves ? null : problem(path, "group", "/Group");
    }

    public static String ruleProblem(Set<String> ruleFullNames, String path) {
        return ruleProblem(ruleFullNames, Set.of(), path);
    }

    /**
     * As {@link #ruleProblem(Set, String)}, but if {@code path} names a <em>computation</em> (a calculated
     * field, not a validation rule) the error says so and points at the computation-aware verbs, instead
     * of the misleading "not a rule" — computations are a distinct element kind.
     */
    public static String ruleProblem(Set<String> ruleFullNames, Set<String> computationFullNames, String path) {
        if (ruleFullNames.contains(path)) {
            return null;
        }
        String fileHint = filePathHint(path, "rule", "/Group/RuleName");
        if (fileHint != null) {
            return fileHint;
        }
        if (computationFullNames.contains(path)) {
            return "'" + path + "' is a computation (a calculated field), not a validation rule — use"
                    + " `computation read " + path + "` (or `computation explain " + path + "`) to read it,"
                    + " or `model read` for the whole model (rules and computations).";
        }
        return "'" + path + "' is not a rule in this model — pass the rule's full name (its slash-path, e.g."
                + " /Group/RuleName), not its short name, not the opaque element id, and not a file path. The model"
                + " file is the `-m <model.json>` argument. Rules in this model: " + ruleFullNames + ".";
    }

    private static String problem(String value, String kind, String example) {
        String fileHint = filePathHint(value, kind, example);
        if (fileHint != null) {
            return fileHint;
        }
        return "'" + value + "' is not a " + kind + " in this model — it must be a model entity path like "
                + example + " (a path INSIDE the model), not a file path. The model file is the `-m <model.json>`"
                + " argument; run `dmtool -m <model.json> model describe` to list the model's entities.";
    }

    /**
     * The sharpened message for the recurring slip: the value passed to {@code --field}/{@code --group}/
     * {@code --rule} is actually a <b>file path</b> (it ends in {@code .json} or names an existing file) —
     * almost certainly the {@code <model.json>} argument pasted into the wrong slot. Returns {@code null}
     * when the value doesn't look like a file (so the generic resolution message is used).
     */
    private static String filePathHint(String value, String kind, String example) {
        if (value == null || !looksLikeFile(value)) {
            return null;
        }
        return "'" + value + "' looks like a FILE path, but --" + kind + " must be a path INSIDE the model"
                + " (e.g. " + example + ") — you have likely passed the <model.json> file argument into --" + kind
                + ". The model FILE is the `-m <model.json>` argument; --" + kind + " is an entity path within it."
                + " Run `dmtool -m <model.json> model describe` to list the model's entities.";
    }

    private static boolean looksLikeFile(String value) {
        if (value.endsWith(".json")) {
            return true;
        }
        try {
            return Files.isRegularFile(Path.of(value));
        } catch (RuntimeException invalidPath) {
            return false;   // not a usable path → not a file, fall through to the generic message
        }
    }
}
