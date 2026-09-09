package io.github.mbackschat.a12.dm.cli.computation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.cli.ModelPaths;
import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/**
 * Parses a CLI <b>computation-spec</b> JSON into the kernel-checkable {@link DraftComputation} + its
 * messages — the computation counterpart of the rule-spec {@code add} reads. A computation is a
 * calculated field, an optional common precondition, and one or more {@code (precondition?, operation)}
 * alternatives, with DSL text in each slot (the CLI never builds the typed AST; the kernel parses the
 * text on validation). Shape:
 *
 * <pre>
 * { "computedField": "/Group/Field",            // required — the field the computation assigns
 *   "group": "/OtherGroup",                      // optional — where the computation LIVES; default the field's parent
 *   "name": "FieldComp",                         // optional — defaults to &lt;Field&gt;Comp
 *   "commonPrecondition": "&lt;DSL&gt;",               // optional — guard shared by all alternatives
 *   "alternatives": [                            // required, non-empty
 *     { "precondition": "&lt;DSL&gt;", "operation": "&lt;DSL&gt;",
 *       "toleranceRangeOp": "DiffersWithToleranceRange1" }, // optional per tier
 *     { "operation": "&lt;DSL&gt;" } ],                          // OR one unconditional
 *   "errorCodesToSuppress": ["MVK_INVALID_COMPARE_DEC_PLACES"], // optional A12 owner-shaped list
 *   "messages": [ {"locale":"en_US","text":"…"} ] }          // required, non-empty
 * </pre>
 *
 * <p><b>{@code group} is the declaring group — where the computation LIVES, not what it may write.</b> The
 * computed field may sit in a different subtree entirely; the kernel accepts that, and the stored
 * {@code computedFieldRelPath} then walks out of the declaring group and back down
 * ({@code validate.laws.ComputationDeclaringGroupLawsTest}). Whether the named group EXISTS is not decided
 * here: an absent one is refused by {@code RuleValidator}'s own {@code RK_NO_SUCH_GROUP} precheck, which
 * reaches the caller as a kernel-path diagnostic rather than as a refusal this class invented.
 */
public final class ComputationSpec {

    /** The exact top-level computation-spec keys, shared with {@code apply}'s flat op gate. */
    public static final Set<String> KEYS = Set.of(
            "computedField", "group", "alternatives", "commonPrecondition", "comment", "name", "messages",
            "errorCodesToSuppress");

    private ComputationSpec() {
    }

    /** True iff {@code spec} looks like a computation-spec (vs the rule-spec {@code field}/{@code condition}). */
    public static boolean looksLikeComputation(JsonNode spec) {
        return spec.hasNonNull("computedField");
    }

    /**
     * The parsed result: the draft + messages, or an actionable {@code problem} (exactly one is set).
     *
     * <p>The problem is a coded {@link Diagnostic} rather than a bare string because every caller emits it into
     * the result envelope. It used to be a string that {@code computation add}/{@code modify} printed to stderr
     * with an empty stdout, so an agent parsing the contract for a result got nothing at all — the same defect
     * {@code refuseUnreadableInput} closed for unreadable inputs.
     */
    public record Parsed(DraftComputation draft, List<LocalizedText> messages, Diagnostic problem) {
        public boolean ok() {
            return problem == null;
        }

        /** The refusal's one-line summary — what a caller puts on the envelope. */
        public String error() {
            return problem == null ? null : problem.summary();
        }

        /** The computation's full name (group + name) — its element id. */
        public String fullName() {
            return draft.groupPath() + "/" + draft.name();
        }
    }

