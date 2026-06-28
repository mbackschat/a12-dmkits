package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Document Model → JSON Schema, the spike's export direction (SCHEMAKIT-SPEC §6) — the mirror of
 * {@link JsonSchemaImporter}. It reads structure through modelkit's kernel-free {@link ModelDocument} (no
 * kernel, no {@code :adapter}): the single root group → the top-level object, nested groups → object
 * properties, repeatable groups → {@code array} properties, and an a12 typeDefinition → a {@code $defs}
 * entry referenced by {@code $ref}. {@code required} is emitted sorted so the round-trip is order-insensitive.
 */
public final class JsonSchemaExporter {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record ExportResult(String jsonSchema, TranscodeReport report) {}

    /** Per-export state: the document, the field→typeDef map, the typeDefs actually referenced, the report. */
    private record Ctx(ModelDocument doc, Map<String, String> fieldToTypedef,
                       TreeSet<String> usedDefs, TranscodeReport.Builder report) {}

    public ExportResult export(String dmJson) {
        ModelDocument doc = ModelDocument.read(dmJson);
        List<String> groupPaths = doc.groupPaths();
        if (groupPaths.isEmpty()) {
            throw new IllegalArgumentException("model has no group to export");
        }
        Ctx ctx = new Ctx(doc, fieldTypedefMap(doc), new TreeSet<>(), TranscodeReport.builder());
        ObjectNode schema = exportGroup(groupPaths.get(0), ctx);   // root group → top-level object
        emitDefs(schema, ctx);
        Carriage.collect(parse(dmJson), doc.groupPaths(), schema, ctx.report());  // rules/computations → x-a12-* (Tier B/C)
        TierAProjection.project(dmJson, schema, ctx.report());                    // recognized rules → native keywords (Tier A)
        return new ExportResult(toJson(schema), ctx.report().build());
    }

    /** Build {@code {type:object, properties, required}} from a group's direct field + sub-group children. */
    private ObjectNode exportGroup(String groupPath, Ctx ctx) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "object");
        ObjectNode properties = node.putObject("properties");
        TreeSet<String> required = new TreeSet<>();

        for (String fieldPath : ctx.doc().fieldPaths()) {
            if (!isDirectChild(fieldPath, groupPath)) {
                continue;
            }
            String name = lastSegment(fieldPath);
            exportField(fieldPath, name, properties, ctx);
            if (ctx.doc().requiredness(fieldPath).orElse(Requiredness.OPTIONAL) != Requiredness.OPTIONAL) {
                required.add(name);
            }
        }

        for (String childPath : ctx.doc().groupPaths()) {
            if (!isDirectChild(childPath, groupPath)) {
                continue;
            }
            String name = lastSegment(childPath);
            ObjectNode childObject = exportGroup(childPath, ctx);
            if (ctx.doc().isRepeating(childPath)) {
                int max = ctx.doc().maxRepetitions(childPath);
                ObjectNode arr = properties.putObject(name);
                arr.put("type", "array");
                arr.set("items", childObject);
                arr.put("maxItems", max);
                ctx.report().structure(childPath, "properties/" + name + " (array, maxItems " + max + ")");
            } else {
                properties.set(name, childObject);
                ctx.report().structure(childPath, "properties/" + name + " (object)");
            }
        }

        if (!required.isEmpty()) {
            ArrayNode req = node.putArray("required");
            required.forEach(req::add);
        }
        return node;
    }

    private void exportField(String fieldPath, String name, ObjectNode properties, Ctx ctx) {
        String typeDefId = ctx.fieldToTypedef().get(fieldPath);
        if (typeDefId != null) {
            properties.putObject(name).put("$ref", "#/$defs/" + typeDefId);
            ctx.usedDefs().add(typeDefId);
            ctx.report().structure(fieldPath, "properties/" + name + " ($ref → " + typeDefId + ")");
            return;
        }
        String jsonType = writeLeafType(properties.putObject(name),
                ctx.doc().fieldKind(fieldPath), ctx.doc().fieldConfig(fieldPath), ctx.report(), fieldPath);
        ctx.report().structure(fieldPath, "properties/" + name + " (" + jsonType + ")");
    }

    private void emitDefs(ObjectNode schema, Ctx ctx) {
        if (ctx.usedDefs().isEmpty()) {
            return;
        }
        ObjectNode defs = JSON.createObjectNode();
        for (String id : ctx.usedDefs()) {
            writeLeafType(defs.putObject(id),
                    ctx.doc().typeDefinitionKind(id), ctx.doc().typeDefinitionConfig(id), ctx.report(), "$defs/" + id);
        }
        schema.set("$defs", defs);
    }

    /** Write a leaf type ({@code string}/{@code integer}/{@code number}/{@code boolean}) onto {@code target}. */
    private static String writeLeafType(ObjectNode target, Optional<String> kindOpt,
                                        Optional<ObjectNode> configOpt, TranscodeReport.Builder report, String source) {
        String kind = kindOpt.orElse("UNKNOWN");
        switch (kind) {
            case "STRING" -> {
                target.put("type", "string");
                configOpt.ifPresent(c -> {
                    copyInt(c, "minLength", target, "minLength");
                    copyInt(c, "maxLength", target, "maxLength");
                    JsonNode pattern = c.findValue("pattern");
                    if (pattern != null && pattern.isTextual()) {
                        target.put("pattern", pattern.asText());
                    }
                });
                return "string";
            }
            case "BOOLEAN" -> {
                target.put("type", "boolean");
                return "boolean";
            }
            case "NUMBER" -> {
                boolean integer = configOpt
                        .map(c -> c.findValue("maxFractionalDigits"))
                        .map(n -> n.isInt() && n.asInt() == 0)
                        .orElse(false);
                target.put("type", integer ? "integer" : "number");
                configOpt.ifPresent(c -> {
                    copyNumber(c, "min", target, "minimum");   // the read-back number block carries min/max as text
                    copyNumber(c, "max", target, "maximum");
                });
                return integer ? "integer" : "number";
            }
            default -> {
                target.put("type", "string");
                report.diagnostic("kind '" + kind + "' at '" + source + "' is not mapped yet — string placeholder");
                return "string(fallback)";
            }
        }
    }

    private static Map<String, String> fieldTypedefMap(ModelDocument doc) {
        Map<String, String> map = new HashMap<>();
        for (String id : doc.typeDefinitionIds()) {
            for (String fieldPath : doc.fieldsUsingTypeDefinition(id)) {
                map.put(fieldPath, id);
            }
        }
        return map;
    }

    private static void copyInt(JsonNode config, String fromKey, ObjectNode target, String toKey) {
        JsonNode v = config.findValue(fromKey);
        if (v != null && v.isInt()) {
            target.put(toKey, v.asInt());
        }
    }

    private static void copyNumber(JsonNode config, String fromKey, ObjectNode target, String toKey) {
        JsonNode v = config.findValue(fromKey);
        if (v == null || v.isNull()) {
            return;
        }
        String text = v.asText("");
        if (text.isEmpty()) {
            return;
        }
        try {
            target.put(toKey, new java.math.BigDecimal(text));   // the bound is stored as text → emit as a number
        } catch (NumberFormatException ignored) {
            // a non-numeric bound is not a JSON Schema minimum/maximum — leave it out
        }
    }

    private static boolean isDirectChild(String path, String groupPath) {
        String prefix = groupPath + "/";
        return path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static JsonNode parse(String dmJson) {
        try {
            return JSON.readTree(dmJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid DM-JSON: " + e.getMessage(), e);
        }
    }

    private static String toJson(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize the exported schema", e);
        }
    }
}
