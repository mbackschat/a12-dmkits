package io.github.mbackschat.a12.dm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders a {@link ResultEnvelope} JSON string as human-readable text for the {@code --text} flag (R2,
 * CLI-SPEC §2 — the secondary direct-BA/dev path). It is a <b>boundary view-transform</b>
 * applied once in {@link Cli#run}: it re-parses the just-serialized envelope rather than threading a
 * formatter through every emit site, and it is <b>gated</b> — anything that is not a result envelope (the
 * {@code operators}/{@code manifest}/… catalogs, {@code export} artifacts, {@code --help}, errors) passes
 * through <b>unchanged</b>. JSON stays the default and the machine contract.
 */
final class TextRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TextRenderer() {
    }

    /** If {@code raw} is a result envelope, render it as human text; otherwise return it unchanged. */
    static String renderIfEnvelope(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        JsonNode env;
        try {
            env = MAPPER.readTree(raw);
        } catch (Exception e) {
            return raw; // not JSON (--help text, a usage error) → pass through
        }
        if (!env.isObject() || !env.path("outcome").isTextual()) {
            return raw; // a catalog/artifact payload, not the result envelope → pass through
        }
        StringBuilder sb = new StringBuilder();
        sb.append(env.path("summary").asText("")).append('\n');
        sb.append("  (").append(env.path("outcome").asText());
        if (env.has("valid")) {
            sb.append(env.get("valid").asBoolean() ? " · valid" : " · INVALID");
        }
        if (env.path("written").asBoolean(false)) {
            sb.append(" · written");
        }
        if (env.path("output").isTextual()) {
            sb.append(" → ").append(env.get("output").asText());
        }
        sb.append(")\n");
        appendSection(sb, "diagnostics", env.get("diagnostics"));
        appendSection(sb, "changed", env.get("changed"));
        appendSection(sb, "data", env.get("data"));
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String label, JsonNode node) {
        boolean empty = node == null || node.isNull()
                || (node.isArray() && node.isEmpty()) || (node.isObject() && node.isEmpty());
        if (empty) {
            return;
        }
        sb.append('\n').append(label).append(":\n");
        tree(node, 2, sb);
    }

    /** Render any JSON node as an indented, brace-free tree — generic, so it covers every {@code data} shape. */
    private static void tree(JsonNode n, int indent, StringBuilder sb) {
        String pad = " ".repeat(indent);
        if (n.isObject()) {
            var fields = n.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                JsonNode v = e.getValue();
                if (v.isValueNode()) {
                    sb.append(pad).append(e.getKey()).append(": ").append(v.asText()).append('\n');
                } else {
                    sb.append(pad).append(e.getKey()).append(":\n");
                    tree(v, indent + 2, sb);
                }
            }
        } else if (n.isArray()) {
            for (JsonNode e : n) {
                if (e.isValueNode()) {
                    sb.append(pad).append("- ").append(e.asText()).append('\n');
                } else {
                    sb.append(pad).append("-\n");
                    tree(e, indent + 4, sb);
                }
            }
        } else {
            sb.append(pad).append(n.asText()).append('\n');
        }
    }
}
