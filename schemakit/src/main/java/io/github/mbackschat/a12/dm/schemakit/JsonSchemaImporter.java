package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.addMap;
import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.addScalarArray;
import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.collectContains;
import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.collectMinItems;
import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.hasMapExtras;
import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.isMap;
import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.resolvedCap;
import static io.github.mbackschat.a12.dm.schemakit.ConditionalRuleNeeds.collectIfThen;
import static io.github.mbackschat.a12.dm.schemakit.ConditionalRuleNeeds.collectObjectNot;
import static io.github.mbackschat.a12.dm.schemakit.ImportSkeleton.identifier;
import static io.github.mbackschat.a12.dm.schemakit.ScalarFields.addScalar;
import static io.github.mbackschat.a12.dm.schemakit.ScalarFields.collectDependentRequired;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.addAllOfGroup;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.addTypeUnion;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.addUnion;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.allOfObjectSubschemas;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.buildRootSuperset;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.explicitDiscriminator;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.hasUnionNode;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.isObjectUnion;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.unionVariants;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private static final ObjectMapper JSON = JsonMapper.builder()
            // A JSON Schema is not an A12 shape, so its leniency is ours to choose and comments stay refused — but
            // its NUMBERS land in the DM-JSON this importer writes, so a declared `minimum`/`maximum` must keep the
            // authored scale. A default mapper builds a DoubleNode: 0.50 became 0.5 and a beyond-double bound became
            // 1.2345678901234568E+21 in the emitted model. API-FINDINGS 103.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            // Same semantic bound path as JSON above: OpenAPI/YAML `minimum: 0.50` must reach DM-JSON with the
            // declared scale intact, rather than becoming a DoubleNode before the importer sees it.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);   // OpenAPI specs are often YAML
    private static final String ROOT_GROUP = "Root";
    static final String ROW_FIELD = "value";   // the single value field in a scalar-array / map row group
    static final String MAP_KEY_FIELD = "key";   // the entry-key field in a map's {key, value} row group
    static final Set<String> ANNOTATION_KEYWORDS = Set.of(
            "$comment", "title", "description", "default", "deprecated", "readOnly", "writeOnly", "examples");

    /**
     * A transcoded model, its report, and the gate that cleared it.
     *
     * <p>{@code consistencyGate} is present only when rule synthesis ran <em>and</em> nothing rewrote the tree
     * afterwards: Tier-A+ synthesis assembles through {@code BoundModel}, which re-binds through the oracle, but a
     * purely structural transcode never binds at all and carriage injection edits the JSON after the bind. So the
     * receipt tracks what actually happened to <em>this</em> model rather than what the importer usually does.
     *
     * <p>The constructor is private because this type is a gate <b>witness</b>:
     * {@code ConsistencyEvidence.of(ImportResult)} publishes the receipt as provenance, so a value anyone can
     * construct would let a caller claim {@code KERNEL_CONFIRMED} over an untranscoded model. Same reason, and
     * the same shape, as {@code ModelReport}/{@code ModelDiff} — see {@code WitnessTypesAreUnforgeableTest}.
     */
    public static final class ImportResult {

        private final String dmJson;
        private final TranscodeReport report;
        private final Optional<Verification> consistencyGate;

        private ImportResult(String dmJson, TranscodeReport report,
                             Optional<Verification> consistencyGate) {
            this.dmJson = dmJson;
            this.report = report;
            this.consistencyGate = consistencyGate;
        }

        /** The transcoded DM-JSON. */
        public String dmJson() {
            return dmJson;
        }

        /** What the transcode did, kept, and dropped. */
        public TranscodeReport report() {
            return report;
        }

        /** The gate that cleared {@link #dmJson()} — empty unless rule synthesis bound it and nothing rewrote it. */
        public Optional<Verification> consistencyGate() {
            return consistencyGate;
        }
    }

    /** Per-transcode state threaded through the recursion: the whole document {@code root} (for JSON-Pointer
     *  {@code $ref} resolution), the resolved {@code dialect}, the reusable-definitions pool, the inline stack
     *  (recursion detection), the report, the Tier-A+ rule-synthesis needs, and the customization profile. */
    record Ctx(JsonNode root, Dialect dialect, JsonNode defs, Deque<String> inlining,
                       TranscodeReport.Builder report, List<RuleSynthesis.Need> ruleNeeds, MappingProfile profile,
                       boolean bundle, String modelId, Set<String> breakEdges,
                       Map<String, String> indexFields, Set<String> rootPaths) {

        /** Would mounting {@code targetId} from this model close an include cycle (break it by inlining instead)? */
        boolean isCycleEdge(String targetId) {
            return breakEdges.contains(modelId + "->" + targetId);
        }
    }

    /** A multi-model bundle: each {@code components/schemas} entry as its own model (id → result), wired by
     *  includes/mounts (SCHEMAKIT-SPEC §4g/§4e). */
    public record BundleResult(Map<String, ImportResult> models) {}

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
     * lists the components, SCHEMAKIT-SPEC §4g). For the whole bundle at once, see {@link #importBundle}.
     */
    public ImportResult importSchema(String jsonSchema, MappingProfile profile, Dialect requestedDialect, String component) {
        JsonNode root = parse(jsonSchema);
        Dialect dialect = resolveDialect(requestedDialect, root);
        TranscodeReport.Builder report = TranscodeReport.builder();
        noteDialect(report, dialect, requestedDialect);
        JsonNode rootSchema = entryPointSchema(root, component);
        String modelId = identifier(component != null ? component : rootSchema.path("title").asText(null));
        return buildModel(root, rootSchema, modelId, dialect, profile, report, false, Set.of(), null);
    }

    /**
     * Import a whole OpenAPI document as a <b>bundle</b>: every {@code components/schemas} entry becomes its own
     * model, and an object cross-reference becomes an a12 <b>mount</b> (include) instead of being inlined — so a
     * shared definition is single-sourced and a recursive {@code $ref} becomes a self-mount (SCHEMAKIT-SPEC §4g).
     */
    public BundleResult importBundle(String openApiDoc, MappingProfile profile, Dialect requestedDialect) {
        JsonNode root = parse(openApiDoc);
        Dialect dialect = resolveDialect(requestedDialect, root);
        JsonNode schemas = root.path("components").path("schemas");
        if (!schemas.isObject() || schemas.isEmpty()) {
            throw new IllegalArgumentException("no components/schemas to import as a bundle");
        }
        Set<String> breakEdges = cycleBreakEdges(schemas, root);   // inline (not mount) one edge per include cycle
        // id → schema, for the rule-synthesis resolver: a mounting component loads its sibling's STRUCTURE skeleton
        // (built on demand, no rules → no recursion) so its own typed rules can be authored. Order-independent.
        Map<String, JsonNode> schemaById = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = schemas.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            schemaById.put(identifier(e.getKey()), e.getValue());
        }
        ModelResolver ruleResolver = id -> {
            JsonNode sch = schemaById.get(id);
            return sch == null ? null
                    : buildStructure(root, sch, id, dialect, profile, TranscodeReport.builder(), true, breakEdges).dmJson();
        };
        Map<String, ImportResult> models = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = schemas.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            TranscodeReport.Builder report = TranscodeReport.builder();
            noteDialect(report, dialect, requestedDialect);
            models.put(identifier(e.getKey()),
                    buildModel(root, e.getValue(), identifier(e.getKey()), dialect, profile, report, true, breakEdges, ruleResolver));
        }
        return new BundleResult(models);
    }

    /** The include edges to break (inline instead of mount) so no cycle survives — the kernel rejects cyclic
     *  includes (like self-includes). A DFS over the cross-component object-ref graph collects each back-edge. */
    private static Set<String> cycleBreakEdges(JsonNode schemas, JsonNode root) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = schemas.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            graph.put(identifier(e.getKey()), objectComponentRefs(e.getValue(), root));
        }
        Set<String> breakEdges = new LinkedHashSet<>();
        Set<String> gray = new LinkedHashSet<>();
        Set<String> done = new HashSet<>();
        for (String node : graph.keySet()) {
            dfsBackEdges(node, graph, gray, done, breakEdges);
        }
        return breakEdges;
    }

    private static void dfsBackEdges(String node, Map<String, Set<String>> graph, Set<String> gray,
                                     Set<String> done, Set<String> breakEdges) {
        if (done.contains(node)) {
            return;
        }
        gray.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (gray.contains(next)) {
                breakEdges.add(node + "->" + next);   // a back-edge to a node on the current path → a cycle
            } else if (!done.contains(next)) {
                dfsBackEdges(next, graph, gray, done, breakEdges);
            }
        }
        gray.remove(node);
        done.add(node);
    }

    /** The object components a schema references (the edges that would become mounts) — a recursive {@code $ref} scan. */
    private static Set<String> objectComponentRefs(JsonNode schema, JsonNode root) {
        Set<String> refs = new LinkedHashSet<>();
        collectObjectRefs(schema, root, refs);
        return refs;
    }

    private static void collectObjectRefs(JsonNode node, JsonNode root, Set<String> refs) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            if (node.has("$ref")) {
                String defName = defName(node);
                if ("object".equals(root.path("components").path("schemas").path(defName).path("type").asText(""))) {
                    refs.add(identifier(defName));
                }
            }
            node.fields().forEachRemaining(e -> collectObjectRefs(e.getValue(), root, refs));
        } else if (node.isArray()) {
            node.forEach(e -> collectObjectRefs(e, root, refs));
        }
    }

    /** State the resolved dialect in the report (only for an OpenAPI dialect — JSON Schema is the default). */
    private static void noteDialect(TranscodeReport.Builder report, Dialect dialect, Dialect requested) {
        if (dialect.isOpenApi()) {
            report.diagnostic("dialect: " + dialect + (requested == Dialect.AUTO ? " (auto-detected)" : " (forced)"));
        }
    }

    private static void reportRootFallback(JsonNode schema, TranscodeReport.Builder report) {
        if (Carriage.groupObject(schema).has(Carriage.X_ROOT_GROUP_NAME)) {
            report.omit("/" + Carriage.X_ROOT_GROUP_NAME,
                    "malformed root annotation — fallback to the ordinary Root group preserves the structure");
        }
        for (var fields = schema.path("properties").fields(); fields.hasNext();) {
            var field = fields.next();
            if (Carriage.groupObject(field.getValue()).has(Carriage.X_ROOT_GROUP_NAME)) {
                report.omit("/properties/" + field.getKey() + "/" + Carriage.X_ROOT_GROUP_NAME,
                        "mixed or malformed root annotations — fallback to the ordinary Root group preserves the structure");
            }
        }
    }

    /** A model's STRUCTURE (DM-JSON) before rule synthesis, plus the rule needs to synthesize onto it. Splitting
     *  this out lets a bundle build a sibling's skeleton on demand to resolve a mount (see {@link #importBundle}). */
    private record Structure(String dmJson, List<RuleSynthesis.Need> ruleNeeds) {}

    /** Build a model's structure from {@code rootSchema} (no rules yet). In {@code bundle} mode an object
     *  cross-reference is mounted as an include rather than inlined (the §4e multi-model wiring). */
    private static Structure buildStructure(JsonNode root, JsonNode rootSchema, String modelId, Dialect dialect,
                                            MappingProfile profile, TranscodeReport.Builder report, boolean bundle,
                                            Set<String> breakEdges) {
        Map<String, JsonNode> roots = Carriage.rootCarriers(rootSchema);
        if (roots.isEmpty()) reportRootFallback(rootSchema, report);
        boolean rootUnion = isObjectUnion(rootSchema);   // a top-level oneOf/anyOf of objects → a root superset
        if (roots.isEmpty() && !rootUnion && !"object".equals(rootSchema.path("type").asText(""))) {
            String type = rootSchema.path("type").asText("");
            throw new IllegalArgumentException("import supports a top-level object or oneOf/anyOf-of-objects schema; "
                    + "got type=" + (type.isEmpty() ? "<none>" : type));
        }
        JsonNode defsPool = dialect.isOpenApi() ? root.path("components").path("schemas") : root.path("$defs");
        Ctx ctx = new Ctx(root, dialect, defsPool, new ArrayDeque<>(), report, new ArrayList<>(), profile,
                bundle, modelId, breakEdges, new LinkedHashMap<>(), new LinkedHashSet<>());
        DocumentModelDraft.Builder b = ImportSkeleton.draft(modelId);
        declareLeafTypedefs(b, ctx);
        if (roots.isEmpty()) {
            ctx.rootPaths().add("/" + ROOT_GROUP);
            b.rootGroup(ROOT_GROUP, g -> {
                if (rootUnion) {
                    buildRootSuperset(g, unionVariants(rootSchema), "/" + ROOT_GROUP, ctx, explicitDiscriminator(rootSchema, ctx));
                } else {
                    populate(g, rootSchema, "/" + ROOT_GROUP, ctx);
                }
            });
        } else {
            roots.keySet().forEach(name -> ctx.rootPaths().add("/" + name));
            roots.forEach((name, carrier) -> {
                String path = "/" + name;
                int max = "array".equals(carrier.path("type").asText())
                        ? resolvedCap(carrier.get("maxItems"), name, "maxItems", ctx) : 0;
                if (max < 0) {
                    report.omit(path, "root array has no repeatability cap — set maxItems or --default-repeat-cap");
                    ctx.rootPaths().remove(path);
                    return;
                }
                b.rootGroup(name, g -> {
                    if (max > 0) g.repeatable(max);
                    populate(g, Carriage.groupObject(carrier), path, ctx);
                });
                report.mapped(path + "/" + Carriage.X_ROOT_GROUP_NAME, path, TranscodeReport.Tier.CARRIED);
            });
        }
        String dmJson = b.build().toJson();
        if (!ctx.indexFields().isEmpty()) {
            ObjectNode tree = (ObjectNode) parse(dmJson);
            Carriage.restoreIndexFields(tree, ctx.indexFields(), report);
            dmJson = tree.toString();
        }
        return new Structure(dmJson, ctx.ruleNeeds());
    }

    /** Build one full model from {@code rootSchema}: structure, then synthesized rules (Tier A+), then carried
     *  rules/computations (Tier B/C). {@code ruleResolver} resolves a bundle sibling so a mounting component can
     *  load for rule synthesis — {@code null} for a single self-contained model. */
    private static ImportResult buildModel(JsonNode root, JsonNode rootSchema, String modelId, Dialect dialect,
                                           MappingProfile profile, TranscodeReport.Builder report, boolean bundle,
                                           Set<String> breakEdges, ModelResolver ruleResolver) {
        Structure s = buildStructure(root, rootSchema, modelId, dialect, profile, report, bundle, breakEdges);
        List<RuleSynthesis.Need> needs = s.ruleNeeds();
        if (rootSchema.has(Carriage.X_RULE)) {
            // An a12-origin schema carries its rules verbatim (Tier B); native `minItems` is the foreign-facing
            // projection of one such carried rule, so re-synthesizing it would DUPLICATE it. Carriage is
            // authoritative — drop the projected-keyword need (SCHEMAKIT-SPEC §4f).
            needs = needs.stream().filter(n -> !(n instanceof RuleSynthesis.MinItemsCount)).toList();
        }
        RuleSynthesis.Applied synthesized = RuleSynthesis.applied(s.dmJson(), needs, report, ruleResolver); // Tier A+
        String dmJson = synthesized.dmJson();
        var gate = synthesized.consistencyGate();
        if (Carriage.carriesElements(rootSchema)) {
            ObjectNode tree = (ObjectNode) parse(dmJson);     // restore carried rules/computations/criteria (Tier B/C)
            Carriage.inject(tree, rootSchema, report);
            dmJson = tree.toString();
            // Carriage rewrote the tree AFTER the gate, so the receipt no longer describes what we return.
            // Dropping it under-reports; keeping it would attest a model the oracle never saw.
            gate = Optional.empty();
        }
        return new ImportResult(dmJson, report.build(), gate);
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
        if (root.has("type") || root.has("properties") || root.has("allOf") || root.has("$ref")
                || root.has("oneOf") || root.has("anyOf")) {
            return root;   // a bare schema (incl. a single extracted OpenAPI Schema Object, or a top-level union)
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
    static void populate(DocumentModelDraft.GroupBuilder g, JsonNode objectSchema,
                                 String groupPath, Ctx ctx) {
        JsonNode indexField = objectSchema.get(Carriage.X_INDEX_FIELD_NAME);
        if (indexField != null) {
            if (indexField.isTextual()) {
                ctx.indexFields().put(groupPath, indexField.textValue());
            } else {
                ctx.report().omit(groupPath + "/" + Carriage.X_INDEX_FIELD_NAME,
                        Carriage.X_INDEX_FIELD_NAME + " at '" + groupPath + "' must be a string — omitted. "
                                + "Restore the group's relative index-field reference by hand");
            }
        }
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
        collectObjectNot(objectSchema, groupPath, ctx);
        if (!allOf.isEmpty()) {
            ctx.report().diagnostic("merged " + allOf.size() + " allOf object subschema(s) into '" + groupPath
                    + "' — all constraints apply together (intersection)");
        }
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

    static void addProperty(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                    boolean isRequired, String groupPath, Ctx ctx) {
        String path = groupPath + "/" + name;
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
            case "array" -> addArray(g, name, schema, path,
                    required && ctx.rootPaths().contains(groupPath), ctx);
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
            case "object" -> {
                String alias = identifier(defName);
                if (ctx.bundle() && !alias.equals(ctx.modelId()) && !ctx.isCycleEdge(alias)) {   // §4e: cross-component ref → a mount
                    g.mount(name, alias);
                    ctx.report().structure(path, "mount → " + alias + " (include)");
                } else {   // self-ref / a cycle-breaking edge (kernel forbids cyclic includes) / single mode → inline
                    if (ctx.isCycleEdge(alias)) {
                        ctx.report().diagnostic("'" + alias + "' inlined (not mounted) at '" + path
                                + "' to break an include cycle — the kernel forbids cyclic includes");
                    }
                    inlineObjectDef(g, name, defName, def, path, ctx);
                }
            }
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
                                 String path, boolean required, Ctx ctx) {
        if (schema.path("uniqueItems").asBoolean(false)) {
            // a repeatable group is built below (object or scalar array) → key its rows for uniqueness; the
            // synthesis resolves the actual row fields post-build and skips if the group/fields didn't import.
            ctx.ruleNeeds().add(new RuleSynthesis.UniqueItems(path));
        }
        collectMinItems(schema, path, required, ctx);   // minItems → a NumberOfFilledGroups(G*) < N rule (both array kinds)
        collectContains(schema, path, required, ctx);   // contains/minContains/maxContains → a filtered-count rule (simple predicate)
        JsonNode items = schema.path("items");
        if (items.has("$ref")) {   // array of $ref items (ubiquitous in OpenAPI) — resolve before the scalar/object dispatch
            addRefItemsArray(g, name, schema, items, path, ctx);
            return;
        }
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

    /** An {@code array} whose {@code items} is a {@code $ref}: resolve it, then — for an object def — a repeatable
     *  group (single mode, inlined) or a repeatable **mount** (bundle mode, cross-component); for a leaf def, a
     *  scalar array of the resolved leaf. */
    private static void addRefItemsArray(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                         JsonNode items, String path, Ctx ctx) {
        String defName = defName(items);
        JsonNode def = resolveRef(ctx, items);
        if (def.isMissingNode()) {
            ctx.report().omit(path, "array '" + name + "' items $ref → unresolved '" + items.path("$ref").asText()
                    + "' — omitted. TO DO: check the ref target, then add the items by hand");
            return;
        }
        if (!"object".equals(def.path("type").asText(""))) {
            addScalarArray(g, name, schema, def, path, ctx);   // array of a leaf typedef → a scalar array of that leaf
            return;
        }
        int max = resolvedCap(schema.get("maxItems"), name, "maxItems", ctx);
        if (max < 0) {
            ctx.report().omit(path, "array '" + name + "' (items $ref) has no maxItems and no default cap — omitted. "
                    + "TO DO: set maxItems, raise --default-repeat-cap, or add by hand");
            return;
        }
        String alias = identifier(defName);
        if (ctx.bundle() && !alias.equals(ctx.modelId()) && !ctx.isCycleEdge(alias)) {   // §4e: array of a cross-component object → a repeatable mount
            g.mount(name, alias, max);
            ctx.report().structure(path, "repeatable mount → " + alias + " (max " + max + ")");
        } else {
            g.group(name, sub -> {
                sub.repeatable(max);
                populate(sub, def, path, ctx);
            });
            ctx.report().structure(path, "repeatable group (items $ref " + defName + " inlined, max " + max + ")");
        }
    }

    static String defName(JsonNode schema) {
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
