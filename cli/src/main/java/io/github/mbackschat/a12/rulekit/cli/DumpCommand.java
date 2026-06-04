package io.github.mbackschat.a12.rulekit.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.rulekit.model.ValueKind;

/**
 * The {@code dump} verb (CLI-SPEC §5) — renders the model-context surface as the finding-13
 * artifacts. Serialization lives here in {@code :cli}, never in core: core exposes the programmatic
 * projections ({@code BoundModel.fields()/groups()}), the CLI turns them into JSONL/markdown files.
 *
 * <p>Usage: {@code dump <model.json> [fields|groups|rules|card]} prints one artifact to stdout;
 * {@code dump <model.json> --output <dir>} writes all four; bare {@code dump <model.json>} prints
 * the model card.
 */
final class DumpCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DumpCommand() {
    }

    static int run(String[] args, Appendable out, Appendable err) {
        if (args.length == 0) {
            return fail(err, "usage: dump <model.json> [fields|groups|rules|card] | --output <dir>");
        }
        Path modelPath = Path.of(args[0]);
        String outputDir = null;
        Artifact only = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--output".equals(a) || "-o".equals(a)) {
                if (i + 1 >= args.length) {
                    return fail(err, "--output needs a directory");
                }
                outputDir = args[++i];
            } else {
                Optional<Artifact> artifact = Artifact.fromArg(a);
                if (artifact.isEmpty()) {
                    return fail(err, "unknown artifact: " + a + " (want fields|groups|rules|card)");
                }
                only = artifact.get();
            }
        }

        try {
            String json = Files.readString(modelPath);
            BoundModel model = BoundModel.ofJson(json);

            if (outputDir != null) {
                Path dir = Path.of(outputDir);
                Files.createDirectories(dir);
                for (Artifact a : Artifact.values()) {
                    Files.writeString(dir.resolve(a.fileName), render(a, model, json));
                }
                return 0;
            }
            append(out, render(only == null ? Artifact.MODEL_CARD : only, model, json));
            return 0;
        } catch (IOException e) {
            return fail(err, "cannot read model: " + e.getMessage());
        } catch (RuntimeException e) {
            return fail(err, "dump failed: " + e.getMessage());
        }
    }

    private static String render(Artifact artifact, BoundModel model, String json) {
        return switch (artifact) {
            case FIELDS -> fieldsJsonl(model);
            case GROUPS -> groupsJsonl(model);
            case RULES -> rulesJsonl(json);
            case MODEL_CARD -> modelCard(model, json);
        };
    }

    // ---- JSONL projections (one self-contained JSON object per line) --------------------------

    private static String fieldsJsonl(BoundModel model) {
        StringBuilder sb = new StringBuilder();
        model.fields().forEach(f -> sb.append(line(fieldNode(f, model))));
        return sb.toString();
    }

    private static ObjectNode fieldNode(FieldInfo f, BoundModel model) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("path", f.path());
        n.put("name", f.name());
        n.put("kind", f.kind().name());
        n.put("group", f.groupPath());
        n.set("repeatsUnder", textOrNull(model.nearestRepeatableAncestor(f.path())));
        if (f.kind() == ValueKind.NUMBER) {
            n.put("scale", f.scale());
        }
        if (f.kind() == ValueKind.ENUMERATION) {
            var values = n.putArray("values");
            f.asEnum().allowedValues().forEach(values::add);
        }
        return n;
    }

    private static String groupsJsonl(BoundModel model) {
        StringBuilder sb = new StringBuilder();
        model.groups().forEach(g -> sb.append(line(groupNode(g, model))));
        return sb.toString();
    }

    private static ObjectNode groupNode(GroupInfo g, BoundModel model) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("path", g.path());
        n.put("name", g.name());
        n.put("repeatable", g.repeating());
        n.set("repeatsUnder", textOrNull(model.nearestRepeatableAncestor(g.path())));
        return n;
    }

    private static String rulesJsonl(String json) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode rootGroups = MAPPER.readTree(json)
                    .path("content").path("modelRoot").path("rootGroups");
            collectRules(rootGroups, "", sb);
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse model DM-JSON for rules", e);
        }
        return sb.toString();
    }

    private static void collectRules(JsonNode elements, String enclosingGroup, StringBuilder sb) {
        if (!elements.isArray()) {
            return;
        }
        for (JsonNode node : elements) {
            String type = node.path("type").asText();
            if ("Rule".equals(type)) {
                JsonNode body = node.path("Rule");
                ObjectNode n = MAPPER.createObjectNode();
                n.put("rule", node.path("id").asText());
                n.put("group", enclosingGroup);
                n.put("slot", "errorCondition");
                n.put("code", body.path("errorCode").asText());
                n.put("errorField", body.path("errorEntityRelPath").asText());
                n.put("severity", body.path("severity").asText());
                n.put("condition", body.path("errorCondition").asText());
                n.putNull("referencedFields"); // raw text; resolving refs needs the adapter's parse
                n.put("resolution", "heuristic");
                sb.append(line(n));
            } else if ("Group".equals(type)) {
                collectRules(node.path("Group").path("elements"), node.path("id").asText(), sb);
            }
        }
    }

    // ---- model-card.md (the read-whole skeleton, stamped) -------------------------------------

    private static String modelCard(BoundModel model, String json) {
        JsonNode header = readHeader(json);
        String id = header.path("id").asText("(unknown)");
        String version = header.path("modelVersion").asText("");

        var fields = model.fields().toList();
        var groups = model.groups().toList();
        long repeatable = groups.stream().filter(GroupInfo::repeating).count();
        long rules = rulesJsonl(json).lines().filter(l -> !l.isBlank()).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# Model: ").append(id);
        if (!version.isBlank()) {
            sb.append(" (v").append(version).append(')');
        }
        sb.append("\n\n");
        sb.append("- fields: ").append(fields.size()).append('\n');
        sb.append("- groups: ").append(groups.size()).append(" (").append(repeatable).append(" repeatable)\n");
        sb.append("- rules: ").append(rules).append("\n\n");

        sb.append("## Groups\n\n");
        groups.forEach(g -> sb.append("- ").append(g.path())
                .append(g.repeating() ? "  (repeatable)" : "").append('\n'));

        var enums = fields.stream().filter(f -> f.kind() == ValueKind.ENUMERATION).toList();
        if (!enums.isEmpty()) {
            sb.append("\n## Enums\n\n");
            enums.forEach(f -> sb.append("- ").append(f.path()).append(": ")
                    .append(String.join(", ", f.asEnum().allowedValues())).append('\n'));
        }
        return sb.toString();
    }

    private static JsonNode readHeader(String json) {
        try {
            return MAPPER.readTree(json).path("header");
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse model DM-JSON header", e);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String line(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node) + "\n"; // compact: one object per line
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize JSONL line", e);
        }
    }

    private static JsonNode textOrNull(Optional<String> value) {
        return value.<JsonNode>map(MAPPER.getNodeFactory()::textNode)
                .orElseGet(MAPPER.getNodeFactory()::nullNode);
    }

    private static void append(Appendable out, String text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write output", e);
        }
    }

    private static int fail(Appendable err, String message) {
        try {
            err.append(message).append('\n');
        } catch (IOException ignored) {
            // best-effort diagnostic
        }
        return 2;
    }
}
