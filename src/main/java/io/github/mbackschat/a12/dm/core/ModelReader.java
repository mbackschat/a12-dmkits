package io.github.mbackschat.a12.dm.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The <b>kernel-free</b> DM-JSON assembly layer (kernel-decoupling Stage 2): parse a DM-JSON model, <b>expand
 * its includes</b> (mount an included model's content under the base's {@code modelAlias} group), <b>resolve
 * imported type-definitions</b> to effective concrete field types, and index every element by its
 * <b>name-path</b> — all without touching {@code com.mgmtp.a12.kernel.*}. It is the clean-room reimplementation
 * of the kernel's {@code IDocumentModelService.expand} (the assembly half of {@link ModelLoader}); the
 * consistency gate ({@code checkConsistency}, R3) stays with the real kernel behind {@link ModelLoader#load}.
 *
 * <h2>What "expand" produces (source-verified against the kernel, KERNEL-FINDINGS "DocumentModel expansion")</h2>
 * <ul>
 *   <li><b>Include mounting.</b> A mount point is a <em>pre-existing</em> group in the base carrying
 *       {@code Group.modelAlias} with no inline elements. Mounting appends the <b>children of the included
 *       model's root group</b> onto that mount group — the included root group itself is discarded (only its
 *       {@code usageType} is copied up). So an included field lands at {@code /<mountGroupName>/<childName>};
 *       the included model's own root-group name <b>never appears</b> in a path. {@code includeLevel}:
 *       {@code SINGLE_RG} (default) mounts the single root group's children; {@code MODEL_ROOT} mounts every
 *       top-level group. {@code excludeRules}/{@code excludeComputations} drop the included model's
 *       rules/computations before mounting (inherited down the include chain; attachment-group children are
 *       never stripped).</li>
 *   <li><b>Type-definition resolution.</b> Every {@code content.typeDefinitions} entry (base + included +
 *       imported) is keyed by {@code id}; each field whose {@code fieldType.type == "TypeDefType"} is
 *       <b>inlined</b> to the referenced definition's concrete {@code fieldType} (transitive, cycle-guarded).
 *       A dangling reference is left as-is (its kind is {@link ValueKind#UNKNOWN}, matching the kernel's
 *       empty {@code getEffectiveType()}).</li>
 *   <li><b>Id normalization.</b> Every element {@code id} in the expanded tree is rewritten to its name-path
 *       ({@code /Group/Field}). Real kernel models carry opaque hash ids (and the kernel prefixes included
 *       ids on mount); a rule condition, the interpreter's loader, and this index all address by name-path
 *       (F-IDX), so the expanded form the reader emits is name-path-keyed and self-contained.</li>
 * </ul>
 *
 * <p>The {@linkplain #toJson() expanded DM-JSON} the reader emits is therefore self-contained, typedef-inlined
 * and name-path-keyed — the interpreter-ready form the real-corpus sweep already validates
 * ({@code adapter.laws.CorpusModelPreparer}). {@link ModelIndex} is the typed view over it.
 */
public final class ModelReader {

    /**
     * Numbers are parsed as {@link BigDecimal} with trailing zeros PRESERVED — so a declared {@code minValue}/
     * {@code maxValue} reads back at the authored literal's exact scale, matching the kernel (which keeps the
     * literal verbatim; the CLI renders it as-is). A plain mapper drops {@code 50.00} to a {@code double}
     * {@code 50.0}, and {@code USE_BIG_DECIMAL_FOR_FLOATS} alone still strips ({@code 50.00} → {@code 5E+1})
     * unless {@code STRIP_TRAILING_BIGDECIMAL_ZEROES} is disabled.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** Included rules/computations we mounted but did NOT rewrite (see {@link #includedRulesNeedingRelativization}). */
    private final ObjectNode expanded;
    private final Map<String, JsonNode> byPath;
    private final List<String> locales;
    private final List<String> includedRulesNeedingRelativization;

    private ModelReader(ObjectNode expanded, Map<String, JsonNode> byPath, List<String> locales,
            List<String> includedRulesNeedingRelativization) {
        this.expanded = expanded;
        this.byPath = byPath;
        this.locales = locales;
        this.includedRulesNeedingRelativization = includedRulesNeedingRelativization;
    }

    /**
     * Parse, expand (includes + imported type-definitions), inline effective types, normalize ids, and index by
     * name-path. {@code resolver} serves an included / imported model's DM-JSON by its reference string; a
     * self-contained model never consults it (use {@link ReferenceResolvers#selfContained()}).
     *
     * @throws UnresolvedReferenceException if the resolver cannot serve a referenced model
     * @throws IllegalArgumentException     if the DM-JSON is not parseable, or an include is structurally illegal
     */
    public static ModelReader expand(String dmJson, ModelResolver resolver) {
        ObjectNode root = parse(dmJson);
        Map<String, JsonNode> typeDefs = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        expandContent(root, resolver, typeDefs, new HashSet<>(), warnings);
        JsonNode modelRoot = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT);
        inlineTypeDefs(modelRoot, typeDefs, new HashSet<>());
        normalizeIds(modelRoot, "");
        stripVestigialModelReferences(root);
        Map<String, JsonNode> byPath = new LinkedHashMap<>();
        indexRootGroups(modelRoot, byPath);
        return new ModelReader(root, byPath, readLocales(root), List.copyOf(warnings));
    }

    // ── outputs ─────────────────────────────────────────────────────────────────────────────────────────

    /** The expanded, self-contained, name-path-keyed DM-JSON — the interpreter-ready form (A4 pre-expansion). */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(expanded);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to emit expanded DM-JSON", e);
        }
    }

    /** The model's declared locales (header {@code locales}), each as a Java locale string (e.g. {@code en_US}). */
    public List<String> locales() {
        return locales;
    }

    /**
     * The name-paths of the rules/computations mounted from an <b>included</b> model that this reader carried
     * <em>verbatim</em> — i.e. whose conditions the kernel would relativize (the vanished included-root-group
     * name) but the reader does not (yet) rewrite. Empty for the common case (rules live on the base model,
     * referencing mounted fields — those need no rewrite). A consumer that must not silently mis-evaluate
     * (the CLI eval pre-expansion, A4 / IG67) refuses when this is non-empty rather than feed possibly-wrong
     * conditions to the engine.
     */
    public List<String> includedRulesNeedingRelativization() {
        return includedRulesNeedingRelativization;
    }

    // ── the name-path index (package-private; ModelIndex is the typed view) ───────────────────────────────

    /** The element node (a {@code Field}/{@code Group} DM-JSON node) at {@code namePath}, or {@code null}. */
    JsonNode element(String namePath) {
        return byPath.get(namePath);
    }

    /** Every indexed name-path (fields + groups), in model order. */
    Set<String> paths() {
        return byPath.keySet();
    }

    // ── expansion ─────────────────────────────────────────────────────────────────────────────────────────

    /** Collect this model's type-definitions, resolve header typedef imports, then expand every include mount. */
    private static void expandContent(ObjectNode root, ModelResolver resolver, Map<String, JsonNode> typeDefs,
            Set<String> visiting, List<String> warnings) {
        JsonNode content = root.path(DmKeys.CONTENT);
        collectTypeDefs(content, typeDefs);
        // Header "typeDefinitions"-purpose imports are ingested only when the model declares no local ones
        // (the kernel's "a mixture of definition ways is not allowed" constraint).
        if (content.path(DmKeys.TYPE_DEFINITIONS).isEmpty()) {
            importTypeDefs(root, resolver, typeDefs, visiting);
        }
        JsonNode rootGroups = content.path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS);
        expandElements(rootGroups, root, resolver, typeDefs, visiting, warnings);
    }

    /** Merge {@code content.typeDefinitions} into {@code typeDefs} (keyed by id; first wins — the kernel dedups
     *  identical ids across models). */
    private static void collectTypeDefs(JsonNode content, Map<String, JsonNode> typeDefs) {
        for (JsonNode td : content.path(DmKeys.TYPE_DEFINITIONS)) {
            String id = td.path(DmKeys.ID).asText("");
            JsonNode ft = td.path(DmKeys.FIELD_TYPE);
            if (!id.isEmpty() && !ft.isMissingNode()) {
                typeDefs.putIfAbsent(id, ft);
            }
        }
    }

    /** Resolve each header {@code modelReferences} entry with {@code purpose == "typeDefinitions"} and merge the
     *  referenced model's type-definitions (recursively). */
    private static void importTypeDefs(ObjectNode root, ModelResolver resolver, Map<String, JsonNode> typeDefs,
            Set<String> visiting) {
        for (JsonNode ref : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (!"typeDefinitions".equals(ref.path(DmKeys.PURPOSE).asText())) {
                continue;
            }
            String reference = ref.path(DmKeys.REFERENCE).asText();
            if (reference.isEmpty() || visiting.contains(reference)) {
                continue;
            }
            ObjectNode importedRoot = resolve(resolver, reference);
            visiting.add(reference);
            collectTypeDefs(importedRoot.path(DmKeys.CONTENT), typeDefs);
            importTypeDefs(importedRoot, resolver, typeDefs, visiting);   // transitive imports
            visiting.remove(reference);
        }
    }

    /** Walk an {@code elements}/{@code rootGroups} array, expanding each mount group in place. */
    private static void expandElements(JsonNode elements, ObjectNode baseRoot, ModelResolver resolver,
            Map<String, JsonNode> typeDefs, Set<String> visiting, List<String> warnings) {
        if (!elements.isArray()) {
            return;
        }
        for (JsonNode node : elements) {
            if (!ElementType.GROUP.matches(node) || !(node instanceof ObjectNode groupNode)) {
                continue;
            }
            ObjectNode body = (ObjectNode) groupNode.path(ElementType.GROUP.wireName());
            String alias = body.path(DmKeys.MODEL_ALIAS).asText("");
            JsonNode existing = body.path(DmKeys.ELEMENTS);
            // A modelAlias group is an unexpanded mount only while its elements are empty — the kernel's
            // idempotency guard (an already-mounted group is not re-expanded).
            if (!alias.isEmpty() && (existing.isMissingNode() || existing.isEmpty())) {
                mount(groupNode, body, alias, baseRoot, resolver, typeDefs, visiting, warnings);
            } else {
                expandElements(existing, baseRoot, resolver, typeDefs, visiting, warnings);
            }
        }
    }

    /** Mount the model referenced by {@code alias} into {@code mountGroup}: append the included root group's
     *  children (SINGLE_RG) or every top-level group (MODEL_ROOT), after fully expanding the included model. */
    private static void mount(ObjectNode mountGroup, ObjectNode mountBody, String alias, ObjectNode baseRoot,
            ModelResolver resolver, Map<String, JsonNode> typeDefs, Set<String> visiting, List<String> warnings) {
        String reference = referenceForAlias(baseRoot, alias);
        if (visiting.contains(reference)) {
            throw new IllegalArgumentException("include cycle detected at reference \"" + reference + "\"");
        }
        ObjectNode includedRoot = resolve(resolver, reference);
        visiting.add(reference);
        expandContent(includedRoot, resolver, typeDefs, visiting, warnings);
        visiting.remove(reference);

        boolean modelRoot = "MODEL_ROOT".equals(mountBody.path("includeLevel").asText());
        boolean excludeRules = mountBody.path("excludeRules").asBoolean(false);
        boolean excludeComputations = mountBody.path("excludeComputations").asBoolean(false);
        ArrayNode includedRootGroups = (ArrayNode) includedRoot.path(DmKeys.CONTENT)
                .path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS);

        List<JsonNode> toMount = new ArrayList<>();
        if (modelRoot) {
            // MODEL_ROOT: every top-level group becomes a child of the mount (keeping its own name).
            includedRootGroups.forEach(toMount::add);
        } else if (!includedRootGroups.isEmpty()) {
            // SINGLE_RG (default): the single root group's children become the mount's children; the root group
            // itself is discarded — only its usageType is copied onto the mount group.
            JsonNode includedRootGroup = includedRootGroups.get(0);
            JsonNode usageType = includedRootGroup.path(ElementType.GROUP.wireName()).path("usageType");
            if (!usageType.isMissingNode()) {
                mountBody.set("usageType", usageType);
            }
            includedRootGroup.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS).forEach(toMount::add);
        }

        ArrayNode mountElements = mountBody.putArray(DmKeys.ELEMENTS);
        for (JsonNode child : toMount) {
            ObjectNode kept = filterRulesAndComputations((ObjectNode) child, excludeRules, excludeComputations);
            if (kept != null) {
                mountElements.add(kept);
                recordIncludedRulesNeedingRelativization(kept, warnings);
            }
        }
        // The include directives are now consumed — drop them so the expanded group is a plain, self-contained
        // group (the kernel likewise doesn't re-expand a group once mounted).
        mountBody.remove(List.of(DmKeys.MODEL_ALIAS, "includeLevel", "excludeRules", "excludeComputations"));
    }

    /** Resolve the mount alias to its referenced-model string via the base header's {@code modelReferences};
     *  falls back to the alias itself when no entry declares a distinct {@code reference}. */
    private static String referenceForAlias(ObjectNode baseRoot, String alias) {
        for (JsonNode ref : baseRoot.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (alias.equals(ref.path(DmKeys.ALIAS).asText())) {
                String reference = ref.path(DmKeys.REFERENCE).asText();
                return reference.isEmpty() ? alias : reference;
            }
        }
        return alias;
    }

    /** Drop {@code Rule}/{@code Computation} elements per the mount's exclude flags (recursively, but never under
     *  an {@code attachment}-usage group — the kernel keeps a template's mandated rules). Returns a copy, or
     *  {@code null} if this node is itself an excluded rule/computation. */
    private static ObjectNode filterRulesAndComputations(ObjectNode node, boolean excludeRules,
            boolean excludeComputations) {
        ElementType type = ElementType.of(node);
        if (type == ElementType.RULE && excludeRules) {
            return null;
        }
        if (type == ElementType.COMPUTATION && excludeComputations) {
            return null;
        }
        if (type == ElementType.GROUP) {
            ObjectNode body = (ObjectNode) node.path(ElementType.GROUP.wireName());
            boolean attachment = "attachment".equals(body.path("usageType").asText());
            JsonNode elements = body.path(DmKeys.ELEMENTS);
            if (!attachment && elements.isArray()) {
                ArrayNode filtered = MAPPER.createArrayNode();
                for (JsonNode child : elements) {
                    ObjectNode kept = filterRulesAndComputations((ObjectNode) child, excludeRules, excludeComputations);
                    if (kept != null) {
                        filtered.add(kept);
                    }
                }
                body.set(DmKeys.ELEMENTS, filtered);
            }
        }
        return node;
    }

    /** Record any mounted included rule/computation (whose condition the kernel would relativize) so a
     *  correctness-critical consumer can refuse rather than mis-evaluate (A4 / IG67). */
    private static void recordIncludedRulesNeedingRelativization(JsonNode node, List<String> warnings) {
        ElementType type = ElementType.of(node);
        if (type == ElementType.RULE || type == ElementType.COMPUTATION) {
            warnings.add(node.path(DmKeys.NAME).asText());
        } else if (type == ElementType.GROUP) {
            node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)
                    .forEach(child -> recordIncludedRulesNeedingRelativization(child, warnings));
        }
    }

    // ── effective types (typedef inlining) ────────────────────────────────────────────────────────────────

    /** Inline every field's {@code TypeDefType} fieldType with the referenced definition's concrete fieldType. */
    private static void inlineTypeDefs(JsonNode node, Map<String, JsonNode> typeDefs, Set<String> onPath) {
        if (typeDefs.isEmpty()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (ElementType.FIELD.matches(obj) && obj.path(ElementType.FIELD.wireName()) instanceof ObjectNode field) {
                JsonNode ft = field.path(DmKeys.FIELD_TYPE);
                if ("TypeDefType".equals(ft.path(DmKeys.TYPE).asText())) {
                    JsonNode resolved = resolveTypeDef(ft, typeDefs, onPath);
                    if (resolved != ft) {
                        field.set(DmKeys.FIELD_TYPE, resolved.deepCopy());
                    }
                }
            }
            obj.forEach(child -> inlineTypeDefs(child, typeDefs, onPath));
        } else if (node.isArray()) {
            node.forEach(child -> inlineTypeDefs(child, typeDefs, onPath));
        }
    }

    /** Resolve a (possibly {@code TypeDefType}) fieldType to its concrete form (transitive, cycle-guarded); a
     *  dangling / cyclic reference is returned unchanged. */
    private static JsonNode resolveTypeDef(JsonNode fieldType, Map<String, JsonNode> typeDefs, Set<String> onPath) {
        if (!"TypeDefType".equals(fieldType.path(DmKeys.TYPE).asText())) {
            return fieldType;
        }
        String refId = fieldType.path("TypeDefType").path(DmKeys.TYPE_DEFINITION_ID).asText("");
        if (refId.isEmpty() || onPath.contains(refId)) {
            return fieldType;
        }
        JsonNode target = typeDefs.get(refId);
        if (target == null) {
            return fieldType;
        }
        onPath.add(refId);
        JsonNode resolved = resolveTypeDef(target, typeDefs, onPath);
        onPath.remove(refId);
        return resolved;
    }

    // ── id normalization ──────────────────────────────────────────────────────────────────────────────────

    /** Rewrite every group/field/rule/computation {@code id} under {@code node} to its name-path. */
    private static void normalizeIds(JsonNode node, String prefix) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            ElementType type = ElementType.of(obj);
            if (type != null) {
                String path = prefix + "/" + obj.path(DmKeys.NAME).asText();
                obj.put(DmKeys.ID, path);
                String childPrefix = type == ElementType.GROUP ? path : prefix;
                obj.forEach(child -> normalizeIds(child, childPrefix));
            } else {
                obj.forEach(child -> normalizeIds(child, prefix));
            }
        } else if (node.isArray()) {
            node.forEach(child -> normalizeIds(child, prefix));
        }
    }

    /** Drop the now-vestigial {@code header.modelReferences} — after expansion the model is self-contained. */
    private static void stripVestigialModelReferences(ObjectNode root) {
        if (root.path(DmKeys.HEADER) instanceof ObjectNode header) {
            header.remove(DmKeys.MODEL_REFERENCES);
        }
    }

    // ── the name-path index ───────────────────────────────────────────────────────────────────────────────

    /** Index the top-level groups' subtrees by name-path (fields + groups), starting one level below the
     *  synthetic root — a top-level group named {@code Order} is {@code /Order}, not {@code /RootGroup/Order}. */
    private static void indexRootGroups(JsonNode modelRoot, Map<String, JsonNode> byPath) {
        for (JsonNode group : modelRoot.path(DmKeys.ROOT_GROUPS)) {
            indexElement(group, "", byPath);
        }
    }

    private static void indexElement(JsonNode node, String prefix, Map<String, JsonNode> byPath) {
        ElementType type = ElementType.of(node);
        if (type != ElementType.GROUP && type != ElementType.FIELD) {
            return;   // rules/computations carry no index entry (ModelIndex reads fields + groups)
        }
        String path = prefix + "/" + node.path(DmKeys.NAME).asText();
        byPath.put(path, node);
        if (type == ElementType.GROUP) {
            for (JsonNode child : node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                indexElement(child, path, byPath);
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

    private static List<String> readLocales(ObjectNode root) {
        List<String> locales = new ArrayList<>();
        for (JsonNode loc : root.path(DmKeys.HEADER).path(DmKeys.LOCALES)) {
            String code = loc.path(DmKeys.CODE).asText();
            if (!code.isEmpty()) {
                locales.add(new Locale.Builder().setLanguageTag(code.replace('_', '-')).build().toString());
            }
        }
        return List.copyOf(locales);
    }

    private static ObjectNode parse(String dmJson) {
        try {
            if (MAPPER.readTree(dmJson) instanceof ObjectNode root) {
                return root;
            }
            throw new IllegalArgumentException("DM-JSON root is not a JSON object");
        } catch (IOException e) {
            throw new IllegalArgumentException("not valid DM-JSON", e);
        }
    }

    private static ObjectNode resolve(ModelResolver resolver, String reference) {
        String json = resolver.resolve(reference);
        if (json == null) {
            throw new UnresolvedReferenceException(reference, "no model resolved for reference \"" + reference + "\"");
        }
        try {
            if (MAPPER.readTree(json) instanceof ObjectNode root) {
                return root;
            }
            throw new UnresolvedReferenceException(reference, "resolved model \"" + reference + "\" is not a JSON object");
        } catch (IOException e) {
            throw new UnresolvedReferenceException(reference, "failed to parse resolved model \"" + reference + "\"", e);
        }
    }
}
