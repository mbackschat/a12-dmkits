package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * JSON Schema → Document Model, the import direction (SCHEMAKIT-SPEC §6). It authors through the
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
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());   // OpenAPI specs are often YAML
    private static final String DEFAULT_MODEL_ID = "ImportedModel";
    private static final String ROOT_GROUP = "Root";
    private static final String LOCALE = "en_US";
    private static final String ROW_FIELD = "value";   // the single value field in a scalar-array / map row group
    private static final String MAP_KEY_FIELD = "key";   // the entry-key field in a map's {key, value} row group

    public record ImportResult(String dmJson, TranscodeReport report) {}

    /** Per-transcode state threaded through the recursion: the whole document {@code root} (for JSON-Pointer
     *  {@code $ref} resolution), the resolved {@code dialect}, the reusable-definitions pool, the inline stack
     *  (recursion detection), the report, the Tier-A+ rule-synthesis needs, and the customization profile. */
    private record Ctx(JsonNode root, Dialect dialect, JsonNode defs, Deque<String> inlining,
                       TranscodeReport.Builder report, List<RuleSynthesis.Need> ruleNeeds, MappingProfile profile) {}

    public ImportResult importSchema(String jsonSchema) {
        return importSchema(jsonSchema, MappingProfile.defaults());
    }

    public ImportResult importSchema(String jsonSchema, MappingProfile profile) {
        return importSchema(jsonSchema, profile, Dialect.AUTO, null);
    }

    /**
     * Import, selecting the wire {@code dialect} (JSON Schema vs OpenAPI 3.0/3.1; {@link Dialect#AUTO} detects it)
     * and, for an OpenAPI document, the {@code component} under {@code components/schemas} to use as the model
     * root (null = the file is itself a bare schema, or — for a multi-schema OpenAPI document — a refusal that
     * lists the components, SCHEMAKIT-SPEC §4g).
     */
    public ImportResult importSchema(String jsonSchema, MappingProfile profile, Dialect requestedDialect, String component) {
        JsonNode root = parse(jsonSchema);
        Dialect dialect = resolveDialect(requestedDialect, root);
        TranscodeReport.Builder report = TranscodeReport.builder();
        if (dialect.isOpenApi()) {   // JSON Schema is the unremarkable default; an OpenAPI dialect is worth stating
            report.diagnostic("dialect: " + dialect + (requestedDialect == Dialect.AUTO ? " (auto-detected)" : " (forced)"));
        }
        JsonNode rootSchema = entryPointSchema(root, component);
        String type = rootSchema.path("type").asText("");
        if (!"object".equals(type)) {
            throw new IllegalArgumentException(
                    "import supports a top-level object schema; got type=" + (type.isEmpty() ? "<none>" : type));
        }
        JsonNode defsPool = dialect.isOpenApi() ? root.path("components").path("schemas") : root.path("$defs");
        Ctx ctx = new Ctx(root, dialect, defsPool, new ArrayDeque<>(), report, new ArrayList<>(), profile);
        DocumentModelDraft.Builder b = DocumentModelDraft
                .named(identifier(component != null ? component : rootSchema.path("title").asText(null)))
                .locale(LOCALE);
        declareLeafTypedefs(b, ctx);
        b.rootGroup(ROOT_GROUP, g -> populate(g, rootSchema, "/" + ROOT_GROUP, ctx));

        String dmJson = b.build().toJson();
        dmJson = RuleSynthesis.apply(dmJson, ctx.ruleNeeds(), ctx.report());   // Tier A+ synthesized rules
        if (rootSchema.has(Carriage.X_RULE) || rootSchema.has(Carriage.X_COMPUTATION)) {
            ObjectNode tree = (ObjectNode) parse(dmJson);     // restore carried rules/computations (Tier B/C)
            Carriage.inject(tree, rootSchema, ctx.report());
            dmJson = tree.toString();
        }
        return new ImportResult(dmJson, ctx.report().build());
    }

    /** {@link Dialect#AUTO} → sniff the document: the OpenAPI {@code openapi:} version field, else JSON Schema. */
    private static Dialect resolveDialect(Dialect requested, JsonNode root) {
        if (requested != Dialect.AUTO) {
            return requested;
        }
        String oas = root.path("openapi").asText("");
        if (oas.startsWith("3.0")) {
            return Dialect.OPENAPI_30;
        }
        if (oas.startsWith("3.1") || oas.startsWith("3.2")) {
            return Dialect.OPENAPI_31;
        }
        return Dialect.JSON_SCHEMA;
    }

    /** The schema to import as the model root: a named {@code components/schemas} entry, or the file itself when
     *  it is a bare schema. A multi-schema OpenAPI document with no {@code component} is refused with the list. */
    private static JsonNode entryPointSchema(JsonNode root, String component) {
        if (component != null && !component.isBlank()) {
            JsonNode picked = root.path("components").path("schemas").path(component);
            if (picked.isMissingNode()) {
                throw new IllegalArgumentException("component '" + component + "' not found under components/schemas"
                        + "; available: " + componentNames(root));
            }
            return picked;
        }
        if (root.has("type") || root.has("properties") || root.has("allOf") || root.has("$ref")) {
            return root;   // a bare schema (incl. a single extracted OpenAPI Schema Object)
        }
        JsonNode schemas = root.path("components").path("schemas");
        if (schemas.isObject() && !schemas.isEmpty()) {
            throw new IllegalArgumentException("this is an OpenAPI document with " + schemas.size()
                    + " component schemas but no component was selected; choose one of: " + componentNames(root)
                    + " (the CLI flag is --component)");
        }
        return root;   // fall through to the type check, which reports the real shape
    }

    private static List<String> componentNames(JsonNode root) {
        List<String> names = new ArrayList<>();
        root.path("components").path("schemas").fieldNames().forEachRemaining(names::add);
        return names;
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

    /**
     * Fill {@code g} from an object schema's {@code properties} — and, when it carries {@code allOf}, the
     * MERGED properties of every object subschema too (SCHEMAKIT-SPEC §4b): {@code allOf} is an
     * <em>intersection</em>, so all subschemas apply together. Properties are unioned (deduped by name,
     * first wins) and {@code required} is the union across the object + every subschema.
     */
    private static void populate(DocumentModelDraft.GroupBuilder g, JsonNode objectSchema,
                                 String groupPath, Ctx ctx) {
        List<JsonNode> allOf = allOfObjectSubschemas(objectSchema, ctx);
        Set<String> required = new LinkedHashSet<>(requiredNames(objectSchema));
        allOf.forEach(sub -> required.addAll(requiredNames(sub)));
        Set<String> seen = new LinkedHashSet<>();
        addMergedProperties(g, objectSchema.path("properties"), required, seen, groupPath, ctx);
        for (JsonNode sub : allOf) {
            addMergedProperties(g, sub.path("properties"), required, seen, groupPath, ctx);
        }
        collectDependentRequired(objectSchema, groupPath, ctx);
        collectIfThen(objectSchema, groupPath, ctx);
        if (!allOf.isEmpty()) {
            ctx.report().diagnostic("merged " + allOf.size() + " allOf object subschema(s) into '" + groupPath
                    + "' — all constraints apply together (intersection)");
        }
    }

    /**
     * {@code if}/{@code then} (SCHEMAKIT-SPEC §4): the tractable shape — {@code if} pins a single property to a
     * value and {@code then} requires fields — maps to a conditional-requiredness rule per dependent (the same
     * synthesis as a union discriminator). Anything else (arbitrary {@code if}/{@code then} schemas, {@code
     * then} value constraints, {@code else}) is reported, not silently dropped.
     */
    private static void collectIfThen(JsonNode objectSchema, String groupPath, Ctx ctx) {
        if (!objectSchema.has("if")) {
            return;
        }
        JsonNode ifProps = objectSchema.path("if").path("properties");
        boolean singleCond = ifProps.isObject() && ifProps.size() == 1;
        Optional<String> value = singleCond ? singleValueOf(ifProps.fields().next().getValue()) : Optional.empty();
        if (value.isEmpty()) {
            ctx.report().diagnostic("if/then at '" + groupPath + "' is not a simple 'if <field>=<value> …' shape "
                    + "(single property, single value) — not mapped; add the conditional rule by hand");
            return;
        }
        String condField = ifProps.fieldNames().next();
        boolean mappedThen = addConditionalRequired(objectSchema.path("then").path("required"),
                groupPath, condField, value.get(), true, ctx);
        boolean mappedElse = addConditionalRequired(objectSchema.path("else").path("required"),
                groupPath, condField, value.get(), false, ctx);
        if (!mappedThen && !mappedElse) {
            ctx.report().diagnostic("if/then at '" + groupPath + "' — no required[] in then/else to map; "
                    + "then/else value-constraints are not mapped — add the conditional rule by hand");
        } else if (objectSchema.path("then").has("properties") || objectSchema.path("else").has("properties")) {
            ctx.report().diagnostic("if/then at '" + groupPath + "': the required-fields part mapped to rules; "
                    + "then/else VALUE-constraints are NOT mapped — add them by hand");
        }
    }

    /** Each {@code required} entry (other than the condition field itself) → a conditional-requiredness rule fired
     *  on the condition ({@code whenEquals=true} = then) or its negation ({@code false} = else). Returns whether
     *  it mapped anything. */
    private static boolean addConditionalRequired(JsonNode required, String groupPath, String condField,
                                                  String value, boolean whenEquals, Ctx ctx) {
        if (!required.isArray() || required.isEmpty()) {
            return false;
        }
        boolean mapped = false;
        for (JsonNode dependent : required) {
            String reqField = dependent.asText();
            if (!reqField.equals(condField)) {
                ctx.ruleNeeds().add(new RuleSynthesis.DiscriminatedRequired(groupPath, condField, value, reqField, whenEquals));
                mapped = true;
            }
        }
        return mapped;
    }

    /** Add each property once (dedup across the object's own + its allOf subschemas), required per the union. */
    private static void addMergedProperties(DocumentModelDraft.GroupBuilder g, JsonNode properties,
                                            Set<String> required, Set<String> seen, String groupPath, Ctx ctx) {
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (seen.add(e.getKey())) {
                addProperty(g, e.getKey(), e.getValue(), required.contains(e.getKey()), groupPath, ctx);
            }
        }
    }

    /** Validation keywords with no a12 mapping yet — reported (never silently dropped); structure still imports. */
    // `contains`/`minContains` need starred-aggregate synthesis (deferred); `if`/`then`/`else` + `not` are
    // handled specially (collectIfThen / collectNot) — their tractable shapes synthesize rules, the rest report.
    private static final List<String> UNSUPPORTED_KEYWORDS = List.of("contains", "minContains");

    private static void addProperty(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                    boolean isRequired, String groupPath, Ctx ctx) {
        String path = groupPath + "/" + name;
        noteUnsupportedKeywords(name, schema, ctx);
        boolean required = resolveNullable(name, schema, isRequired, ctx);   // OpenAPI 3.0 nullable:true ⇒ optional
        if (schema.has("$ref")) {
            addRef(g, name, schema, required, path, ctx);
            return;
        }
        if (schema.has("$dynamicRef")) {
            ctx.report().omit(path, "property '" + name + "' uses $dynamicRef '" + schema.get("$dynamicRef").asText()
                    + "' — a runtime-polymorphic reference with no static a12 form (SCHEMAKIT-SPEC §4c). TO DO: "
                    + "add the resolved structure by hand");
            return;
        }
        if (isObjectUnion(schema)) {
            addUnion(g, name, unionVariants(schema), path, ctx, explicitDiscriminator(schema, ctx));
            return;
        }
        if (hasUnionNode(schema)) {   // a oneOf/anyOf that is NOT all-object → a cross-kind union (§4c)
            ctx.report().omit(path, "property '" + name + "' is a oneOf/anyOf of mixed or non-object kinds — "
                    + "omitted: a12 has no cross-kind sum type (SCHEMAKIT-SPEC §4c). TO DO: model it as separate "
                    + "fields, or pick the primary variant by hand");
            return;
        }
        if (schema.path("type").isArray()) {
            addTypeUnion(g, name, schema, required, groupPath, ctx);   // nullable X / cross-kind type array (§4c)
            return;
        }
        if (!schema.has("type") && schema.path("allOf").isArray()) {
            addAllOfGroup(g, name, schema, path, ctx);   // a bare allOf (no type) → a merged group
            return;
        }
        if (schema.has("unevaluatedProperties") || schema.has("unevaluatedItems")) {
            ctx.report().diagnostic("property '" + name + "' uses unevaluatedProperties/unevaluatedItems — an "
                    + "applicator-dependent dynamic closure with no static a12 form (SCHEMAKIT-SPEC §4c); the "
                    + "declared structure imports but that constraint is NOT enforced. TO DO: enforce it by hand");
            // fall through: the object's declared structure still imports
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
            default -> addScalar(g, name, schema, required, path, ctx);
        }
    }

    /** OpenAPI 3.0 {@code nullable:true} ⇒ the field is OPTIONAL (a12 has no present-but-null). A required+nullable
     *  field loses its "must be present" half — reported, never silent. Other dialects: requiredness unchanged. */
    private static boolean resolveNullable(String name, JsonNode schema, boolean isRequired, Ctx ctx) {
        if (ctx.dialect().isOpenApi30() && schema.path("nullable").asBoolean(false)) {
            if (isRequired) {
                ctx.report().diagnostic("property '" + name + "' is nullable+required (OpenAPI 3.0) → imported as "
                        + "OPTIONAL: a12 cannot express present-but-null. TO DO: enforce presence by hand if needed");
            }
            return false;
        }
        return isRequired;
    }

    private static void addRef(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                               boolean isRequired, String path, Ctx ctx) {
        String defName = defName(schema);
        JsonNode def = resolveRef(ctx, schema);
        if (def.isMissingNode()) {
            ctx.report().omit(path, "$ref at '" + name + "' → unresolved '" + schema.path("$ref").asText() + "' — "
                    + "omitted. TO DO: check the ref target (" + (ctx.dialect().isOpenApi() ? "components/schemas"
                    : "$defs") + "), then add the field by hand");
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
        long depth = ctx.inlining().stream().filter(defName::equals).count();
        if (depth > ctx.profile().recursionDepth()) {   // 0 = expand once then stop; N = N extra recursive levels
            ctx.report().omit(path, "recursive $ref at '" + name + "' → '" + defName + "' — omitted at depth "
                    + depth + ": a12 includes expand, so infinite recursion has no static form (SCHEMAKIT-SPEC §4c). "
                    + "TO DO: raise the MappingProfile recursionDepth, or add the deeper structure by hand");
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
        if (schema.path("uniqueItems").asBoolean(false)) {
            // a repeatable group is built below (object or scalar array) → key its rows for uniqueness; the
            // synthesis resolves the actual row fields post-build and skips if the group/fields didn't import.
            ctx.ruleNeeds().add(new RuleSynthesis.UniqueItems(path));
        }
        noteMinItems(schema, name, ctx);   // minItems is not enforced (KERNEL-FINDINGS) — report it for BOTH array kinds
        JsonNode items = schema.path("items");
        if (!"object".equals(items.path("type").asText(""))) {
            addScalarArray(g, name, schema, items, path, ctx);
            return;
        }
        int max = resolvedCap(schema.get("maxItems"), name, "maxItems", ctx);
        if (max < 0) {
            ctx.report().omit(path, "array '" + name + "' has no maxItems and no default cap — omitted: a12 group "
                    + "repeatability is a bounded max ≥ 2. TO DO: set maxItems, raise --default-repeat-cap, or add by hand");
            return;
        }
        g.group(name, sub -> {
            sub.repeatable(max);
            populate(sub, items, path, ctx);
        });
        ctx.report().structure(path, "repeatable group (max " + max + ")");
    }

    /**
     * The repeatability cap for a container: the schema's bound ({@code maxItems}/{@code maxProperties}) when
     * ≥ 2, else the profile's {@code defaultRepeatCap} (best-effort — so an unbounded array/map still imports
     * and the importer can reach into its nested structure), else {@code -1} = no cap (the caller omits). A
     * fallback cap is flagged in the report (it is a chosen bound, not from the schema).
     */
    private static int resolvedCap(JsonNode bound, String name, String boundKey, Ctx ctx) {
        if (bound != null && bound.asInt() >= 2) {
            return bound.asInt();
        }
        int fallback = ctx.profile().defaultRepeatCap();
        if (fallback >= 2) {
            ctx.report().diagnostic("'" + name + "' has no " + boundKey + " — capped at the default " + fallback
                    + " rows (a chosen bound, not from the schema); set " + boundKey + ", --default-repeat-cap, or --strict");
            return fallback;
        }
        return -1;
    }

    /**
     * {@code minItems} (a minimum row count) is NOT enforced — a12 has no clean rule form for group
     * cardinality (the error entity controls a rule's iteration, and {@code NumberOfFilledGroups} needs ≥ 2
     * group args — KERNEL-FINDINGS). So it is reported, never silently implied to be enforced (task #7).
     */
    /**
     * {@code not: {const}} / {@code not: {enum}} → a forbidding rule (SCHEMAKIT-SPEC §4); any other {@code not}
     * shape is reported, not silently dropped. The synthesis resolves the field's kind post-build (and reports
     * if the kind can't carry the constraint), so this just records the request.
     */
    private static void collectNot(String name, JsonNode schema, String path, Ctx ctx) {
        JsonNode not = schema.path("not");
        if (not.isMissingNode()) {
            return;
        }
        if (not.has("const")) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenConst(path, not.get("const").asText()));
        } else if (not.path("enum").isArray() && !not.get("enum").isEmpty()) {
            List<String> values = new ArrayList<>();
            not.get("enum").forEach(v -> values.add(v.asText()));
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenEnum(path, values));
        } else if (not.hasNonNull("pattern")) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenPattern(path, not.get("pattern").asText()));
        } else if (not.path("minimum").isNumber()) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenBound(path, true, not.get("minimum").asText()));
        } else if (not.path("maximum").isNumber()) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenBound(path, false, not.get("maximum").asText()));
        } else {
            ctx.report().diagnostic("property '" + name + "' uses a 'not' that isn't a simple "
                    + "const/enum/pattern/minimum/maximum — not mapped; add the constraint by hand");
        }
    }

    /** Report any {@link #UNSUPPORTED_KEYWORDS} present — surfaced, not silently dropped (task #13). */
    private static void noteUnsupportedKeywords(String name, JsonNode schema, Ctx ctx) {
        for (String kw : UNSUPPORTED_KEYWORDS) {
            if (schema.has(kw)) {
                ctx.report().diagnostic("property '" + name + "' uses '" + kw + "' — not yet imported; the declared "
                        + "structure imports but that constraint is NOT enforced. TO DO: add the equivalent a12 rule "
                        + "by hand");
            }
        }
    }

    private static void noteMinItems(JsonNode schema, String name, Ctx ctx) {
        if (schema.has("minItems")) {
            ctx.report().diagnostic("array '" + name + "' minItems=" + schema.get("minItems").asInt()
                    + " is NOT enforced — a12 has no clean rule form for group cardinality (KERNEL-FINDINGS). "
                    + "TO DO: enforce the minimum row count by hand if it is required");
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

    /** Run {@code build} with the array's repeatability cap ({@link #resolvedCap}), or omit when there is none. */
    private static void withCap(JsonNode schema, String name, String path, Ctx ctx, IntConsumer build) {
        int max = resolvedCap(schema.get("maxItems"), name, "maxItems", ctx);
        if (max < 0) {
            ctx.report().omit(path, "array '" + name + "' of scalars has no maxItems and no default cap — "
                    + "omitted: an a12 repeatable group needs a bounded max ≥ 2. TO DO: set maxItems, raise "
                    + "--default-repeat-cap, or add the group by hand");
            return;
        }
        build.accept(max);
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

    /** Run {@code build} with the map's repeatability cap ({@link #resolvedCap}), or omit when there is none. */
    private static void withMapCap(JsonNode schema, String name, String path, Ctx ctx, IntConsumer build) {
        int max = resolvedCap(schema.get("maxProperties"), name, "maxProperties", ctx);
        if (max < 0) {
            ctx.report().omit(path, "map '" + name + "' has no maxProperties and no default cap — omitted: an a12 "
                    + "repeatable group needs a bounded max ≥ 2. TO DO: set maxProperties, raise --default-repeat-cap, or add by hand");
            return;
        }
        build.accept(max);
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

    // --- allOf of object subschemas (SCHEMAKIT-SPEC §4b): an intersection → merge into one group ---

    /** The object subschemas of {@code allOf}, with each {@code $ref} resolved to its {@code $def} (pure). */
    private static List<JsonNode> allOfObjectSubschemas(JsonNode schema, Ctx ctx) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode sub : schema.path("allOf")) {
            JsonNode resolved = sub.has("$ref") ? ctx.defs().path(defName(sub)) : sub;
            if (resolved.path("properties").isObject()) {
                result.add(resolved);
            }
        }
        return result;
    }

    /** A bare {@code allOf} property (no {@code type}) → a group built from the merged subschemas. */
    private static void addAllOfGroup(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                      String path, Ctx ctx) {
        boolean ownProps = schema.path("properties").isObject() && !schema.get("properties").isEmpty();
        if (!ownProps && allOfObjectSubschemas(schema, ctx).isEmpty()) {
            ctx.report().omit(path, "property '" + name + "' is an allOf with no object subschema carrying "
                    + "properties — omitted (an empty group is invalid). TO DO: it may be a scalar-constraint "
                    + "allOf; add the field by hand");
            return;
        }
        g.group(name, sub -> populate(sub, schema, path, ctx));
        ctx.report().structure(path, "group (allOf merge)");
    }

    // --- oneOf/anyOf of object variants (SCHEMAKIT-SPEC §4b): a12 has no sum type → superset + discriminator ---

    private static JsonNode unionNode(JsonNode schema) {
        JsonNode oneOf = schema.path("oneOf");
        return oneOf.isArray() ? oneOf : schema.path("anyOf");
    }

    /** Any {@code oneOf}/{@code anyOf} present (object or not) — used to flag a cross-kind union after the
     *  all-object case ({@link #isObjectUnion}) has been handled. */
    private static boolean hasUnionNode(JsonNode schema) {
        return (schema.path("oneOf").isArray() && !schema.path("oneOf").isEmpty())
                || (schema.path("anyOf").isArray() && !schema.path("anyOf").isEmpty());
    }

    /**
     * A {@code type} array (SCHEMAKIT-SPEC §4c). A <em>nullable</em> {@code [X, "null"]} DOES map → an
     * optional {@code X} field (the value may be absent). A cross-kind {@code [X, Y, …]} has no a12 form (a
     * field has one kind) → omitted with a corrective to-do. Omit, never block.
     */
    private static void addTypeUnion(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                     boolean isRequired, String groupPath, Ctx ctx) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode t : schema.get("type")) {
            if (!"null".equals(t.asText())) {
                kinds.add(t.asText());
            }
        }
        String path = groupPath + "/" + name;
        if (kinds.size() == 1) {   // nullable X → optional X (the §4c exception that DOES map)
            ObjectNode single = (ObjectNode) schema.deepCopy();
            single.put("type", kinds.get(0));
            addProperty(g, name, single, false, groupPath, ctx);   // nullable ⇒ optional, regardless of `required`
            return;
        }
        ctx.report().omit(path, "property '" + name + "' has a cross-kind type union " + kinds + " — omitted: an "
                + "a12 field has exactly one kind (SCHEMAKIT-SPEC §4c). TO DO: split it into separate fields, or "
                + "pick the primary kind by hand");
    }

    /** A {@code oneOf}/{@code anyOf} whose variants are ALL objects — the only union shape a12 can superset. */
    private static boolean isObjectUnion(JsonNode schema) {
        JsonNode variants = unionNode(schema);
        if (!variants.isArray() || variants.isEmpty()) {
            return false;
        }
        for (JsonNode v : variants) {
            if (!"object".equals(v.path("type").asText("")) && !v.path("properties").isObject()) {
                return false;
            }
        }
        return true;
    }

    private static List<JsonNode> unionVariants(JsonNode schema) {
        List<JsonNode> variants = new ArrayList<>();
        unionNode(schema).forEach(variants::add);
        return variants;
    }

    private static void addUnion(DocumentModelDraft.GroupBuilder g, String name, List<JsonNode> variants,
                                 String path, Ctx ctx, String explicitDiscriminator) {
        switch (ctx.profile().unionStrategy()) {
            case OMIT -> ctx.report().omit(path, "property '" + name + "' is a oneOf/anyOf union of object "
                    + "variants — omitted. TO DO: model the variants as a superset group by hand, or set the "
                    + "union strategy in the MappingProfile (SUPERSET_RULE / FIRST_VARIANT)");
            case FIRST_VARIANT -> {
                g.group(name, sub -> populate(sub, variants.get(0), path, ctx));
                ctx.report().structure(path, "group (first oneOf/anyOf variant only)");
            }
            case SUPERSET_RULE -> buildSuperset(g, name, variants, path, ctx, explicitDiscriminator);
        }
    }

    /** The OpenAPI {@code discriminator.propertyName} on a union schema (OpenAPI dialects only), else null. */
    private static String explicitDiscriminator(JsonNode schema, Ctx ctx) {
        return ctx.dialect().isOpenApi() ? schema.path("discriminator").path("propertyName").asText(null) : null;
    }

    /** The superset: union every variant's fields (all optional), lift a discriminator to an enum + rules. */
    private static void buildSuperset(DocumentModelDraft.GroupBuilder g, String name, List<JsonNode> variants,
                                      String path, Ctx ctx, String explicitDiscriminator) {
        Optional<String> discriminator = findDiscriminator(variants, explicitDiscriminator);
        g.group(name, sub -> {
            Set<String> seen = new LinkedHashSet<>();
            discriminator.ifPresent(disc -> {
                seen.add(disc);
                sub.enumField(disc, e -> discriminatorValues(variants, disc).forEach(e::value));
                ctx.report().structure(path + "/" + disc, "discriminator ENUMERATION");
            });
            for (JsonNode variant : variants) {
                for (Iterator<Map.Entry<String, JsonNode>> it = variant.path("properties").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (seen.add(e.getKey())) {   // first occurrence only; superset fields are all OPTIONAL
                        addProperty(sub, e.getKey(), e.getValue(), false, path, ctx);
                    }
                }
            }
        });
        ctx.report().structure(path, "oneOf/anyOf superset group (" + variants.size() + " variants)");
        if (discriminator.isPresent()) {
            collectDiscriminatorRules(variants, discriminator.get(), path, ctx);
        } else {
            ctx.report().diagnostic("oneOf/anyOf at '" + name + "' merged into a superset of optional fields; "
                    + "cross-variant exclusivity has no discriminator to key on and is NOT structurally enforced. "
                    + "TO DO: add a discriminator (a const field in each variant), or enforce exclusivity by hand");
        }
    }

    /** A discriminator: a property present in every variant that each variant pins to a single value. An explicit
     *  OpenAPI {@code discriminator.propertyName} ({@code explicit}) is preferred when each variant pins it. */
    private static Optional<String> findDiscriminator(List<JsonNode> variants, String explicit) {
        if (explicit != null && !explicit.isBlank() && pinnedInEvery(variants, explicit)) {
            return Optional.of(explicit);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = variants.get(0).path("properties").fields(); it.hasNext(); ) {
            String candidate = it.next().getKey();
            if (pinnedInEvery(variants, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean pinnedInEvery(List<JsonNode> variants, String property) {
        return variants.stream().allMatch(v -> singleValueOf(v.path("properties").path(property)).isPresent());
    }

    /** The single value a variant pins a property to: {@code const}, or a one-element {@code enum}. */
    private static Optional<String> singleValueOf(JsonNode propSchema) {
        if (propSchema.has("const")) {
            return Optional.of(propSchema.get("const").asText());
        }
        JsonNode en = propSchema.path("enum");
        return en.isArray() && en.size() == 1 ? Optional.of(en.get(0).asText()) : Optional.empty();
    }

    private static List<String> discriminatorValues(List<JsonNode> variants, String disc) {
        List<String> values = new ArrayList<>();
        for (JsonNode variant : variants) {
            singleValueOf(variant.path("properties").path(disc))
                    .filter(v -> !values.contains(v))
                    .ifPresent(values::add);
        }
        return values;
    }

    private static void collectDiscriminatorRules(List<JsonNode> variants, String disc, String groupPath, Ctx ctx) {
        for (JsonNode variant : variants) {
            Optional<String> value = singleValueOf(variant.path("properties").path(disc));
            if (value.isEmpty()) {
                continue;
            }
            JsonNode props = variant.path("properties");
            for (JsonNode reqNode : variant.path("required")) {
                String req = reqNode.asText();
                if (req.equals(disc)) {
                    continue;   // the discriminator itself is the enum, not a dependent
                }
                if (!ruleEligibleScalar(props.path(req))) {
                    ctx.report().diagnostic("discriminator rule for '" + req + "' (required when " + disc + "="
                            + value.get() + ") not synthesized — only scalar string/integer/boolean/enum required "
                            + "fields are supported; enforce it by hand");
                    continue;
                }
                ctx.ruleNeeds().add(new RuleSynthesis.DiscriminatedRequired(groupPath, disc, value.get(), req, true));
            }
        }
    }

    /** A required field a discriminator rule can reference: a reliably-imported scalar (a {@code FieldRef}). */
    private static boolean ruleEligibleScalar(JsonNode propSchema) {
        if (propSchema.has("enum")) {
            return true;
        }
        return switch (propSchema.path("type").asText("")) {
            case "string", "integer", "boolean" -> true;   // 'number' import is profile-dependent → excluded
            default -> false;
        };
    }

    private static void addScalar(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                  boolean isRequired, String path, Ctx ctx) {
        Requiredness req = isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL;
        collectNot(name, schema, path, ctx);   // a 'not' const/enum → a forbidding rule (the synthesis resolves the kind)
        if (schema.has("enum") && schema.get("enum").isArray() && !schema.get("enum").isEmpty()) {
            addEnumField(g, name, schema, isRequired);
            ctx.report().structure(path, "ENUMERATION");
            return;
        }
        switch (schema.path("type").asText("")) {
            case "string" -> {
                if (schema.path("format").isTextual()) {
                    addFormattedString(g, name, schema, isRequired, path, ctx);
                } else {
                    addStringField(g, name, schema, isRequired);
                    ctx.report().structure(path, "STRING");
                    if (schema.has("const")) {
                        ctx.ruleNeeds().add(new RuleSynthesis.ConstValue(path, false, schema.get("const").asText()));
                    }
                }
            }
            case "boolean" -> {
                FieldKind kind = ctx.profile().booleanKind() == MappingProfile.BooleanKind.CONFIRM
                        ? FieldKind.CONFIRM : FieldKind.BOOLEAN;
                g.field(name, kind, req);
                ctx.report().structure(path, kind.name());
            }
            case "integer" -> {
                addIntegerField(g, name, schema, isRequired, ctx);
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

    /**
     * A string {@code format} (SCHEMAKIT-SPEC §4b). Temporal formats map to a12's native temporal field
     * KINDS (always — they are first-class a12 types, strictly better than a string); the semantic string
     * formats (email/uuid/…) follow the {@link MappingProfile#format()} strategy.
     */
    private static void addFormattedString(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                           boolean isRequired, String path, Ctx ctx) {
        String format = schema.get("format").asText();
        switch (format) {
            case "date" -> {
                g.dateField(name, d -> { if (isRequired) { d.required(); } });
                ctx.report().structure(path, "DATE (format date)");
            }
            case "date-time" -> {
                g.dateTimeField(name, d -> { if (isRequired) { d.required(); } });
                ctx.report().structure(path, "DATE_TIME (format date-time)");
            }
            case "time" -> {
                g.timeField(name, t -> { if (isRequired) { t.required(); } });
                ctx.report().structure(path, "TIME (format time)");
            }
            default -> addSemanticFormat(g, name, schema, format, isRequired, path, ctx);
        }
    }

    /** A non-temporal {@code format} (email/uuid/…) — governed by the profile's {@link MappingProfile#format()}. */
    private static void addSemanticFormat(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                          String format, boolean isRequired, String path, Ctx ctx) {
        switch (ctx.profile().format()) {
            case PATTERN -> standardRegex(format).ifPresentOrElse(
                    regex -> {
                        g.stringField(name, s -> {
                            if (schema.has("minLength")) {
                                s.minLength(schema.get("minLength").asInt());
                            }
                            if (schema.has("maxLength")) {
                                s.maxLength(schema.get("maxLength").asInt());
                            }
                            s.pattern(regex, name + " must be a valid " + format);
                            if (isRequired) {
                                s.required();
                            }
                        });
                        ctx.report().structure(path, "STRING + pattern (format " + format + ")");
                    },
                    () -> {
                        addStringField(g, name, schema, isRequired);
                        ctx.report().structure(path, "STRING");
                        ctx.report().diagnostic("format '" + format + "' at '" + name + "' has no standard regex "
                                + "— imported as a plain string. TO DO: add a pattern by hand, or set the format "
                                + "strategy to CUSTOM_TYPE");
                    });
            case CUSTOM_TYPE -> {
                g.customField(name, c -> {
                    c.type(format);
                    if (isRequired) {
                        c.required();
                    }
                });
                ctx.report().structure(path, "CUSTOM type '" + format + "' (format " + format + ")");
                ctx.report().diagnostic("format '" + format + "' at '" + name + "' mapped to a custom field type '"
                        + format + "' — register a validator for it at runtime");
            }
            case IGNORE -> {
                addStringField(g, name, schema, isRequired);
                ctx.report().structure(path, "STRING (format '" + format + "' ignored)");
            }
        }
    }

    /** A standard regex for a well-known JSON Schema string {@code format}, where one exists. */
    private static Optional<String> standardRegex(String format) {
        return switch (format) {
            case "email" -> Optional.of("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}");
            case "uuid" -> Optional.of("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
            default -> Optional.empty();
        };
    }

    /** Strict bounds have no inclusive field-config home → collect them as Tier-A+ synthesized rules. */
    private static void collectStrictBounds(JsonNode schema, String path, Ctx ctx) {
        exclusiveBound(schema, true, ctx)
                .ifPresent(v -> ctx.ruleNeeds().add(new RuleSynthesis.ExclusiveBound(path, true, v)));
        exclusiveBound(schema, false, ctx)
                .ifPresent(v -> ctx.ruleNeeds().add(new RuleSynthesis.ExclusiveBound(path, false, v)));
    }

    /** The strict-bound value, honoring the dialect's encoding: OpenAPI 3.0 uses a <em>boolean</em>
     *  {@code exclusiveMinimum} flag that modifies {@code minimum}; JSON Schema / OpenAPI 3.1 use a
     *  <em>numeric</em> {@code exclusiveMinimum}. Empty when there is no strict bound. */
    private static Optional<String> exclusiveBound(JsonNode schema, boolean min, Ctx ctx) {
        JsonNode ex = schema.path(min ? "exclusiveMinimum" : "exclusiveMaximum");
        if (ctx.dialect().isOpenApi30()) {
            String inc = min ? "minimum" : "maximum";
            return ex.asBoolean(false) && schema.has(inc) ? Optional.of(schema.get(inc).asText()) : Optional.empty();
        }
        return ex.isNumber() ? Optional.of(ex.asText()) : Optional.empty();
    }

    /** The inclusive {@code minimum}/{@code maximum} value, suppressed when OpenAPI 3.0 marks it exclusive (then it
     *  rides a strict-bound rule instead — {@link #exclusiveBound}). Empty when absent. */
    private static Optional<String> inclusiveBound(JsonNode schema, boolean min, Ctx ctx) {
        String inc = min ? "minimum" : "maximum";
        if (!schema.has(inc)) {
            return Optional.empty();
        }
        if (ctx.dialect().isOpenApi30() && schema.path(min ? "exclusiveMinimum" : "exclusiveMaximum").asBoolean(false)) {
            return Optional.empty();
        }
        return Optional.of(schema.get(inc).asText());
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
                ctx.report().diagnostic("number '" + name + "' imported as a scale-0 integer (--number-scale INTEGER) "
                        + "— any decimal places are DROPPED; set --number-scale DEFAULT_SCALE to keep them");
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
                ctx.report().diagnostic("number '" + name + "' has no scale in the schema — imported at the "
                        + "default scale " + scale + " (a guess; set --default-scale, --number-scale, or the "
                        + "field's scale by hand if a different precision is needed)");
            }
        }
    }

    /** An integer field (scale 0); inclusive {@code minimum}/{@code maximum} import to number value bounds (an
     *  OpenAPI-3.0 exclusive bound is suppressed here and rides a strict-bound rule instead — {@link #inclusiveBound}). */
    private static void addIntegerField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                        boolean isRequired, Ctx ctx) {
        Optional<String> minimum = inclusiveBound(schema, true, ctx);
        Optional<String> maximum = inclusiveBound(schema, false, ctx);
        g.numberField(name, n -> {
            n.fractionDigits(0);
            minimum.ifPresent(n::min);
            maximum.ifPresent(n::max);
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

    /** Resolve a local {@code $ref} by JSON Pointer against the document root — handles both
     *  {@code #/$defs/X} (JSON Schema) and {@code #/components/schemas/X} (OpenAPI). External refs are unsupported. */
    private static JsonNode resolveRef(Ctx ctx, JsonNode schema) {
        String ref = schema.path("$ref").asText("");
        return ref.startsWith("#/") ? ctx.root().at(ref.substring(1)) : MissingNode.getInstance();
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

    /** Parse the input as JSON first (exact behavior for JSON documents), then YAML (a JSON superset) so
     *  OpenAPI/JSON-Schema documents in either form import. The generated DM-JSON re-parse always hits the JSON path. */
    private static JsonNode parse(String document) {
        try {
            return JSON.readTree(document);
        } catch (Exception jsonError) {
            try {
                return YAML.readTree(document);
            } catch (Exception yamlError) {
                throw new IllegalArgumentException("not valid JSON or YAML: " + jsonError.getMessage(), jsonError);
            }
        }
    }
}
