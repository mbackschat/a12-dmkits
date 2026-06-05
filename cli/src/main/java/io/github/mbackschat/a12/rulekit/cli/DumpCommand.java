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
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code dump} verb (CLI-SPEC §5) — renders the model-context surface as the finding-13
 * artifacts. Serialization lives here in {@code :cli}, never in core: core exposes the programmatic
 * projections ({@code BoundModel.fields()/groups()}), the CLI turns them into JSONL/markdown files.
 */
@Command(name = "dump", mixinStandardHelpOptions = true,
        description = "Render the model as the finding-13 artifacts (fields/groups/rules JSONL + model card).")
final class DumpCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model file")
    String model;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<artifact>",
            description = "one of: fields | groups | rules | card (default: card). Ignored with --output.")
    String artifactArg;

    @Option(names = {"--output", "-o"}, paramLabel = "<dir>",
            description = "write all four artifacts into this directory instead of printing one")
    String outputDir;

    @Override
    public Integer call() {
        Artifact only = null;
        if (artifactArg != null) {
            Optional<Artifact> artifact = Artifact.fromArg(artifactArg);
            if (artifact.isEmpty()) {
                return fail("unknown artifact: " + artifactArg + " (want fields|groups|rules|card)");
            }
            only = artifact.get();
        }
        try {
            String json = Files.readString(Path.of(model));
            BoundModel bound = BoundModel.ofJson(json);
            if (outputDir != null) {
                Path dir = Path.of(outputDir);
                Files.createDirectories(dir);
                for (Artifact a : Artifact.values()) {
                    Files.writeString(dir.resolve(a.fileName), render(a, bound, json));
                }
                return 0;
            }
            out().print(render(only == null ? Artifact.MODEL_CARD : only, bound, json));
            return 0;
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        } catch (RuntimeException e) {
            return fail("dump failed: " + e.getMessage());
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
                n.putNull("referencedFields");
                n.put("resolution", "heuristic");
                sb.append(line(n));
            } else if ("Group".equals(type)) {
                collectRules(node.path("Group").path("elements"), node.path("id").asText(), sb);
            }
        }
    }

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

    private static String line(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize JSONL line", e);
        }
    }

    private static JsonNode textOrNull(Optional<String> value) {
        return value.<JsonNode>map(MAPPER.getNodeFactory()::textNode)
                .orElseGet(MAPPER.getNodeFactory()::nullNode);
    }
}
