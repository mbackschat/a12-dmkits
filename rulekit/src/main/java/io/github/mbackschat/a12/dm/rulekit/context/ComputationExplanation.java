package io.github.mbackschat.a12.dm.rulekit.context;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

/**
 * A structured projection of one computation (F9 explain, the calculated-field peer of
 * {@link RuleExplanation}) — what it computes, under which conditions, in human- and machine-readable
 * form. Works identically on a rulekit-built computation and a converted-foreign one (F10 read); both
 * are ordinary rulekit {@code Computation}s by the time they reach here.
 *
 * <ul>
 *   <li>{@code computedField}/{@code computedFieldKind} — the field the computation assigns, and its model kind.</li>
 *   <li>{@code commonPrecondition} — the guard shared by all alternatives (explained like a rule), if any.</li>
 *   <li>{@code alternatives} — each {@code (precondition?, operation)} tier: the precondition explained like a
 *       rule (absent for the single unconditional alternative) and the {@code operation} as a formula
 *       projection (rendered text + operator glossary + referenced fields).</li>
 *   <li>{@code roundingMode} — how the computed value is rounded; absent from the JSON when {@code Exact},
 *       as A12 itself stores it.</li>
 *   <li>{@code messages} — the self-validation error texts (the kernel requires them), by locale.</li>
 *   <li>{@code gloss} — a one-line natural-language summary.</li>
 * </ul>
 *
 * <p>Built via {@link #builder()}: seven components, two of them adjacent strings, are too swap-prone for a
 * positional constructor. An unset aspect takes its default — no precondition, no alternatives or messages,
 * {@code Exact} rounding.
 */
public record ComputationExplanation(
        String computedField,
        String computedFieldKind,
        Optional<RuleExplanation> commonPrecondition,
        List<Alternative> alternatives,
        RoundingMode roundingMode,
        List<Message> messages,
        String gloss) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ComputationExplanation {
        commonPrecondition = commonPrecondition == null ? Optional.empty() : commonPrecondition;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        roundingMode = roundingMode == null ? RoundingMode.EXACT : roundingMode;
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String computedField;
        private String computedFieldKind;
        private RuleExplanation commonPrecondition;
        private List<Alternative> alternatives;
        private RoundingMode roundingMode;
        private List<Message> messages;
        private String gloss;

        public Builder computedField(String v) { this.computedField = v; return this; }
        public Builder computedFieldKind(String v) { this.computedFieldKind = v; return this; }
        public Builder commonPrecondition(RuleExplanation v) { this.commonPrecondition = v; return this; }
        public Builder alternatives(List<Alternative> v) { this.alternatives = v; return this; }
        public Builder roundingMode(RoundingMode v) { this.roundingMode = v; return this; }
        public Builder messages(List<Message> v) { this.messages = v; return this; }
        public Builder gloss(String v) { this.gloss = v; return this; }

        public ComputationExplanation build() {
            return new ComputationExplanation(computedField, computedFieldKind,
                    Optional.ofNullable(commonPrecondition), alternatives, roundingMode, messages, gloss);
        }
    }

    /** One {@code (precondition?, operation)} tier — the precondition (if any) and the value it assigns. */
    public record Alternative(
            Optional<RuleExplanation> precondition,
            OperationExplanation operation,
            Optional<ToleranceBand> toleranceRangeOp) {

        public Alternative {
            precondition = precondition == null ? Optional.empty() : precondition;
            toleranceRangeOp = toleranceRangeOp == null ? Optional.empty() : toleranceRangeOp;
        }

        public Alternative(Optional<RuleExplanation> precondition, OperationExplanation operation) {
            this(precondition, operation, Optional.empty());
        }
    }

    /**
     * A computation operation (the value expression assigned to the computed field) projected as a formula:
     * its rendered DSL {@code text}, the {@code operators} it uses (catalog meaning/gotchas, the
     * {@link RuleExplanation.Operator} glossary), and the fields/groups it {@code references}.
     */
    public record OperationExplanation(String text, List<RuleExplanation.Operator> operators,
            List<RuleExplanation.Reference> references) {
        public OperationExplanation {
            operators = List.copyOf(operators);
            references = List.copyOf(references);
        }
    }

    /** One self-validation error message: its DM-JSON locale code and text. */
    public record Message(String locale, String text) {
    }

    /** The explanation as agent-parseable JSON (files-as-API). */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("computedField", computedField);
        root.put("computedFieldKind", computedFieldKind);
        commonPrecondition.ifPresent(p -> root.set("commonPrecondition", ruleJson(p)));

        ArrayNode alts = root.putArray("alternatives");
        for (Alternative a : alternatives) {
            ObjectNode node = alts.addObject();
            a.precondition().ifPresent(p -> node.set("precondition", ruleJson(p)));
            node.set("operation", operationJson(a.operation()));
            a.toleranceRangeOp().ifPresent(tolerance -> node.put("toleranceRangeOp", tolerance.wire()));
        }
        roundingMode.stored().ifPresent(mode -> root.put("roundingMode", mode));

        ArrayNode msgs = root.putArray("messages");
        for (Message m : messages) {
            ObjectNode node = msgs.addObject();
            node.put("locale", m.locale());
            node.put("text", m.text());
        }
        root.put("gloss", gloss);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render the computation explanation as JSON", e);
        }
    }

    private static ObjectNode operationJson(OperationExplanation op) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("text", op.text());
        ArrayNode ops = node.putArray("operators");
        for (RuleExplanation.Operator o : op.operators()) {
            ObjectNode on = ops.addObject();
            on.put("id", o.id());
            on.put("keyword", o.keyword());
            on.put("kind", o.kind());
            on.put("meaning", o.meaning());
            if (!o.gotchas().isEmpty()) {
                ArrayNode g = on.putArray("gotchas");
                o.gotchas().forEach(g::add);
            }
        }
        ArrayNode refs = node.putArray("references");
        for (RuleExplanation.Reference r : op.references()) {
            ObjectNode rn = refs.addObject();
            rn.put("path", r.path());
            rn.put("kind", r.kind());
        }
        return node;
    }

    /** A precondition reuses the rule-explanation JSON shape (parsed back to a node so it nests cleanly). */
    private static com.fasterxml.jackson.databind.JsonNode ruleJson(RuleExplanation explanation) {
        try {
            return MAPPER.readTree(explanation.toJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to nest the precondition explanation", e);
        }
    }
}
