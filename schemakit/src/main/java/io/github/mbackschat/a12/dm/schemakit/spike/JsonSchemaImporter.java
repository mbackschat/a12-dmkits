package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.ArrayDeque;
import java.util.Deque;
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
 * {@code object} → a repeatable group; scalar properties → fields; {@code $defs}/{@code $ref} — a leaf
 * {@code $def} → a typeDefinition + reference, an object {@code $def} → an inlined nested group (with a
 * reuse-collapse note; the include/mount mapping is a later increment), and a <em>recursive</em>
 * {@code $ref} is <em>omitted</em> with a corrective to-do. <b>Import never blocks</b>: a feature a12 cannot
 * represent is omitted (skipped + recorded as a to-do in the report), so the result is always a usable,
 * kernel-valid DM skeleton the user completes by hand — never a silent drop, never an abort.
 */
public final class JsonSchemaImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL_ID = "ImportedModel";
    private static final String ROOT_GROUP = "Root";
    private static final String LOCALE = "en_US";

    public record ImportResult(String dmJson, TranscodeReport report) {}

    /** Per-transcode state threaded through the recursion: the {@code $defs} pool, the inline stack (for
     *  recursion detection), and the report being accumulated. */
    private record Ctx(JsonNode defs, Deque<String> inlining, TranscodeReport.Builder report) {}

    public ImportResult importSchema(String jsonSchema) {
        JsonNode root = parse(jsonSchema);
        String type = root.path("type").asText("");
        if (!"object".equals(type)) {
            throw new IllegalArgumentException(
                    "import supports a top-level object schema; got type=" + (type.isEmpty() ? "<none>" : type));
        }
        Ctx ctx = new Ctx(root.path("$defs"), new ArrayDeque<>(), TranscodeReport.builder());
        DocumentModelDraft.Builder b = DocumentModelDraft.named(identifier(root.path("title").asText(null)))
                .locale(LOCALE);
        declareLeafTypedefs(b, ctx);
        b.rootGroup(ROOT_GROUP, g -> populate(g, root, "/" + ROOT_GROUP, ctx));

        String dmJson = b.build().toJson();
        if (root.has(Carriage.X_RULE) || root.has(Carriage.X_COMPUTATION)) {
            ObjectNode tree = (ObjectNode) parse(dmJson);     // restore carried rules/computations (Tier B/C)
            Carriage.inject(tree, root, ctx.report());
            dmJson = tree.toString();
        }
        return new ImportResult(dmJson, ctx.report().build());
    }

    /** A leaf {@code $def} (string/integer) becomes a reusable a12 typeDefinition, declared before the root group. */
    private static void declareLeafTypedefs(DocumentModelDraft.Builder b, Ctx ctx) {
        if (!ctx.defs().isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = ctx.defs().fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            switch (e.getValue().path("type").asText("")) {
                case "string" -> b.typeDef(e.getKey(), t -> t.string(s -> {}));
                case "integer" -> b.typeDef(e.getKey(), t -> t.number(n -> n.fractionDigits(0)));
                default -> { /* object $defs are inlined; other leaf kinds resolve at the ref site */ }
            }
        }
    }

    /** Fill {@code g} from the {@code properties} of an object schema; each object level owns its {@code required}. */
    private static void populate(DocumentModelDraft.GroupBuilder g, JsonNode objectSchema,
                                 String groupPath, Ctx ctx) {
        Set<String> required = requiredNames(objectSchema);
        JsonNode properties = objectSchema.path("properties");
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            addProperty(g, e.getKey(), e.getValue(), required.contains(e.getKey()), groupPath, ctx);
        }
    }

    private static void addProperty(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                    boolean isRequired, String groupPath, Ctx ctx) {
        String path = groupPath + "/" + name;
        if (schema.has("$ref")) {
            addRef(g, name, schema, isRequired, path, ctx);
            return;
        }
        switch (schema.path("type").asText("")) {
            case "object" -> {
                g.group(name, sub -> populate(sub, schema, path, ctx));
                ctx.report().structure(path, "group");
            }
            case "array" -> addArray(g, name, schema, path, ctx);
            default -> addScalar(g, name, schema, isRequired, path, ctx);
        }
    }

    private static void addRef(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                               boolean isRequired, String path, Ctx ctx) {
        String defName = defName(schema);
        JsonNode def = ctx.defs().path(defName);
        if (def.isMissingNode()) {
            ctx.report().omit(path, "$ref at '" + name + "' → unknown $def '" + defName + "' — omitted. "
                    + "TO DO: check the schema's $defs, then add the field by hand");
            return;
        }
        switch (def.path("type").asText("")) {
            case "string", "integer" -> {
                g.typedefField(name, defName, isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL);
                ctx.report().structure(path, "typedef ref → " + defName);
            }
            case "object" -> inlineObjectDef(g, name, defName, def, path, ctx);
            default -> addScalar(g, name, def, isRequired, path, ctx);   // boolean leaf $def → an inline field
        }
    }

    private static void inlineObjectDef(DocumentModelDraft.GroupBuilder g, String name, String defName,
                                        JsonNode def, String path, Ctx ctx) {
        if (ctx.inlining().contains(defName)) {
            ctx.report().omit(path, "recursive $ref at '" + name + "' → '" + defName + "' — omitted: a12 includes "
                    + "expand, so infinite recursion has no static form. TO DO: add the recursive structure "
                    + "by hand to the depth you need, or set a bounded recursionDepth in the MappingProfile");
            return;
        }
        ctx.inlining().push(defName);
        g.group(name, sub -> populate(sub, def, path, ctx));
        ctx.inlining().pop();
        ctx.report().structure(path, "object $def inlined (" + defName + ")");
        ctx.report().diagnostic("object $def '" + defName + "' was INLINED at '" + name + "' — reuse via an a12 "
                + "include/mount (a separate child model, proposal §4.1) is a later increment");
    }

    private static void addArray(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                 String path, Ctx ctx) {
        JsonNode items = schema.path("items");
        if (!"object".equals(items.path("type").asText(""))) {
            ctx.report().omit(path, "array '" + name + "' has scalar items — omitted. TO DO: add it by hand as a "
                    + "multi-select group or a value-list field (proposal §9), or set the array-of-scalar "
                    + "strategy in the MappingProfile");
            return;
        }
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems == null || maxItems.asInt() < 2) {
            ctx.report().omit(path, "array '" + name + "' has no maxItems ≥ 2 — omitted: a12 group repeatability "
                    + "is a bounded max ≥ 2. TO DO: add the repeatable group by hand with a chosen max, or set "
                    + "maxItems on the schema");
            return;
        }
        int max = maxItems.asInt();
        g.group(name, sub -> {
            sub.repeatable(max);
            populate(sub, items, path, ctx);
        });
        ctx.report().structure(path, "repeatable group (max " + max + ")");
        if (schema.has("minItems")) {
            ctx.report().diagnostic("array '" + name + "' minItems=" + schema.get("minItems").asInt()
                    + " has no structural home (a12 repeatability has no min slot) — becomes a GroupFilled rule "
                    + "in the rule-synthesis increment (proposal §4)");
        }
    }

    private static void addScalar(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                  boolean isRequired, String path, Ctx ctx) {
        Requiredness req = isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL;
        switch (schema.path("type").asText("")) {
            case "string" -> {
                addStringField(g, name, schema, isRequired);
                ctx.report().structure(path, "STRING");
            }
            case "boolean" -> {
                g.field(name, FieldKind.BOOLEAN, req);
                ctx.report().structure(path, "BOOLEAN");
            }
            case "integer" -> {
                addIntegerField(g, name, schema, isRequired);
                ctx.report().structure(path, "NUMBER(integer, scale 0)");
            }
            // JSON `number` has no faithful a12 target without a scale signal: an a12 number always carries a
            // bounded max scale (modelkit defaults it to 0 = integer), so mapping a bare `number` to scale 0
            // would silently turn a decimal into an integer. So it is omitted, not mismapped.
            case "number" -> ctx.report().omit(path, "property '" + name + "' is type 'number' (unbounded "
                    + "decimal) — omitted: an a12 number is always scale-bounded. TO DO: add the field by hand "
                    + "with a chosen scale, or set 'multipleOf' (→ scale) / a default-scale in the MappingProfile");
            default -> ctx.report().omit(path, "property '" + name + "' has type '"
                    + (schema.path("type").asText("").isEmpty() ? "<none>" : schema.path("type").asText(""))
                    + "' — omitted (not yet mapped). TO DO: add this field by hand");
        }
    }

    /** A string field; {@code minLength}/{@code maxLength}/{@code pattern} import to string field config. */
    private static void addStringField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                       boolean isRequired) {
        Integer minLength = schema.has("minLength") ? schema.get("minLength").asInt() : null;
        Integer maxLength = schema.has("maxLength") ? schema.get("maxLength").asInt() : null;
        String pattern = schema.path("pattern").isTextual() ? schema.get("pattern").asText() : null;
        if (minLength == null && maxLength == null && pattern == null) {
            g.field(name, FieldKind.STRING, isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL);
            return;
        }
        g.stringField(name, s -> {
            if (minLength != null) {
                s.minLength(minLength);
            }
            if (maxLength != null) {
                s.maxLength(maxLength);
            }
            if (pattern != null) {
                s.pattern(pattern, "must match " + pattern);   // a12 needs a message; JSON Schema has none → synthesize
            }
            if (isRequired) {
                s.required();
            }
        });
    }

    /** An integer field (scale 0); {@code minimum}/{@code maximum} import to number value bounds. */
    private static void addIntegerField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                        boolean isRequired) {
        String minimum = schema.has("minimum") ? schema.get("minimum").asText() : null;
        String maximum = schema.has("maximum") ? schema.get("maximum").asText() : null;
        g.numberField(name, n -> {
            n.fractionDigits(0);
            if (minimum != null) {
                n.min(minimum);
            }
            if (maximum != null) {
                n.max(maximum);
            }
            if (isRequired) {
                n.required();
            }
        });
    }

    private static String defName(JsonNode schema) {
        String ref = schema.path("$ref").asText("");
        int slash = ref.lastIndexOf('/');
        return slash >= 0 ? ref.substring(slash + 1) : ref;
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
