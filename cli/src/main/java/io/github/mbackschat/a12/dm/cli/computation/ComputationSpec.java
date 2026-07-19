package io.github.mbackschat.a12.dm.cli.computation;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * Parses a CLI <b>computation-spec</b> JSON into the kernel-checkable {@link DraftComputation} + its
 * messages — the computation counterpart of the rule-spec {@code add} reads. A computation is a
 * calculated field, an optional common precondition, and one or more {@code (precondition?, operation)}
 * alternatives, with DSL text in each slot (the CLI never builds the typed AST; the kernel parses the
 * text on validation). Shape:
 *
 * <pre>
 * { "computedField": "/Group/Field",            // required — the field the computation assigns
 *   "name": "FieldComp",                         // optional — defaults to &lt;Field&gt;Comp
 *   "commonPrecondition": "&lt;DSL&gt;",               // optional — guard shared by all alternatives
 *   "alternatives": [                            // required, non-empty
 *     { "precondition": "&lt;DSL&gt;", "operation": "&lt;DSL&gt;",
 *       "toleranceRangeOp": "DiffersWithToleranceRange1" }, // optional per tier
 *     { "operation": "&lt;DSL&gt;" } ],                          // OR one unconditional
 *   "messages": [ {"locale":"en_US","text":"…"} ] }          // required, non-empty
 * </pre>
 */
public final class ComputationSpec {

    private ComputationSpec() {
    }

    /** True iff {@code spec} looks like a computation-spec (vs the rule-spec {@code field}/{@code condition}). */
    public static boolean looksLikeComputation(JsonNode spec) {
        return spec.hasNonNull("computedField");
    }

    /** The parsed result: the draft + messages, or an actionable {@code error} (exactly one is set). */
    public record Parsed(DraftComputation draft, List<LocalizedText> messages, String error) {
        public boolean ok() {
            return error == null;
        }

        /** The computation's full name (group + name) — its element id. */
        public String fullName() {
            return draft.groupPath() + "/" + draft.name();
        }
    }

    public static Parsed parse(JsonNode spec) {
        String computedField = text(spec, "computedField");
        if (computedField == null) {
            return error("computation-spec needs computedField (the calculated field's full path)");
        }
        int slash = computedField.lastIndexOf('/');
        if (slash <= 0) {
            return error("computedField must be an absolute path (e.g. /Group/Field), got: " + computedField);
        }
        String groupPath = computedField.substring(0, slash);
        String fieldName = computedField.substring(slash + 1);
        String name = spec.hasNonNull("name") ? spec.get("name").asText() : fieldName + "Comp";

        JsonNode alts = spec.path("alternatives");
        if (!alts.isArray() || alts.isEmpty()) {
            return error("computation-spec needs a non-empty alternatives array");
        }
        List<DraftComputation.Alternative> alternatives = new ArrayList<>();
        for (JsonNode alt : alts) {
            String operation = text(alt, "operation");
            if (operation == null) {
                return error("each alternative needs an operation (the value-expression DSL)");
            }
            ToleranceBand toleranceRangeOp = null;
            if (alt.has("toleranceRangeOp") && !alt.get("toleranceRangeOp").isNull()) {
                JsonNode tolerance = alt.get("toleranceRangeOp");
                if (!tolerance.isTextual() || tolerance.asText().isBlank()) {
                    return error("toleranceRangeOp must be one of "
                            + java.util.Arrays.stream(ToleranceBand.values()).map(ToleranceBand::wire).toList());
                }
                try {
                    toleranceRangeOp = ToleranceBand.fromWire(tolerance.asText());
                } catch (IllegalArgumentException e) {
                    return error(e.getMessage());
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
            return error("computation-spec needs at least one message {locale,text}");
        }

        DraftComputation draft = new DraftComputation(groupPath, name, "../" + fieldName,
                text(spec, "commonPrecondition"), alternatives);
        return new Parsed(draft, messages, null);
    }

    private static Parsed error(String message) {
        return new Parsed(null, null, message);
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
