package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The compact {@code --text} body for {@code model report} (MODEL-REVIEW-SPEC §4/§6): the comprehension
 * **catalog** — every rule and computation as a one-line gloss, then the dead-field set — instead of the
 * generic tree's full field dump. The counts + polarity ride the envelope headline, so this focuses on
 * the part a human actually reads: what each rule/computation *does*.
 */
final class ReportText implements VerbText {

    enum TreeLayoutStep {
        SIBLING_CLASSIFICATION,
        PREFIX_BUILD
    }

    private static final Consumer<TreeLayoutStep> NO_LAYOUT_PROBE = ignored -> {};

    private final Consumer<TreeLayoutStep> layoutProbe;

    ReportText() {
        this(NO_LAYOUT_PROBE);
    }

    ReportText(Consumer<TreeLayoutStep> layoutProbe) {
        this.layoutProbe = Objects.requireNonNull(layoutProbe, "layoutProbe");
    }

    @Override
    public String render(JsonNode data) {
        if (data.path("nodes").isArray()) {
            return renderTree(data);
        }
        StringBuilder sb = new StringBuilder();
        appendCatalog(sb, "rules", data.path("rules"), "rule");
        appendCatalog(sb, "computations", data.path("computations"), "computation");
        JsonNode unreferenced = data.path("unreferenced");
        if (unreferenced.isArray() && !unreferenced.isEmpty()) {
            sb.append("unreferenced (").append(unreferenced.size()).append("):\n");
            unreferenced.forEach(p -> sb.append("  ").append(p.asText()).append('\n'));
        }
        return sb.isEmpty() ? "(empty model)\n" : sb.toString();
    }

    private String renderTree(JsonNode data) {
        List<JsonNode> nodes = new ArrayList<>();
        data.path("nodes").forEach(nodes::add);
        if (nodes.isEmpty()) {
            return "(empty model)\n";
        }

        boolean[] lastSibling = lastSiblingFlags(nodes);
        List<Integer> ancestors = new ArrayList<>();

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            int depth = node.path("depth").asInt();
            while (ancestors.size() > depth) {
                ancestors.removeLast();
            }
            if (ancestors.size() != depth) {
                throw new IllegalArgumentException("tree node depth skips its parent at "
                        + node.path("path").asText());
            }

            String path = node.path("path").asText();
            String prefix = depth == 0 ? "" : prefixFor(ancestors, lastSibling);
            if (depth == 0) {
                out.append(path);
            } else {
                out.append(prefix)
                        .append(lastSibling[i] ? "└─ " : "├─ ")
                        .append(shortName(path));
            }
            out.append("  ").append(description(node)).append('\n');
            appendLogic(out, node, prefix, lastSibling[i]);
            ancestors.add(i);
        }

