package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Document Model → JSON Schema, the export direction (SCHEMAKIT-SPEC §6) — the mirror of
 * {@link JsonSchemaImporter}. It reads structure through modelkit's kernel-free {@link ModelDocument} (no
 * kernel, no {@code :adapter}): the single root group → the top-level object, nested groups → object
 * properties, repeatable groups → {@code array} properties, and an a12 typeDefinition → a {@code $defs}
 * entry referenced by {@code $ref}. {@code required} is emitted sorted so the round-trip is order-insensitive.
 */
public final class JsonSchemaExporter {

    private static final ObjectMapper JSON = JsonMapper.builder()
            // Export parses the source DM-JSON for lossless rule/computation carriage. It is therefore an A12
            // model boundary even though its output is a JSON Schema (API-FINDINGS 103 / IF228).
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    public record ExportResult(String jsonSchema, TranscodeReport report) {}

    /** Per-export state: the document, the field→typeDef map, the typeDefs actually referenced, the report, and
     *  the {@code $ref} prefix (dialect/wrap-dependent: {@code #/$defs/} bare vs {@code #/components/schemas/} wrapped). */
    private record Ctx(ModelDocument doc, Map<String, String> fieldToTypedef,
                       TreeSet<String> usedDefs, TranscodeReport.Builder report, String refPrefix) {}

    public ExportResult export(String dmJson) {
        return export(dmJson, Dialect.JSON_SCHEMA, false);
    }

    /**
     * Export in the chosen wire {@code dialect}; when {@code wrapOpenApi}, wrap the Schema Object in a minimal
     * OpenAPI document under {@code components/schemas} (refs → {@code #/components/schemas/…}), else emit a bare
     * Schema Object with its typeDefinitions under {@code $defs} (SCHEMAKIT-SPEC §4g). The {@code x-a12-*} carriage
     * is dialect-agnostic — it is a valid OpenAPI extension in every version.
     */
    public ExportResult export(String dmJson, Dialect dialect, boolean wrapOpenApi) {
        ModelDocument doc = ModelDocument.read(dmJson);
        List<String> groupPaths = doc.groupPaths();
        if (groupPaths.isEmpty()) {
            throw new IllegalArgumentException("model has no group to export");
        }
        String refPrefix = wrapOpenApi ? "#/components/schemas/" : "#/$defs/";
        Ctx ctx = new Ctx(doc, fieldTypedefMap(doc), new TreeSet<>(), TranscodeReport.builder(), refPrefix);
        ObjectNode schema = exportGroup(groupPaths.get(0), ctx);   // root group → top-level object
        Carriage.collect(parse(dmJson), doc.groupPaths(), schema, ctx.report());  // rules/computations → x-a12-* (Tier B/C)
        TierAProjection.project(dmJson, schema, ctx.report());                    // recognized rules → native keywords (Tier A)
        if (wrapOpenApi) {
            return new ExportResult(toJson(wrapOpenApi(schema, dialect, ctx)), ctx.report().build());
        }
        emitDefs(schema, ctx);
        if (dialect == Dialect.OPENAPI_30 && !ctx.usedDefs().isEmpty()) {
            ctx.report().diagnostic("OpenAPI 3.0 has no $defs — the typeDefinitions are emitted as $defs (valid in "
                    + "JSON Schema / OpenAPI 3.1). Use --wrap-openapi to emit them under components/schemas instead");
        }
        return new ExportResult(toJson(schema), ctx.report().build());
    }

