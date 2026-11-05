package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.cli.ApplyCommand.Op;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * The checks an {@code apply} op-record passes before it is dispatched: its {@code target}/{@code op} pair names an
 * {@link Op}, and its keys are that op's. Kept apart from {@link ApplyCommand}, which dispatches and commits, because
 * these checks read only the op-record and the {@link Op} registry.
 */
final class ApplyOpValidation {

    private ApplyOpValidation() {
    }

    /**
     * Op-arg validation (CLI-SPEC §7) — check the op-record's keys against the verb's param model and emit a
     * corrective diagnostic (the most common batch mistake self-corrects instead of failing opaquely): an
     * {@code op} not valid for its {@code target} → {@code RK_UNKNOWN_OP}; a typo'd/unknown key →
     * {@code RK_UNKNOWN_ARG} with a {@code where.arg} and a did-you-mean {@code fix}.
     */
    static List<Diagnostic> validate(JsonNode op) {
        List<Diagnostic> out = new ArrayList<>();
        // cross-type corrective: a `batch` op-record ({verb,args}) was fed to `apply`
        if (op.has("verb") || op.has("argv")) {
            out.add(Diagnostic.precheck(Optional.of("RK_WRONG_BATCH_KIND"),
                    "this op has verb/argv — it is a `batch` op-record, not an `apply` op",
                    new Diagnostic.Where((String) null, Optional.empty()),
                    Optional.of("use the `batch` verb for {verb,args} ops; `apply` ops are {target,op,…args}"),
                    Optional.empty()));
            return out;
        }
        Op which = Op.of(op);
        if (which == null) {
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_OP"), unknownOpSummary(op),
                    new Diagnostic.Where((String) null, Optional.empty()),
                    Optional.of(unknownOpFix(op)),
                    Optional.empty()));
            return out;
        }
        String label = which.label();
        Set<String> valid = which.keys;
        for (Iterator<String> it = op.fieldNames(); it.hasNext();) {
            String f = it.next();
            if (f.equals("target") || f.equals("op") || valid.contains(f)) {
                continue;
            }
            String near = Suggestions.nearest(f, valid);
            Optional<String> relocation = MetadataRelocation.fix(which.target, which.op, f)
                    .or(() -> IncludeAliasRetirement.fix(which.target, f));
            out.add(Diagnostic.precheck(Optional.of("RK_UNKNOWN_ARG"),
                    "unknown arg '" + f + "' for op '" + label + "'",
                    Diagnostic.Where.arg(null, f),
                    relocation.isPresent() ? relocation
                            : Optional.of(near != null ? "did you mean '" + near + "'?"
                                    : valid.isEmpty() ? "op '" + label + "' takes no args beyond target and op; remove '"
                                            + f + "'"
                                    : "valid args: " + String.join(", ", new TreeSet<>(valid))),
                    Optional.empty()));
        }
        // the flat key-set above covers the op-record's TOP level; for ops with a rich nested spec, add the
        // schema-sourced NESTED check (e.g. a typo inside a `string` config block, or a message-item key) —
        // the gap a flat key-set can't see (the same SpecKeys the standalone spec verbs use).
        String nestedSchema = NESTED_SCHEMA.get(which);
        if (nestedSchema != null) {
            out.addAll(SpecKeys.forSchema(nestedSchema).validateNested(op));
        }
        return out;
    }

    /** Ops whose spec has nested objects worth schema-validating beyond the flat top-level key-set above. */
    private static final Map<Op, String> NESTED_SCHEMA = Map.of(
            Op.FIELD_ADD, "field-add", Op.FIELD_MODIFY, "field-modify",
            Op.RULE_ADD, "rule-add", Op.RULE_MODIFY, "rule-add",
            Op.COMPUTATION_ADD, "computation-add", Op.COMPUTATION_MODIFY, "computation-add",
            Op.TYPEDEF_ADD, "typedef-add", Op.TYPEDEF_MODIFY, "typedef-add",
            Op.CRITERION_ADD, "criterion-add",
            Op.META_MODIFY, "apply#/$defs/metaModify");

    /** The supported {@code target op} labels, sorted — for the unknown-op diagnostic. */
    private static String supportedOps() {
        TreeSet<String> labels = new TreeSet<>();
        for (Op o : Op.values()) {
            labels.add(o.label());
        }
        return String.join(", ", labels);
    }

    /** The op whose {@link Op#label} is exactly {@code text}, or {@code null} — asked of the enum, not of JSON. */
    private static Op labelOf(String text) {
        for (Op o : Op.values()) {
            if (o.label().equals(text)) {
                return o;
            }
        }
        return null;
    }

    /**
     * The unknown-op summary, which must quote what the author WROTE.
     *
     * <p>It used to concatenate {@code target + " " + op} unconditionally, so a record carrying only
     * {@code {"op": "rule add"}} was reported as {@code op ' rule add'} — a value with a leading space that
     * appears nowhere in the input. Measured 2026-09-04: reading that, one agent could not tell whether the
     * space was its own typo, the tool's formatting, or a real parse of its file.
     */
    private static String unknownOpSummary(JsonNode op) {
        String target = op.path("target").asText("");
        String verb = op.path("op").asText("");
        boolean hasTarget = op.hasNonNull("target");
        if (target.isEmpty() && verb.isEmpty()) {
            return hasTarget || op.hasNonNull("op")
                    ? "this op-record's target/op pair is empty"
                    : "this op-record names no target/op pair";
        }
        if (target.isEmpty()) {
            return "apply does not support op '" + verb + "' with " + (hasTarget ? "an empty" : "no") + " target";
        }
        if (verb.isEmpty()) {
            // Never `target + " " + verb` here: that printed `op 'rule '`, a trailing-space value the author
            // did not write — the same defect as the leading-space case below, found by a cold review one
            // commit after it was fixed in only one direction.
            return "apply does not support target '" + target + "' with no op";
        }
        if (labelOf(verb) != null) {
            // `op` already holds a whole label. Concatenating produced `op 'rule rule add'`, a value assembled
            // from two keys and quoted as if written — so each key is quoted as itself and the fix splits it.
            return "apply does not support target '" + target + "' with op '" + verb + "'";
        }
        return "apply does not support op '" + target + " " + verb + "'";
    }

    /**
     * …and the corrective, which must not hand back a spelling the tool then refuses.
     *
     * <p>{@link #supportedOps} lists SPACE-JOINED labels ({@code rule add}), while an op-record spells the same
     * op as two keys ({@code {"target":"rule","op":"add"}}). Offering the label alone as the answer to a bad
     * {@code op} value therefore CONFIRMS the mistake it is correcting — the exact loop that cost this session
     * a run on 2026-09-04, where {@code {"op":"rule add"}} was refused and then told "supported ops: …, rule
     * add, …". When the value the author put in {@code op} is itself a valid label, the corrective splits it
     * for them; otherwise it names the shape before listing the labels.
     */
    private static String unknownOpFix(JsonNode op) {
        String verb = op.path("op").asText("");
        // The split hint fires whenever `op` HOLDS a label, whether or not a target came with it: an author who
        // wrote both {"target":"rule","op":"rule add"} has demonstrably confused the two spellings, and that is
        // exactly when withholding the correction is worst. (It was withheld there until a cold review, because
        // the branch was gated on the target being absent.)
        Optional<String> retired = IncludeAliasRetirement.opFix(op.path("target").asText(""), verb);
        if (retired.isPresent()) return retired.get();
        if (labelOf(verb) != null) {
            String[] halves = verb.split(" ", 2);
            return "an op-record spells that as TWO keys: {\"target\": \"" + halves[0] + "\", \"op\": \""
                    + halves[1] + "\", …} — the space-joined form is the label, not the `op` value";
        }
        // The list leads because a reader truncating this string is looking for the valid set; calling them
        // "(target op) pairs" is what stops the space-joined label from reading as an `op` value.
        return "supported (target op) pairs: " + supportedOps()
                + " — an op-record spells one as {\"target\": …, \"op\": …, …args}";
    }
}
