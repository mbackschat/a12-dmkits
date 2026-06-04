package io.github.mbackschat.a12.rulekit.context;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A structured projection of one rule's condition (F9 explain) — what it checks, in human- and
 * machine-readable form. The same mechanism serves both consumers (§14): understanding/rewriting an
 * existing rule and grounding new authoring. It works identically on a rulekit-built condition and a
 * <b>converted-foreign one (F10 ingest)</b> — both are ordinary rulekit ASTs by the time they reach here.
 *
 * <ul>
 *   <li>{@code text} — the canonical (EN) rendering of the condition.</li>
 *   <li>{@code operators} — each operator used, with its catalog {@code meaning}, kind, and the
 *       author-relevant {@code gotchas} (the semantic surprises {@code checkConsistency} is silent on).</li>
 *   <li>{@code references} — the fields/groups the condition touches, with their model kind.</li>
 *   <li>{@code iterates}/{@code iterationGroup} — whether the condition ranges over a repeatable group, and which.</li>
 *   <li>{@code gloss} — a one-line natural-language summary.</li>
 * </ul>
 */
public record RuleExplanation(
        String text,
        List<Operator> operators,
        List<Reference> references,
        boolean iterates,
        Optional<String> iterationGroup,
        String gloss) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RuleExplanation {
        operators = List.copyOf(operators);
        references = List.copyOf(references);
        iterationGroup = iterationGroup == null ? Optional.empty() : iterationGroup;
    }

    /** An operator the condition uses: catalog id, EN keyword, kind, meaning, and relevant gotchas. */
    public record Operator(String id, String keyword, String kind, String meaning, List<String> gotchas) {
        public Operator {
            gotchas = gotchas == null ? List.of() : List.copyOf(gotchas);
        }
    }

    /** A field/group the condition references: its path and model kind ({@code "GROUP"} for groups). */
    public record Reference(String path, String kind) {
    }

    /** The explanation as agent-parseable JSON (files-as-API). */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("text", text);
        ArrayNode ops = root.putArray("operators");
        for (Operator o : operators) {
            ObjectNode node = ops.addObject();
            node.put("id", o.id());
            node.put("keyword", o.keyword());
            node.put("kind", o.kind());
            node.put("meaning", o.meaning());
            if (!o.gotchas().isEmpty()) {
                ArrayNode g = node.putArray("gotchas");
                o.gotchas().forEach(g::add);
            }
        }
        ArrayNode refs = root.putArray("references");
        for (Reference r : references) {
            ObjectNode node = refs.addObject();
            node.put("path", r.path());
            node.put("kind", r.kind());
        }
        root.put("iterates", iterates);
        iterationGroup.ifPresent(g -> root.put("iterationGroup", g));
        root.put("gloss", gloss);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render the explanation as JSON", e);
        }
    }
}
