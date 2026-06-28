package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Document Model → JSON Schema, the spike's export direction (SCHEMAKIT-SPEC §6, shape 1: the single root
 * group → a flat top-level object). It reads structure through modelkit's kernel-free {@link ModelDocument}
 * (no kernel, no {@code :adapter}) and emits a Jackson tree. The mirror of {@link JsonSchemaImporter}:
 * {@code import → export} round-trips on {@code {type, properties, required}}. {@code required} is emitted
 * sorted so the round-trip is order-insensitive.
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
        String rootGroup = groupPaths.get(0);   // shape 1: a single root group

        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        TreeSet<String> required = new TreeSet<>();
        List<TranscodeReport.Disposition> dispositions = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (String fieldPath : doc.fieldPaths()) {
            if (!isDirectChild(fieldPath, rootGroup)) {
                continue;   // shape 1: top-level fields only
            }
            String name = lastSegment(fieldPath);
            String kind = doc.fieldKind(fieldPath).orElse("UNKNOWN");
            ObjectNode prop = properties.putObject(name);
            String jsonType = mapKind(kind, doc, fieldPath, prop, diagnostics);
            dispositions.add(new TranscodeReport.Disposition(
                    fieldPath, "properties/" + name + " (" + jsonType + ")", TranscodeReport.Tier.STRUCTURE));
            if (doc.requiredness(fieldPath).orElse(Requiredness.OPTIONAL) != Requiredness.OPTIONAL) {
                required.add(name);
            }
        }
        if (!required.isEmpty()) {
            ArrayNode req = schema.putArray("required");
            required.forEach(req::add);
        }
        return new ExportResult(toJson(schema), new TranscodeReport(dispositions, diagnostics));
    }

    private static String mapKind(String kind, ModelDocument doc, String fieldPath, ObjectNode prop,
                                  List<String> diag) {
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
                diag.add("field kind '" + kind + "' on '" + fieldPath
                        + "' is not mapped in shape 1 — emitted as a string placeholder");
                return "string(fallback)";
            }
        }
    }

    private static boolean isDirectChild(String fieldPath, String groupPath) {
        String prefix = groupPath + "/";
        return fieldPath.startsWith(prefix) && fieldPath.indexOf('/', prefix.length()) < 0;
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
