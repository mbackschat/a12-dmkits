package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A reviewable before/after of a modify-by-re-express (F8 #2): the old and new rendered condition
 * text, whether they differ, and — per the diff contract (study finding 8) — any <b>lossiness</b>
 * disclosed up front (e.g. comments/formatting in a foreign source that re-rendering can't preserve).
 * The point is that a human or agent approves the textual change <em>and</em> sees what falls away,
 * before anything is written back.
 */
public record RenderedDiff(String before, String after, boolean changed, List<String> lossiness) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public RenderedDiff {
        lossiness = lossiness == null ? List.of() : List.copyOf(lossiness);
    }

    /** Diff two conditions by their canonical (EN) rendering — symmetric AST-to-AST, no lossiness. */
    public static RenderedDiff between(Condition before, Condition after) {
        DslRenderer renderer = new DslRenderer(ConditionLanguage.EN);
        return between(renderer.render(before), renderer.render(after), List.of());
    }

    /** Diff two rendered texts, attaching any disclosed lossiness notes. */
    public static RenderedDiff between(String before, String after, List<String> lossiness) {
        return new RenderedDiff(before, after, !before.equals(after), lossiness);
    }

    /** A readable unified-style view: {@code - old} / {@code + new}, with {@code ! note} lossiness lines. */
    public String unified() {
        StringBuilder sb = new StringBuilder();
        if (changed) {
            sb.append("- ").append(before).append('\n').append("+ ").append(after);
        } else {
            sb.append("  ").append(before);
        }
        for (String note : lossiness) {
            sb.append('\n').append("! ").append(note);
        }
        return sb.toString();
    }

    /** The diff as agent-parseable JSON (files-as-API). */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("before", before);
        root.put("after", after);
        root.put("changed", changed);
        ArrayNode notes = root.putArray("lossiness");
        lossiness.forEach(notes::add);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render the diff as JSON", e);
        }
    }
}
