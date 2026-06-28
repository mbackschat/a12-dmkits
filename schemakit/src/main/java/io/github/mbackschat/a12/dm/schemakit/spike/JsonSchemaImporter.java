package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema → Document Model, the spike's import direction (SCHEMAKIT-SPEC §6). It authors through the
 * typed surface (the dogfood rule) — modelkit's {@link DocumentModelDraft} — so the emitted DM-JSON is
 * kernel-valid by construction, and it touches the kernel only transitively via the public facade.
 *
 * <p>Shapes covered: a top-level {@code object}; nested {@code object}s → nested groups; {@code array} of
 * {@code object} → a repeatable group ({@code maxItems} → repeatability); scalar properties → fields. An
 * input element with no faithful a12 target is <em>reported</em> (never silently dropped or mismapped).
 */
public final class JsonSchemaImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL_ID = "ImportedModel";
    private static final String ROOT_GROUP = "Root";
    private static final String LOCALE = "en_US";

    public record ImportResult(String dmJson, TranscodeReport report) {}

    public ImportResult importSchema(String jsonSchema) {
        JsonNode root = parse(jsonSchema);
        String type = root.path("type").asText("");
        if (!"object".equals(type)) {
            throw new IllegalArgumentException(
                    "import supports a top-level object schema; got type=" + (type.isEmpty() ? "<none>" : type));
        }
        TranscodeReport.Builder report = TranscodeReport.builder();
        DocumentModelDraft draft = DocumentModelDraft.named(identifier(root.path("title").asText(null)))
                .locale(LOCALE)
                .rootGroup(ROOT_GROUP, g -> populate(g, root, "/" + ROOT_GROUP, report))
                .build();
        return new ImportResult(draft.toJson(), report.build());
    }

    /** Fill {@code g} from the {@code properties} of an object schema; each object level owns its {@code required}. */
    private static void populate(DocumentModelDraft.GroupBuilder g, JsonNode objectSchema,
                                 String groupPath, TranscodeReport.Builder report) {
        Set<String> required = requiredNames(objectSchema);
        JsonNode properties = objectSchema.path("properties");
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            addProperty(g, e.getKey(), e.getValue(), required.contains(e.getKey()), groupPath, report);
        }
    }

    private static void addProperty(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                    boolean isRequired, String groupPath, TranscodeReport.Builder report) {
        String path = groupPath + "/" + name;
        String type = schema.path("type").asText("");
        switch (type) {
            case "object" -> {
                g.group(name, sub -> populate(sub, schema, path, report));
                report.structure(path, "group");
            }
            case "array" -> addArray(g, name, schema, path, report);
            default -> addScalar(g, name, schema, isRequired, path, report);
        }
    }

    private static void addArray(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                 String path, TranscodeReport.Builder report) {
        JsonNode items = schema.path("items");
        if (!"object".equals(items.path("type").asText(""))) {
            report.refuse(path, "array '" + name + "' has non-object items — array-of-scalar needs a strategy "
                    + "(multi-select / value-list, proposal §9); not in this increment");
            return;
        }
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems == null || maxItems.asInt() < 2) {
            report.refuse(path, "array '" + name + "' needs maxItems ≥ 2 — a12 group repeatability is bounded "
                    + "and ≥ 2 (there is no unbounded repeatable group)");
            return;
        }
        int max = maxItems.asInt();
        g.group(name, sub -> {
            sub.repeatable(max);
            populate(sub, items, path, report);
        });
        report.structure(path, "repeatable group (max " + max + ")");
        if (schema.has("minItems")) {
            report.diagnostic("array '" + name + "' minItems=" + schema.get("minItems").asInt()
                    + " has no structural home (a12 repeatability has no min slot) — becomes a GroupFilled rule "
                    + "in the rule-synthesis increment (proposal §4)");
        }
    }

    private static void addScalar(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                  boolean isRequired, String path, TranscodeReport.Builder report) {
        Requiredness req = isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL;
        String type = schema.path("type").asText("");
        switch (type) {
            case "string" -> {
                g.field(name, FieldKind.STRING, req);
                report.structure(path, "STRING");
            }
            case "boolean" -> {
                g.field(name, FieldKind.BOOLEAN, req);
                report.structure(path, "BOOLEAN");
            }
            case "integer" -> {
                g.numberField(name, b -> {
                    b.fractionDigits(0);
                    if (isRequired) {
                        b.required();
                    }
                });
                report.structure(path, "NUMBER(integer, scale 0)");
            }
            // JSON `number` has NO faithful a12 target: an a12 number always carries a bounded max scale
            // (modelkit defaults it to 0 = integer), so an unbounded decimal cannot be expressed without a
            // scale policy. Mapping it to scale 0 would silently turn a decimal into an integer — so it is
            // REPORTED, not mismapped. Scale inference (multipleOf → scale, proposal §5) lands later.
            case "number" -> report.refuse(path, "property '" + name + "' is type 'number' (unbounded decimal) — "
                    + "no faithful a12 target: an a12 number is always scale-bounded (default 0 = integer). "
                    + "Provide 'multipleOf' for a scale, or await the field-type-matcher increment (proposal §5)");
            default -> report.diagnostic("unsupported property type '" + (type.isEmpty() ? "<none>" : type)
                    + "' on '" + name + "'");
        }
    }

    private static Set<String> requiredNames(JsonNode objectSchema) {
        JsonNode req = objectSchema.path("required");
        if (!req.isArray()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        req.forEach(n -> names.add(n.asText()));
        return names;
    }

    /** A JSON Schema title is free text; fall back to a stable default when it is absent or not a safe id. */
    private static String identifier(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_MODEL_ID;
        }
        String trimmed = title.trim();
        return trimmed.matches("[A-Za-z][A-Za-z0-9_]*") ? trimmed : DEFAULT_MODEL_ID;
    }

    private static JsonNode parse(String jsonSchema) {
        try {
            return JSON.readTree(jsonSchema);
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
        }
    }
}
