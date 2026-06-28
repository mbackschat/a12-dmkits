package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

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
    private static final String ROW_FIELD = "value";   // the single value field in a scalar-array / map row group
    private static final String MAP_KEY_FIELD = "key";   // the entry-key field in a map's {key, value} row group

    public record ImportResult(String dmJson, TranscodeReport report) {}

    /** Per-transcode state threaded through the recursion: the {@code $defs} pool, the inline stack (for
     *  recursion detection), the report, the Tier-A+ rule-synthesis needs, and the customization profile. */
    private record Ctx(JsonNode defs, Deque<String> inlining, TranscodeReport.Builder report,
                       List<RuleSynthesis.Need> ruleNeeds, MappingProfile profile) {}

    public ImportResult importSchema(String jsonSchema) {
        return importSchema(jsonSchema, MappingProfile.defaults());
    }

    public ImportResult importSchema(String jsonSchema, MappingProfile profile) {
        JsonNode root = parse(jsonSchema);
        String type = root.path("type").asText("");
        if (!"object".equals(type)) {
            throw new IllegalArgumentException(
                    "import supports a top-level object schema; got type=" + (type.isEmpty() ? "<none>" : type));
        }
        Ctx ctx = new Ctx(root.path("$defs"), new ArrayDeque<>(), TranscodeReport.builder(), new ArrayList<>(), profile);
        DocumentModelDraft.Builder b = DocumentModelDraft.named(identifier(root.path("title").asText(null)))
                .locale(LOCALE);
        declareLeafTypedefs(b, ctx);
        b.rootGroup(ROOT_GROUP, g -> populate(g, root, "/" + ROOT_GROUP, ctx));

        String dmJson = b.build().toJson();
        dmJson = RuleSynthesis.apply(dmJson, ctx.ruleNeeds(), ctx.report());   // Tier A+ synthesized rules
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
        collectDependentRequired(objectSchema, groupPath, ctx);
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
                if (isMap(schema)) {
                    addMap(g, name, schema, path, ctx);
                } else {
                    g.group(name, sub -> populate(sub, schema, path, ctx));
                    ctx.report().structure(path, "group");
                    if (hasMapExtras(schema)) {
                        ctx.report().diagnostic("object '" + name + "' declares additionalProperties/"
                                + "patternProperties (extra dynamic keys) alongside its fixed properties — the "
                                + "extras are NOT imported; model them as a separate map property if needed");
                    }
                }
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
            addScalarArray(g, name, schema, items, path, ctx);
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

    /**
     * An {@code array} of <em>scalar</em> items — no single a12 home, so the {@link MappingProfile} picks a
     * target (SCHEMAKIT-SPEC §4b). All three non-{@code OMIT} strategies need a bounded cap ({@code maxItems
     * ≥ 2}; a12 repeatability is a bounded max ≥ 2) — {@link #withCap} omits when absent. Each row holds the
     * one value field named {@value #ROW_FIELD}. Omit, never block: an unmappable combination is skipped + a to-do.
     */
    private static void addScalarArray(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                       JsonNode items, String path, Ctx ctx) {
        switch (ctx.profile().arrayOfScalar()) {
            case OMIT -> ctx.report().omit(path, "array '" + name + "' has scalar items — omitted. TO DO: add it "
                    + "by hand as a multi-select group or a value-list field (proposal §9), or set the "
                    + "array-of-scalar strategy in the MappingProfile (MULTI_SELECT / VALUE_LIST / REPEATABLE_SINGLE)");
            case MULTI_SELECT -> withCap(schema, name, path, ctx, max -> asMultiSelect(g, name, items, max, path, ctx));
            case VALUE_LIST -> withCap(schema, name, path, ctx, max -> asValueList(g, name, items, max, path, ctx));
            case REPEATABLE_SINGLE -> withCap(schema, name, path, ctx, max -> asRepeatableSingle(g, name, items, max, path, ctx));
        }
    }

    /** Run {@code build} with the array's repeatability cap, or omit when there is no {@code maxItems ≥ 2}. */
    private static void withCap(JsonNode schema, String name, String path, Ctx ctx, IntConsumer build) {
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems == null || maxItems.asInt() < 2) {
            ctx.report().omit(path, "array '" + name + "' of scalars has no maxItems ≥ 2 — omitted: an a12 "
                    + "repeatable group needs a bounded max ≥ 2. TO DO: set maxItems on the schema, or add the "
                    + "group by hand with a chosen max");
            return;
        }
        build.accept(maxItems.asInt());
    }

    /** MULTI_SELECT → an a12 multi-select group of closed enum choices; needs {@code items.enum}. */
    private static void asMultiSelect(DocumentModelDraft.GroupBuilder g, String name, JsonNode items,
                                      int max, String path, Ctx ctx) {
        if (!hasEnum(items)) {
            ctx.report().omit(path, "array '" + name + "' under MULTI_SELECT needs a closed item set (items.enum) "
                    + "— omitted. TO DO: add items.enum, or choose the VALUE_LIST / REPEATABLE_SINGLE strategy");
            return;
        }
        String[] values = enumArrayValues(items);
        g.multiSelectGroup(name, m -> m.enumField(ROW_FIELD, values).maxRows(max));
        ctx.report().structure(path, "multi-select group (enum, max " + max + ")");
    }

    /** VALUE_LIST → a multi-select group with a free-text string field; needs string items. */
    private static void asValueList(DocumentModelDraft.GroupBuilder g, String name, JsonNode items,
                                    int max, String path, Ctx ctx) {
        if (!"string".equals(items.path("type").asText(""))) {
            ctx.report().omit(path, "array '" + name + "' under VALUE_LIST needs string items — omitted. "
                    + "TO DO: choose the REPEATABLE_SINGLE strategy for non-string scalars");
            return;
        }
        g.multiSelectGroup(name, m -> m.stringField(ROW_FIELD).maxRows(max));
        ctx.report().structure(path, "value-list (string multi-select, max " + max + ")");
    }

    /** REPEATABLE_SINGLE → a plain repeatable group with one field of the item's kind (the general form). */
    private static void asRepeatableSingle(DocumentModelDraft.GroupBuilder g, String name, JsonNode items,
                                           int max, String path, Ctx ctx) {
        if (scalarWouldOmit(items, ctx)) {
            ctx.report().omit(path, "array '" + name + "' has items of type '"
                    + (items.path("type").asText("").isEmpty() ? "<none>" : items.path("type").asText(""))
                    + "' not importable under the current profile — omitted (an empty repeatable group is "
                    + "invalid). TO DO: set the numberScale strategy for number items, or add the field by hand");
            return;
        }
        g.group(name, sub -> {
            sub.repeatable(max);
            addScalar(sub, ROW_FIELD, items, true, path + "/" + ROW_FIELD, ctx);
        });
        ctx.report().structure(path, "repeatable single-field group (max " + max + ")");
    }

    /** Would {@link #addScalar} omit this item kind under the profile? (Used to avoid an empty repeatable group.) */
    private static boolean scalarWouldOmit(JsonNode items, Ctx ctx) {
        if (hasEnum(items)) {
            return false;
        }
        return switch (items.path("type").asText("")) {
            case "string", "boolean", "integer" -> false;
            case "number" -> ctx.profile().numberScale() == MappingProfile.NumberScale.OMIT;
            default -> true;   // unknown / unsupported scalar kind
        };
    }

    private static boolean hasEnum(JsonNode items) {
        return items.has("enum") && items.get("enum").isArray() && !items.get("enum").isEmpty();
    }

    private static String[] enumArrayValues(JsonNode items) {
        JsonNode en = items.get("enum");
        String[] values = new String[en.size()];
        for (int i = 0; i < en.size(); i++) {
            values[i] = en.get(i).asText();
        }
        return values;
    }

    /** A JSON object used as a MAP: no fixed property set, just a value schema for arbitrary keys. */
    private static boolean isMap(JsonNode schema) {
        boolean hasProperties = schema.path("properties").isObject() && schema.get("properties").size() > 0;
        return !hasProperties && hasMapExtras(schema);
    }

    /** Does the object carry dynamic-key extras ({@code additionalProperties} schema / {@code patternProperties})? */
    private static boolean hasMapExtras(JsonNode schema) {
        return schema.path("additionalProperties").isObject() || hasPatternProperties(schema);
    }

    private static boolean hasPatternProperties(JsonNode schema) {
        return schema.path("patternProperties").isObject() && !schema.get("patternProperties").isEmpty();
    }

    /** The value schema of a map: {@code additionalProperties} if a schema, else the first {@code patternProperties}. */
    private static JsonNode mapValueSchema(JsonNode schema) {
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isObject()) {
            return additional;
        }
        return schema.path("patternProperties").fields().next().getValue();   // hasPatternProperties guarantees one
    }

    /**
     * A map → a repeatable {@code {key, value}} group (SCHEMAKIT-SPEC §4b): each row is one entry, {@value
     * #MAP_KEY_FIELD} holds the key and {@value #ROW_FIELD} the scalar value (an object value's fields are
     * inlined alongside {@value #MAP_KEY_FIELD}). The cap comes from {@code maxProperties} (≥ 2; a12
     * repeatability is a bounded max ≥ 2) — omitted when absent. Omit, never block.
     */
    private static void addMap(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                               String path, Ctx ctx) {
        switch (ctx.profile().mapStrategy()) {
            case OMIT -> ctx.report().omit(path, "object '" + name + "' is a map (additionalProperties/"
                    + "patternProperties) — omitted. TO DO: add a repeatable {key, value} group by hand, or set "
                    + "the map strategy in the MappingProfile (REPEATABLE_KEY_VALUE)");
            case IGNORE -> ctx.report().diagnostic("object '" + name + "' is a map — ignored per the IGNORE map "
                    + "strategy; no structure emitted for its dynamic keys");
            case REPEATABLE_KEY_VALUE -> withMapCap(schema, name, path, ctx,
                    max -> buildKeyValueGroup(g, name, schema, max, path, ctx));
        }
    }

    /** Run {@code build} with the map's repeatability cap, or omit when there is no {@code maxProperties ≥ 2}. */
    private static void withMapCap(JsonNode schema, String name, String path, Ctx ctx, IntConsumer build) {
        JsonNode maxProperties = schema.get("maxProperties");
        if (maxProperties == null || maxProperties.asInt() < 2) {
            ctx.report().omit(path, "map '" + name + "' has no maxProperties ≥ 2 — omitted: an a12 repeatable "
                    + "group needs a bounded max ≥ 2. TO DO: set maxProperties on the schema, or add the group by hand");
            return;
        }
        build.accept(maxProperties.asInt());
    }

    private static void buildKeyValueGroup(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                           int max, String path, Ctx ctx) {
        JsonNode value = mapValueSchema(schema);
        g.group(name, sub -> {
            sub.repeatable(max);
            sub.field(MAP_KEY_FIELD, FieldKind.STRING, Requiredness.REQUIRED);   // the entry key
            if ("object".equals(value.path("type").asText(""))) {
                populate(sub, value, path, ctx);   // inline the value object's fields alongside the key
            } else {
                addScalar(sub, ROW_FIELD, value, true, path + "/" + ROW_FIELD, ctx);
            }
        });
        ctx.report().structure(path, "map → repeatable {key, value} group (max " + max + ")");
    }

    private static void addScalar(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                  boolean isRequired, String path, Ctx ctx) {
        Requiredness req = isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL;
        if (schema.has("enum") && schema.get("enum").isArray() && !schema.get("enum").isEmpty()) {
            addEnumField(g, name, schema, isRequired);
            ctx.report().structure(path, "ENUMERATION");
            return;
        }
        switch (schema.path("type").asText("")) {
            case "string" -> {
                addStringField(g, name, schema, isRequired);
                ctx.report().structure(path, "STRING");
                if (schema.has("const")) {
                    ctx.ruleNeeds().add(new RuleSynthesis.ConstValue(path, false, schema.get("const").asText()));
                }
            }
            case "boolean" -> {
                FieldKind kind = ctx.profile().booleanKind() == MappingProfile.BooleanKind.CONFIRM
                        ? FieldKind.CONFIRM : FieldKind.BOOLEAN;
                g.field(name, kind, req);
                ctx.report().structure(path, kind.name());
            }
            case "integer" -> {
                addIntegerField(g, name, schema, isRequired);
                ctx.report().structure(path, "NUMBER(integer, scale 0)");
                collectStrictBounds(schema, path, ctx);
                if (schema.has("const")) {
                    ctx.ruleNeeds().add(new RuleSynthesis.ConstValue(path, true, schema.get("const").asText()));
                }
                if (schema.has("multipleOf")) {
                    ctx.ruleNeeds().add(new RuleSynthesis.MultipleOf(path, schema.get("multipleOf").asText()));
                }
            }
            // JSON `number` has no faithful a12 target without a scale signal: an a12 number always carries a
            // bounded max scale (modelkit defaults it to 0 = integer). The MappingProfile decides: OMIT (the
            // default — never silently turn a decimal into an integer), INTEGER (scale 0), or a DEFAULT_SCALE.
            case "number" -> addNumberField(g, name, isRequired, path, ctx);
            default -> ctx.report().omit(path, "property '" + name + "' has type '"
                    + (schema.path("type").asText("").isEmpty() ? "<none>" : schema.path("type").asText(""))
                    + "' — omitted (not yet mapped). TO DO: add this field by hand");
        }
    }

    /** A JSON Schema {@code enum} → an a12 ENUMERATION. Labels have no JSON-Schema source → none (the
     *  label strategy is a MappingProfile knob); values import in order. */
    private static void addEnumField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                     boolean isRequired) {
        g.enumField(name, e -> {
            for (JsonNode value : schema.get("enum")) {
                e.value(value.asText());
            }
            if (isRequired) {
                e.required();
            }
        });
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

    /** Strict bounds have no inclusive field-config home → collect them as Tier-A+ synthesized rules. */
    private static void collectStrictBounds(JsonNode schema, String path, Ctx ctx) {
        if (schema.has("exclusiveMinimum")) {
            ctx.ruleNeeds().add(new RuleSynthesis.ExclusiveBound(path, true, schema.get("exclusiveMinimum").asText()));
        }
        if (schema.has("exclusiveMaximum")) {
            ctx.ruleNeeds().add(new RuleSynthesis.ExclusiveBound(path, false, schema.get("exclusiveMaximum").asText()));
        }
    }

    /** {@code dependentRequired} on an object → a conditional-requiredness rule per (trigger → dependent). */
    private static void collectDependentRequired(JsonNode objectSchema, String groupPath, Ctx ctx) {
        JsonNode dependentRequired = objectSchema.path("dependentRequired");
        if (!dependentRequired.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = dependentRequired.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isArray()) {
                continue;
            }
            for (JsonNode dependent : e.getValue()) {
                ctx.ruleNeeds().add(new RuleSynthesis.DependentRequired(groupPath, e.getKey(), dependent.asText()));
            }
        }
    }

    /** A bare {@code number} (unbounded decimal); the MappingProfile decides its a12 scale (or omits it). */
    private static void addNumberField(DocumentModelDraft.GroupBuilder g, String name, boolean isRequired,
                                       String path, Ctx ctx) {
        switch (ctx.profile().numberScale()) {
            case OMIT -> ctx.report().omit(path, "property '" + name + "' is type 'number' (unbounded decimal) "
                    + "— omitted: an a12 number is always scale-bounded. TO DO: add the field by hand with a "
                    + "chosen scale, or set the MappingProfile numberScale to DEFAULT_SCALE / INTEGER");
            case INTEGER -> {
                g.numberField(name, n -> {
                    n.fractionDigits(0);
                    if (isRequired) {
                        n.required();
                    }
                });
                ctx.report().structure(path, "NUMBER(integer, scale 0)");
            }
            case DEFAULT_SCALE -> {
                int scale = ctx.profile().defaultScale();
                g.numberField(name, n -> {
                    n.fractionDigits(scale);
                    if (isRequired) {
                        n.required();
                    }
                });
                ctx.report().structure(path, "NUMBER(scale " + scale + ")");
            }
        }
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
