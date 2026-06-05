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
 *   <li>{@code tree} — the condition's <b>structure</b>: a walk of the sealed AST so the And/Or nesting
 *       and which operator wraps which is preserved (the flat {@code operators} list loses it). Each
 *       node is {@code {operator, text, children}} where {@code operator} is a <b>backlink</b> into
 *       {@code operators} (the meaning/gotchas live there once, not repeated per node) and {@code text}
 *       is the rendered DSL of that subtree. Operator-bearing operands (functions/aggregates) become
 *       child nodes; value leaves (fields/literals) stay in {@code text}. So {@code operators} is the
 *       glossary, {@code tree} is the shape — normalized, not duplicated.</li>
 *   <li>{@code gloss} — a one-line natural-language summary.</li>
 * </ul>
 */
public record RuleExplanation(
        String text,
        List<Operator> operators,
        List<Reference> references,
        boolean iterates,
        Optional<String> iterationGroup,
        Node tree,
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

    /**
     * One node of the structural {@code tree}. {@code operator} is the operator-id — a <b>backlink</b>
     * into the {@code operators} glossary (where its keyword/kind/meaning/gotchas live, once);
     * {@code text} is the rendered DSL of this subtree. {@code children} are the operands that
     * themselves carry an operator — {@code LogicCondition}'s two sides, a comparison's
     * function/aggregate operands — in source order; value operands (fields, literals) are shown in
     * {@code text} rather than as nodes.
     */
    public record Node(String operator, String text, List<Node> children) {
        public Node {
            children = children == null ? List.of() : List.copyOf(children);
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
        if (tree != null) {
            root.set("tree", treeJson(tree));
        }
        root.put("gloss", gloss);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render the explanation as JSON", e);
        }
    }

    private static ObjectNode treeJson(Node n) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("operator", n.operator());
        o.put("text", n.text());
        if (!n.children().isEmpty()) {
            ArrayNode c = o.putArray("children");
            n.children().forEach(child -> c.add(treeJson(child)));
        }
        return o;
    }
}