    /** Wrap the root Schema Object in a minimal, valid OpenAPI document: the model under {@code components/schemas}
     *  by its id, its typeDefinitions alongside, and an empty {@code paths} (required by 3.0, allowed by 3.1). */
    private ObjectNode wrapOpenApi(ObjectNode rootSchema, Dialect dialect, Ctx ctx) {
        ObjectNode root = JSON.createObjectNode();
        root.put("openapi", dialect == Dialect.OPENAPI_30 ? "3.0.3" : "3.1.0");
        ObjectNode info = root.putObject("info");
        info.put("title", ctx.doc().id());
        info.put("version", "1.0.0");
        root.putObject("paths");
        ObjectNode schemas = root.putObject("components").putObject("schemas");
        schemas.set(ctx.doc().id(), rootSchema);
        for (String id : ctx.usedDefs()) {
            writeLeaf(schemas.putObject(id), leafOfTypeDef(ctx.doc(), id, ctx.report()));
        }
        return root;
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
            properties.putObject(name).put("$ref", ctx.refPrefix() + typeDefId);
            ctx.usedDefs().add(typeDefId);
            ctx.report().structure(fieldPath, "properties/" + name + " ($ref → " + typeDefId + ")");
            return;
        }
        if ("ENUMERATION".equals(ctx.doc().fieldKind(fieldPath).orElse(""))) {
            ObjectNode prop = properties.putObject(name);
            prop.put("type", "string");
            ArrayNode en = prop.putArray("enum");
            ctx.doc().enumValues(fieldPath).forEach(en::add);   // typed read-back (modelkit), no raw-tree dig
            ctx.report().structure(fieldPath, "properties/" + name + " (enum)");
            return;
        }
        LeafSchema leaf = leafOfField(ctx.doc(), fieldPath, ctx.report());
        writeLeaf(properties.putObject(name), leaf);
        ctx.report().structure(fieldPath, "properties/" + name + " (" + leaf.jsonType() + ")");
    }

    private void emitDefs(ObjectNode schema, Ctx ctx) {
        if (ctx.usedDefs().isEmpty()) {
            return;
        }
        ObjectNode defs = JSON.createObjectNode();
        for (String id : ctx.usedDefs()) {
            writeLeaf(defs.putObject(id), leafOfTypeDef(ctx.doc(), id, ctx.report()));
        }
        schema.set("$defs", defs);
    }

    /** A leaf JSON-Schema type and its constraints — the read-back of a field's or typedef's declared config. */
    private record LeafSchema(String jsonType, Optional<String> pattern, OptionalInt minLength,
                              OptionalInt maxLength, Optional<BigDecimal> minimum, Optional<BigDecimal> maximum) {}

    /** Emit a leaf type ({@code string}/{@code integer}/{@code number}/{@code boolean}) + its constraints. */
    private static void writeLeaf(ObjectNode target, LeafSchema leaf) {
        target.put("type", leaf.jsonType());
        leaf.pattern().ifPresent(p -> target.put("pattern", p));
        leaf.minLength().ifPresent(n -> target.put("minLength", n));
        leaf.maxLength().ifPresent(n -> target.put("maxLength", n));
        leaf.minimum().ifPresent(v -> target.put("minimum", v));
        leaf.maximum().ifPresent(v -> target.put("maximum", v));
    }

    /** A field's leaf type, read through the typed {@link ModelDocument} accessors (no raw config navigation). */
    private static LeafSchema leafOfField(ModelDocument doc, String fieldPath, TranscodeReport.Builder report) {
        return new LeafSchema(
                jsonTypeFor(doc.fieldKind(fieldPath).orElse("UNKNOWN"), doc.fieldMaxFractionalDigits(fieldPath), report, fieldPath),
                doc.fieldPattern(fieldPath), doc.fieldMinLength(fieldPath), doc.fieldMaxLength(fieldPath),
                doc.fieldMinValue(fieldPath), doc.fieldMaxValue(fieldPath));
    }

    /** A leaf typeDefinition's type, read through the typed typedef accessors (the peers of the field ones). */
    private static LeafSchema leafOfTypeDef(ModelDocument doc, String id, TranscodeReport.Builder report) {
        return new LeafSchema(
                jsonTypeFor(doc.typeDefinitionKind(id).orElse("UNKNOWN"), doc.typeDefinitionMaxFractionalDigits(id), report, "$defs/" + id),
                doc.typeDefinitionPattern(id), doc.typeDefinitionMinLength(id), doc.typeDefinitionMaxLength(id),
                doc.typeDefinitionMinValue(id), doc.typeDefinitionMaxValue(id));
    }

    /** Map an a12 value kind to its JSON-Schema {@code type}; a NUMBER is {@code integer} iff its scale is 0. */
    private static String jsonTypeFor(String kind, OptionalInt scale, TranscodeReport.Builder report, String source) {
        return switch (kind) {
            case "STRING" -> "string";
            case "BOOLEAN" -> "boolean";
            case "NUMBER" -> scale.orElse(-1) == 0 ? "integer" : "number";
            // ENUMERATION is handled in exportField via ModelDocument.enumValues (the typed read-back).
            default -> {
                report.diagnostic("kind '" + kind + "' at '" + source + "' is not mapped yet — string placeholder");
                yield "string";
            }
        };
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
