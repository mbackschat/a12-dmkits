package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.List;
import java.util.TreeSet;

/**
 * Document Model → JSON Schema, the spike's export direction (SCHEMAKIT-SPEC §6). It reads structure
 * through modelkit's kernel-free {@link ModelDocument} (no kernel, no {@code :adapter}) and emits a Jackson
 * tree. The mirror of {@link JsonSchemaImporter}: the single root group → the top-level object, nested
 * groups → object properties, repeatable groups → {@code array} properties ({@code maxItems} = the
 * repetition bound). {@code required} is emitted sorted so the round-trip is order-insensitive.
 */
public final class JsonSchemaExporter {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record ExportResult(String jsonSchema, TranscodeReport report) {}

    public ExportResult export(String dmJson) {
        ModelDocument doc = ModelDocument.read(dmJson);
        List<String> groupPaths = doc.groupPaths();
        if (groupPaths.isEmpty()) {
            throw new IllegalArgumentException("model has no group to export");
        }
        TranscodeReport.Builder report = TranscodeReport.builder();
        ObjectNode schema = exportGroup(groupPaths.get(0), doc, report);   // root group → top-level object
        return new ExportResult(toJson(schema), report.build());
    }

    /** Build {@code {type:object, properties, required}} from a group's direct field + sub-group children. */
    private ObjectNode exportGroup(String groupPath, ModelDocument doc, TranscodeReport.Builder report) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "object");
        ObjectNode properties = node.putObject("properties");
        TreeSet<String> required = new TreeSet<>();

        for (String fieldPath : doc.fieldPaths()) {
            if (!isDirectChild(fieldPath, groupPath)) {
                continue;
            }
            String name = lastSegment(fieldPath);
            String jsonType = mapKind(fieldPath, doc, properties.putObject(name), report);
            report.structure(fieldPath, "properties/" + name + " (" + jsonType + ")");
            if (doc.requiredness(fieldPath).orElse(Requiredness.OPTIONAL) != Requiredness.OPTIONAL) {
                required.add(name);
            }
        }

        for (String childPath : doc.groupPaths()) {
            if (!isDirectChild(childPath, groupPath)) {
                continue;
            }
            String name = lastSegment(childPath);
            ObjectNode childObject = exportGroup(childPath, doc, report);
            if (doc.isRepeating(childPath)) {
                ObjectNode arr = properties.putObject(name);
                arr.put("type", "array");
                arr.set("items", childObject);
                arr.put("maxItems", doc.maxRepetitions(childPath));
                report.structure(childPath, "properties/" + name + " (array, maxItems " + doc.maxRepetitions(childPath) + ")");
            } else {
                properties.set(name, childObject);
                report.structure(childPath, "properties/" + name + " (object)");
            }
        }

        if (!required.isEmpty()) {
            ArrayNode req = node.putArray("required");
            required.forEach(req::add);
        }
        return node;
    }

    private static String mapKind(String fieldPath, ModelDocument doc, ObjectNode prop, TranscodeReport.Builder report) {
        String kind = doc.fieldKind(fieldPath).orElse("UNKNOWN");
        switch (kind) {
            case "STRING" -> {
                prop.put("type", "string");
                return "string";
            }
            case "BOOLEAN" -> {
                prop.put("type", "boolean");
                return "boolean";
            }
            case "NUMBER" -> {
                boolean integer = doc.fieldConfig(fieldPath)
                        .map(c -> c.findValue("maxFractionalDigits"))
                        .map(n -> n.isInt() && n.asInt() == 0)
                        .orElse(false);
                prop.put("type", integer ? "integer" : "number");
                return integer ? "integer" : "number";
            }
            default -> {
                prop.put("type", "string");
                report.diagnostic("field kind '" + kind + "' on '" + fieldPath
                        + "' is not mapped yet — emitted as a string placeholder");
                return "string(fallback)";
            }
        }
    }

    private static boolean isDirectChild(String path, String groupPath) {
        String prefix = groupPath + "/";
        return path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String toJson(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize the exported schema", e);
        }
    }
}