    public static Parsed parse(JsonNode spec) {
        String computedField = text(spec, "computedField");
        if (computedField == null) {
            return incomplete("computation-spec needs computedField (the calculated field's full path)",
                    "computedField");
        }
        if (!ModelPaths.isAbsoluteEntityPath(computedField)) {
            return notAnAbsolutePath("computedField", computedField,
                    spec.hasNonNull("group") ? spec.get("group").asText() : null);
        }
        int slash = computedField.lastIndexOf('/');
        String fieldName = computedField.substring(slash + 1);
        // The DECLARING group: where the computation element lives, which A12 lets differ from where it writes.
        // Two refusals are deliberately NOT made here. (1) No containment check against computedField — the
        // kernel accepts a target in another subtree, and the one case it DOES refuse (a per-row operand of a
        // repeatable declaring group) is the kernel's to report, since the client cannot see it without parsing
        // the operation. (2) No path-FORM check: `isAbsoluteEntityPath` requires two segments, so it would
        // refuse a ROOT group — legal here, and the very shape the measured cross-group case uses. A group that
        // does not resolve is refused by RuleValidator's own RK_NO_SUCH_GROUP precheck, which covers a malformed
        // value and a missing one with the same diagnostic. `computedField` is form-checked above because
        // nothing downstream re-derives the group from it once `group` is supplied.
        String groupPath = spec.hasNonNull("group")
                ? spec.get("group").asText()
                : computedField.substring(0, slash);
        String name = spec.hasNonNull("name") ? spec.get("name").asText() : fieldName + "Comp";

        JsonNode alts = spec.path("alternatives");
        if (!alts.isArray() || alts.isEmpty()) {
            return incomplete("computation-spec needs a non-empty alternatives array", "alternatives");
        }
        List<DraftComputation.Alternative> alternatives = new ArrayList<>();
        for (JsonNode alt : alts) {
            String operation = text(alt, "operation");
            if (operation == null) {
                return incomplete("each alternative needs an operation (the value-expression DSL)",
                        "alternatives");
            }
            ToleranceBand toleranceRangeOp = null;
            if (alt.has("toleranceRangeOp") && !alt.get("toleranceRangeOp").isNull()) {
                JsonNode tolerance = alt.get("toleranceRangeOp");
                if (!tolerance.isTextual() || tolerance.asText().isBlank()) {
                    return invalidValue("toleranceRangeOp must be one of "
                            + java.util.Arrays.stream(ToleranceBand.values()).map(ToleranceBand::wire).toList(),
                            "toleranceRangeOp");
                }
                try {
                    toleranceRangeOp = ToleranceBand.fromWire(tolerance.asText());
                } catch (IllegalArgumentException e) {
                    return invalidValue(e.getMessage(), "toleranceRangeOp");
                }
            }
            alternatives.add(DraftComputation.Alternative.builder(operation)
                    .precondition(text(alt, "precondition"))
                    .toleranceRangeOp(toleranceRangeOp)
                    .build());
        }
        // a whole-computation comment leads the first operation (mirrors the library Computation.Builder.comment(String))
        String comment = text(spec, "comment");
        if (comment != null && !comment.isBlank()) {
            DraftComputation.Alternative first = alternatives.get(0);
            alternatives.set(0, DraftComputation.Alternative
                    .builder(Comments.withLeadingComment(comment, first.operation()))
                    .precondition(first.precondition())
                    .toleranceRangeOp(first.toleranceRangeOp())
                    .build());
        }

        List<LocalizedText> messages = messages(spec);
        if (messages.isEmpty()) {
            return incomplete("computation-spec needs at least one message {locale,text}", "messages");
        }

        List<String> errorCodesToSuppress = new ArrayList<>();
        for (JsonNode code : spec.path("errorCodesToSuppress")) {
            errorCodesToSuppress.add(code.asText());
        }
        // Relative to the computation ELEMENT (group + name), so it stays "../<Field>" when the element sits
        // beside its field and becomes a walk out and back down when `group` put it elsewhere.
        String computedFieldRelPath =
                EntityPath.of(groupPath + "/" + name).relativize(EntityPath.of(computedField));
        DraftComputation draft = DraftComputation.builder().groupPath(groupPath).name(name)
                .computedFieldRelPath(computedFieldRelPath).commonPrecondition(text(spec, "commonPrecondition"))
                .alternatives(alternatives).errorCodesToSuppress(errorCodesToSuppress).messages(messages).build();
        return new Parsed(draft, messages, null);
    }

    /** A required slot is missing or empty — {@code RK_INCOMPLETE_RULE}, the spec-shaped refusal. */
    private static Parsed incomplete(String summary, String arg) {
        return refusal(RkCode.INCOMPLETE_RULE, summary, arg);
    }

    /** A present key carries a value the spec cannot use — {@code RK_INVALID_VALUE}. */
    private static Parsed invalidValue(String summary, String arg) {
        return refusal(RkCode.INVALID_VALUE, summary, arg);
    }

    /**
     * A path-shape refusal, carrying the path the author MEANT rather than the code's generic corrective.
     * {@code declaredGroup} is the spec's own {@code group} when it has one — the only base a relative value can
     * resolve against here, since a malformed {@code computedField} cannot yield one.
     */
    private static Parsed notAnAbsolutePath(String key, String value, String declaredGroup) {
        return refusal(RkCode.INVALID_VALUE, ModelPaths.notAnAbsoluteEntityPath(key, value), key,
                ModelPaths.absoluteEntityPathFix(key, value, declaredGroup));
    }

    /**
     * A refused parse, located by the spec KEY that carried the bad value.
     *
     * <p>{@code where.path} is deliberately left unset. The schema defines it as a model-element path, and the
     * refusals here fire precisely because the value is not one — putting it there would publish a model locus
     * for {@code "model.json"}. The key goes in {@code where.arg}, which is what it is.
     */
    private static Parsed refusal(RkCode code, String summary, String arg) {
        return new Parsed(null, null, Diagnostic.precheck(code, summary, Diagnostic.Where.arg(null, arg)));
    }

    /** …and with a corrective computed from the value, where the code's generic one would not describe it. */
    private static Parsed refusal(RkCode code, String summary, String arg, String fix) {
        return new Parsed(null, null, Diagnostic.precheck(code, summary, Diagnostic.Where.arg(null, arg), fix));
    }

    private static List<LocalizedText> messages(JsonNode spec) {
        List<LocalizedText> messages = new ArrayList<>();
        for (JsonNode m : spec.path("messages")) {
            if (m.hasNonNull("locale") && m.hasNonNull("text")) {
                messages.add(new LocalizedText(m.get("locale").asText(), m.get("text").asText()));
            }
        }
        return messages;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