        JsonNode summary = data.path("summary");
        out.append("fields ").append(summary.path("fields").asInt())
                .append(" · groups ").append(summary.path("groups").asInt())
                .append(" · rules ").append(summary.path("rules").asInt())
                .append(" · computations ").append(summary.path("computations").asInt())
                .append(" · repeatable ").append(summary.path("repeatableGroups").asInt())
                .append('\n');
        return out.toString();
    }

    private boolean[] lastSiblingFlags(List<JsonNode> nodes) {
        boolean[] out = new boolean[nodes.size()];
        Arrays.fill(out, true);
        Map<String, Integer> previousChildByParent = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            layoutProbe.accept(TreeLayoutStep.SIBLING_CLASSIFICATION);
            JsonNode parentNode = nodes.get(i).path("parent");
            String parent = parentNode.isNull() ? null : parentNode.asText(null);
            Integer previousChild = previousChildByParent.put(parent, i);
            if (previousChild != null) {
                out[previousChild] = false;
            }
        }
        return out;
    }

    private String prefixFor(List<Integer> ancestors, boolean[] lastSibling) {
        layoutProbe.accept(TreeLayoutStep.PREFIX_BUILD);
        StringBuilder out = new StringBuilder(Math.max(0, ancestors.size() - 1) * 3);
        for (int depth = 1; depth < ancestors.size(); depth++) {
            out.append(lastSibling[ancestors.get(depth)] ? "   " : "│  ");
        }
        return out.toString();
    }

    private static String description(JsonNode node) {
        return switch (node.path("kind").asText()) {
            case "model" -> "(model)";
            case "group" -> {
                List<String> details = new ArrayList<>();
                details.add("group");
                if (node.path("repeatable").asBoolean()) {
                    details.add("repeatable");
                    details.add("max " + node.path("maxReps").asInt());
                }
                if (node.has("origin")) {
                    details.add("origin: " + node.path("origin").asText());
                }
                yield withFacets("(" + String.join(" · ", details) + ")", node);
            }
            case "field" -> {
                StringBuilder field = new StringBuilder(node.path("type").asText());
                if (node.has("scale")) {
                    field.append(" scale").append(node.path("scale").asInt());
                }
                if (node.has("minLength")) {
                    field.append(" min").append(node.path("minLength").asInt());
                }
                if (node.has("maxLength")) {
                    field.append(" max").append(node.path("maxLength").asInt());
                }
                if (node.has("min")) {
                    field.append(" min=").append(node.path("min").asText());
                }
                if (node.has("max")) {
                    field.append(" max=").append(node.path("max").asText());
                }
                if (node.has("pattern")) {
                    field.append(" pattern=\"").append(node.path("pattern").asText()).append('"');
                }
                if (node.has("values")) {
                    field.append(" {");
                    List<String> values = new ArrayList<>();
                    node.path("values").forEach(value -> values.add(value.asText()));
                    field.append(String.join(", ", values));
                    if (node.path("truncated").asBoolean()) {
                        field.append(", … (").append(node.path("valueCount").asInt()).append(" total)");
                    }
                    field.append('}');
                }
                node.path("categories").fields().forEachRemaining(category -> {
                    List<String> values = new ArrayList<>();
                    category.getValue().forEach(value -> values.add(value.asText()));
                    field.append(" →").append(category.getKey()).append('{')
                            .append(String.join(",", values)).append('}');
                });
                if (node.has("typeRef")) {
                    field.append(" typeRef:").append(node.path("typeRef").asText());
                }
                if (node.path("computed").asBoolean()) {
                    field.append(" computed");
                }
                yield withFacets(field.toString(), node);
            }
            default -> node.path("kind").asText();
        };
    }

    private static String withFacets(String base, JsonNode node) {
        StringBuilder out = new StringBuilder(base);
        node.path("annotations").fields().forEachRemaining(annotation ->
                out.append(" @").append(annotation.getKey()).append('=').append(annotation.getValue().asText()));
        JsonNode descriptions = node.path("descriptions");
        appendLocalized(out, "internal", descriptions.path("internal"));
        appendLocalized(out, "external", descriptions.path("external"));
        appendLocalized(out, "helper", descriptions.path("helper"));
        return out.toString();
    }

    private static void appendLocalized(StringBuilder out, String kind, JsonNode values) {
        values.fields().forEachRemaining(value -> out.append(' ').append(kind).append('[')
                .append(value.getKey()).append("]=\"").append(value.getValue().asText()).append('"'));
    }

    private static void appendLogic(StringBuilder out, JsonNode node, String prefix, boolean lastSibling) {
        List<String> lines = new ArrayList<>();
        for (JsonNode rule : node.path("rules")) {
            lines.add("rule " + shortName(rule.path("name").asText()) + " :  " + rule.path("dsl").asText());
        }
        if (node.has("computation")) {
            JsonNode computation = node.path("computation");
            lines.add("comp " + shortName(computation.path("name").asText()) + " :  "
                    + computation.path("dsl").asText());
        }
        if (lines.isEmpty()) {
            return;
        }
        String indent = node.path("depth").asInt() == 0
                ? ""
                : prefix + (lastSibling ? "   " : "│  ");
        for (int i = 0; i < lines.size(); i++) {
            out.append(indent).append(i == lines.size() - 1 ? "└─ " : "├─ ").append(lines.get(i)).append('\n');
        }
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static void appendCatalog(StringBuilder sb, String label, JsonNode entries, String idKey) {
        if (!entries.isArray() || entries.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        for (JsonNode e : entries) {
            sb.append("  ").append(e.path(idKey).asText()).append(" — ").append(e.path("gloss").asText()).append('\n');
        }
    }
}
