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
                    + " `computation explain " + path + "` to inspect it, or `rule read --all` to read the"
                    + " whole model (rules and computations).";
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
