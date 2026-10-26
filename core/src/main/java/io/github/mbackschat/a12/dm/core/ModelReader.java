package io.github.mbackschat.a12.dm.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
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
     * Configured to match A12's own model mapper ({@code ObjectMapperProvider.createPreconfiguredObjectMapper}),
     * because a model A12 reads must be a model we read.
     *
     * <p>Numbers are parsed as {@link BigDecimal} with trailing zeros PRESERVED — so a declared
     * {@code minValue}/{@code maxValue} reads back at the authored literal's exact scale, matching the kernel
     * (which keeps the literal verbatim; the CLI renders it as-is). A plain mapper drops {@code 50.00} to a
     * {@code double} {@code 50.0}, and {@code USE_BIG_DECIMAL_FOR_FLOATS} alone still strips
     * ({@code 50.00} → {@code 5E+1}) unless {@code STRIP_TRAILING_BIGDECIMAL_ZEROES} is disabled.
     *
     * <p>Comments are accepted for the same reason: A12 enables {@code ALLOW_COMMENTS} on both of its mappers, so
     * a commented DM-JSON model is a legal A12 artifact and refusing one makes a file the A12 tooling
     * round-trips unreadable here. A trailing comma stays a parse error, which A12 also refuses — the parity is
     * two-sided (INTERPRETER-FINDINGS IF228, whose first pass fixed the {@code :interpreter} parse sites and
     * missed this JVM one).
     */
    // Package scope so ConfigFlag decodes a member's JSON text with this same model codec rather than a second
    // construction site (A12JsonConstructionGuardTest).
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** The severity of a rule that declares none: A12's {@code a12internal.Rule} initializes it to ERROR. */
    private static final String DEFAULT_RULE_SEVERITY = "ERROR";

    /** The expanded, self-contained, name-path-keyed DM-JSON tree. */
    private final ObjectNode expanded;
    private final Map<String, JsonNode> byPath;
    private final List<String> locales;
    private final Integer baseYear;
    /** Surviving mounted rules/computations whose fragments retain a genuine unmappable reference. */
    private final List<String> includedElementsWithUnmappedReferences;
    private final List<ComputationInfo> computations;
    private final List<RuleInfo> rules;
    /** Include-mount group path → included model id. Kept separately from the expanded wire format. */
    private final Map<String, String> originsByPath;
    /** Field path → authored type-definition id. Kept separately after effective-type inlining. */
    private final Map<String, String> typeRefsByPath;

    private ModelReader(State state) {
        this.expanded = state.expanded();
        this.byPath = state.index().byPath();
        this.locales = state.model().locales();
        this.baseYear = state.model().baseYear();
        this.includedElementsWithUnmappedReferences = state.model().unmappedReferences();
        this.computations = state.index().computations();
        this.rules = state.index().rules();
        this.originsByPath = state.provenance().originsByPath();
        this.typeRefsByPath = state.provenance().typeRefsByPath();
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
        Set<ObjectNode> elementsWithResiduals = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<ObjectNode, String> origins = new IdentityHashMap<>();
        Map<ObjectNode, String> typeRefs = new IdentityHashMap<>();
        Map<ObjectNode, String> conditionLanguages = new IdentityHashMap<>();
        expandContent(root, resolver, typeDefs, new HashSet<>(), elementsWithResiduals, origins,
                conditionLanguages);
        JsonNode modelRoot = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT);
        captureTypeDefRefs(modelRoot, typeRefs);
        inlineTypeDefs(modelRoot, typeDefs, new HashSet<>());
        normalizeIds(modelRoot, "");
        Map<String, String> originsByPath = provenanceByPath(origins);
        Map<String, String> typeRefsByPath = provenanceByPath(typeRefs);
        List<String> unmappedReferences = residualElementPaths(modelRoot, elementsWithResiduals);
        stripVestigialModelReferences(root);
        IndexState index = new IndexState(new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>());
        indexRootGroups(modelRoot, index);
        Provenance provenance = new Provenance(Map.copyOf(originsByPath), Map.copyOf(typeRefsByPath));
        return new ModelReader(new State(root,
                index.frozen(),
                new ModelState(readLocales(root), readBaseYear(root), unmappedReferences),
                provenance));
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

    /**
     * The model's declared base year ({@code modelInfo.baseYear}), or empty when it declares none. Read at the
     * model level like {@link #locales()} because it is model-level state a gate depends on, not a field's.
     */
    public Optional<Integer> baseYear() {
        return Optional.ofNullable(baseYear);
    }

    /** The model's declared locales (header {@code locales}), each as a Java locale string (e.g. {@code en_US}). */
    public List<String> locales() {
        return locales;
    }

    /**
     * The full name-paths of surviving rules/computations mounted from an <b>included</b> model whose
     * reference-bearing fragments retain a genuine unmapped reference. Relative references keep their
     * move-invariant meaning; absolute and flag-enabled unique short-name references to included entities are
     * bound in the pre-mount model and rewritten to their moved targets. A consumer that must not silently
     * mis-evaluate (the CLI eval preparation boundary, A4 / IF122) refuses when this is non-empty.
     */
    public List<String> includedElementsWithUnmappedReferences() {
        return includedElementsWithUnmappedReferences;
    }

    /**
     * Historical compatibility alias. The result covers both rules and computations and reports current
     * unmapped references rather than the removed depth-relative implementation.
     */
    @Deprecated(forRemoval = false)
    public List<String> includedRulesNeedingRelativization() {
        return includedElementsWithUnmappedReferences();
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

    /** The model's authored computations with resolved absolute targets, in model order (ModelIndex's view). */
    List<ComputationInfo> computations() {
        return computations;
    }

    /** The model's authored rules, in model order (ModelIndex's view). */
    List<RuleInfo> rules() {
        return rules;
    }

    private record State(ObjectNode expanded, IndexState index, ModelState model, Provenance provenance) {
    }

    /** The name-path index and the model-order element lists, filled by one walk and then frozen. */
    private record IndexState(Map<String, JsonNode> byPath, List<ComputationInfo> computations,
            List<RuleInfo> rules) {
        IndexState frozen() {
            return new IndexState(byPath, List.copyOf(computations), List.copyOf(rules));
        }
    }

    private record ModelState(List<String> locales, Integer baseYear, List<String> unmappedReferences) {
    }

    private record Provenance(Map<String, String> originsByPath, Map<String, String> typeRefsByPath) {
    }

    /** The included-model id whose content is mounted at {@code path}, or {@code null}. */
    String origin(String path) {
        return originsByPath.get(path);
    }

    /** The authored typedef id behind the now-inlined field at {@code path}, or {@code null}. */
    String typeRef(String path) {
        return typeRefsByPath.get(path);
    }

    // ── expansion ─────────────────────────────────────────────────────────────────────────────────────────

    /** Collect this model's type-definitions, resolve header typedef imports, then expand every include mount. */
    private static void expandContent(ObjectNode root, ModelResolver resolver, Map<String, JsonNode> typeDefs,
            Set<String> visiting, Set<ObjectNode> elementsWithResiduals, Map<ObjectNode, String> origins,
            Map<ObjectNode, String> conditionLanguages) {
        JsonNode content = root.path(DmKeys.CONTENT);
        collectTypeDefs(content, typeDefs);
        // Header "typeDefinitions"-purpose imports are ingested only when the model declares no local ones
        // (the kernel's "a mixture of definition ways is not allowed" constraint).
        if (content.path(DmKeys.TYPE_DEFINITIONS).isEmpty()) {
            importTypeDefs(root, resolver, typeDefs, visiting);
        }
        JsonNode rootGroups = content.path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS);
        captureConditionLanguages(rootGroups, conditionLanguageCode(root), conditionLanguages);
        expandElements(rootGroups, root, resolver, typeDefs, visiting, elementsWithResiduals, origins,
                conditionLanguages, "");
    }

    private static String conditionLanguageCode(ObjectNode root) {
        return root.path(DmKeys.CONTENT).path(DmKeys.MODEL_CONFIG)
                .path("conditionLanguage").path("code").asText("en_US");
    }

    private static void captureConditionLanguages(JsonNode elements, String languageCode,
            Map<ObjectNode, String> conditionLanguages) {
        for (JsonNode candidate : elements) {
            if (!(candidate instanceof ObjectNode element)) {
                continue;
            }
            ElementType type = ElementType.of(element);
            if (type == ElementType.RULE || type == ElementType.COMPUTATION) {
                conditionLanguages.putIfAbsent(element, languageCode);
            } else if (type == ElementType.GROUP) {
                captureConditionLanguages(
                        element.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS),
                        languageCode,
                        conditionLanguages);
            }
        }
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
            // A12 resolves a textual typedef reference even when it is empty or blank and refuses the model when
            // nothing answers it (KF593), so only an absent or non-text reference, which was not measured, is skipped.
            JsonNode referenceNode = ref.path(DmKeys.REFERENCE);
            String reference = referenceNode.asText();
            if (!referenceNode.isTextual() || visiting.contains(reference)) {
                continue;
            }
            ObjectNode importedRoot = resolve(resolver, reference);
            visiting.add(reference);
            collectTypeDefs(importedRoot.path(DmKeys.CONTENT), typeDefs);
            importTypeDefs(importedRoot, resolver, typeDefs, visiting);   // transitive imports
            visiting.remove(reference);
        }
    }

    /** Walk an {@code elements}/{@code rootGroups} array, expanding each mount group in place. {@code parentPath}
     *  is the absolute name-path of the container whose elements these are (empty at the model root), so a mount's
     *  own absolute path is known — the anchor the condition remap rewrites included references onto. */
    private static void expandElements(JsonNode elements, ObjectNode baseRoot, ModelResolver resolver,
            Map<String, JsonNode> typeDefs, Set<String> visiting, Set<ObjectNode> elementsWithResiduals,
            Map<ObjectNode, String> origins, Map<ObjectNode, String> conditionLanguages, String parentPath) {
        if (!elements.isArray()) {
            return;
        }
        for (JsonNode node : elements) {
            if (!ElementType.GROUP.matches(node) || !(node instanceof ObjectNode groupNode)) {
                continue;
            }
            ObjectNode body = (ObjectNode) groupNode.path(ElementType.GROUP.wireName());
            String groupPath = parentPath + "/" + groupNode.path(DmKeys.NAME).asText();
            String alias = body.path(DmKeys.MODEL_ALIAS).asText("");
            JsonNode existing = body.path(DmKeys.ELEMENTS);
            boolean unexpanded = existing.isMissingNode() || existing.isEmpty();
            // An empty alias names no include entry, so A12 cannot find the included model and fails the expansion.
            if (alias.isEmpty() && body.path(DmKeys.MODEL_ALIAS).isTextual() && unexpanded) {
                throw new UnresolvedReferenceException(alias,
                        "the include group " + groupPath + " has an empty modelAlias, so A12 cannot find the included model");
            }
            // A modelAlias group is an unexpanded mount only while its elements are empty — the kernel's
            // idempotency guard (an already-mounted group is not re-expanded).
            if (!alias.isEmpty() && unexpanded) {
                mount(groupNode, body, alias, baseRoot, resolver, typeDefs, visiting, elementsWithResiduals,
                        origins, conditionLanguages, groupPath);
            } else {
                expandElements(existing, baseRoot, resolver, typeDefs, visiting, elementsWithResiduals, origins,
                        conditionLanguages, groupPath);
            }
        }
    }

    /** Mount the model referenced by {@code alias} into {@code mountGroup}: append the included root group's
     *  children (SINGLE_RG) or every top-level group (MODEL_ROOT), after fully expanding the included model. */
    private static void mount(ObjectNode mountGroup, ObjectNode mountBody, String alias, ObjectNode baseRoot,
            ModelResolver resolver, Map<String, JsonNode> typeDefs, Set<String> visiting,
            Set<ObjectNode> elementsWithResiduals, Map<ObjectNode, String> origins,
            Map<ObjectNode, String> conditionLanguages, String mountPath) {
        String reference = referenceForAlias(baseRoot, alias);
        if (visiting.contains(reference)) {
            throw new IllegalArgumentException("include cycle detected at reference \"" + reference + "\"");
        }
        ObjectNode includedRoot = resolve(resolver, reference);
        visiting.add(reference);
        expandContent(includedRoot, resolver, typeDefs, visiting, elementsWithResiduals, origins,
                conditionLanguages);
        visiting.remove(reference);
        String sourceModel = includedRoot.path(DmKeys.HEADER).path(DmKeys.ID).asText(reference);
        origins.put(mountGroup, sourceModel);

        boolean modelRoot = "MODEL_ROOT".equals(mountBody.path("includeLevel").asText());
        boolean excludeRules = excludes(mountBody, "excludeRules");
        boolean excludeComputations = excludes(mountBody, "excludeComputations");
        ArrayNode includedRootGroups = (ArrayNode) includedRoot.path(DmKeys.CONTENT)
                .path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS);
        ModelSymbols symbols = ModelSymbols.from(includedRoot);

        // The old (included-model) absolute-path prefix → its new post-mount absolute prefix. Kernel behavior is
        // characterized from DocumentModelExpandService.RuleAndComputationConditionUpdaterVisitor together with
        // EntityPathsRelativizer: mounted fragments keep naming the entities that moved with them. SINGLE_RG
        // replaces the vanishing root group with the mount path; MODEL_ROOT keeps each top group's name below it.
        List<MountedNode> toMount = new ArrayList<>();
        Map<String, String> prefixMap = new LinkedHashMap<>();
        if (modelRoot) {
            for (JsonNode top : includedRootGroups) {
                toMount.add(new MountedNode(top, ""));
                String name = top.path(DmKeys.NAME).asText();
                if (!name.isEmpty()) {
                    prefixMap.put("/" + name, mountPath + "/" + name);
                }
            }
        } else if (!includedRootGroups.isEmpty()) {
            // SINGLE_RG (default): the single root group's children become the mount's children; the root group
            // itself is discarded (its name maps to the mount path). Its final usageType slot replaces the mount's
            // slot, including absence; a raw mount marker must not survive when the expanded provider has none.
            JsonNode includedRootGroup = includedRootGroups.get(0);
            String rootGroupName = includedRootGroup.path(DmKeys.NAME).asText();
            if (!rootGroupName.isEmpty()) {
                prefixMap.put("/" + rootGroupName, mountPath);
            }
            JsonNode usageType = includedRootGroup.path(ElementType.GROUP.wireName()).path(DmKeys.USAGE_TYPE);
            if (usageType.isMissingNode()) {
                mountBody.remove(DmKeys.USAGE_TYPE);
            } else {
                mountBody.set(DmKeys.USAGE_TYPE, usageType);
            }
            // SINGLE_RG discards the provider root, so its attachment shield must be transferred as behavior as
            // well as metadata. Filtering first would erase the template's mandated rules even though the Kernel
            // preserves them; WorkspacePreparationParityTest locks the external oracle.
            if ("attachment".equals(usageType.asText())) {
                excludeRules = false;
                excludeComputations = false;
            }
            String includedRootPath = "/" + rootGroupName;
            includedRootGroup.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)
                    .forEach(child -> toMount.add(new MountedNode(child, includedRootPath)));
        }

        ArrayNode mountElements = mountBody.putArray(DmKeys.ELEMENTS);
        for (MountedNode child : toMount) {
            ObjectNode kept = filterRulesAndComputations(
                    (ObjectNode) child.node(), excludeRules, excludeComputations);
            if (kept != null) {
                mountElements.add(kept);
                // Remap every absolute included reference onto its post-mount location; a reference this cannot map
                // (an absolute first segment naming no mounted top-group) is left verbatim and its element recorded
                // so the consumer refuses rather than mis-evaluate.
                remapMountedConditions(kept, prefixMap, elementsWithResiduals, symbols, conditionLanguages,
                        child.parentPath());
            }
        }
        // The include directives are now consumed — drop them so the expanded group is a plain, self-contained
        // group (the kernel likewise doesn't re-expand a group once mounted).
        mountBody.remove(List.of(DmKeys.MODEL_ALIAS, "includeLevel", "excludeRules", "excludeComputations"));
    }

    /** Whether a mount directive drops the included executables: the JSON literal {@code true} and nothing else.
     *
     *  <p>A12 reads these two members with {@code JsonNode.booleanValue()} (kernel serializer
     *  {@code ElementDeserializer}), which is false for every non-Boolean. Jackson's {@code asBoolean(false)}
     *  instead coerces {@code "true"} and {@code 1}, so it would drop a rule the Kernel keeps — measured
     *  2026-09-12 across twelve spellings by {@code WorkspaceIncludeStructureKernelTest}, which carries the
     *  near-misses ({@code "True"}, {@code 0.5}) that the coercion happens to read the same way. */
    private static boolean excludes(ObjectNode mountBody, String directive) {
        return mountBody.path(directive).isBoolean() && mountBody.path(directive).booleanValue();
    }

    /** Resolve the mount alias to its referenced-model string via the base header's {@code modelReferences}: the
     *  LAST entry carrying the alias, whatever its purpose, is the one A12 keys it to (KF593). A12 finds no included
     *  model when no entry carries the alias or that entry's {@code reference} is absent, null or blank as
     *  {@code String.isBlank} reads it, and fails the expansion, so this refuses there too rather than reading the
     *  alias as the reference. */
    private static String referenceForAlias(ObjectNode baseRoot, String alias) {
        JsonNode keyed = null;
        for (JsonNode ref : baseRoot.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (alias.equals(ref.path(DmKeys.ALIAS).asText())) {
                keyed = ref;
            }
        }
        if (keyed != null && "typeDefinitions".equals(keyed.path(DmKeys.PURPOSE).asText())) {
            throw new UnresolvedReferenceException(alias, "the header.modelReferences entry for alias \"" + alias
                    + "\" has purpose typeDefinitions, and A12 refuses to expand a mount through one");
        }
        JsonNode reference = keyed == null ? null : keyed.path(DmKeys.REFERENCE);
        if (reference != null && reference.isTextual() && !reference.asText().isBlank()) {
            return reference.asText();
        }
        throw new UnresolvedReferenceException(alias,
                "no header.modelReferences entry for alias \"" + alias + "\" names a model, so A12 cannot find the included model");
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
            boolean attachment = "attachment".equals(body.path(DmKeys.USAGE_TYPE).asText());
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

    private record MountedNode(JsonNode node, String parentPath) {
    }

    private record ModelSymbols(
            Set<String> entityPaths,
            Map<String, List<String>> fieldsByShortName,
            boolean shortNamesAllowed) {

        private static ModelSymbols from(ObjectNode root) {
            Set<String> paths = new HashSet<>();
            Map<String, List<String>> fields = new HashMap<>();
            for (JsonNode top : root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS)) {
                collect(top, "", paths, fields);
            }
            // Through A12's own POJO binding rather than `asBoolean`: the two disagree on a capitalized
            // `"True"` (A12 on, `asBoolean` off — so every model-wide short name would read as undeclared)
            // and on a fractional number (A12 refuses the model, `asBoolean` truncates and answers).
            JsonNode flag = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_CONFIG)
                    .path(DmKeys.FIELD_REF_BY_SHORT_NAME_ALLOWED);
            boolean allowShortNames = ConfigFlag.isSet(flag);
            return new ModelSymbols(paths, fields, allowShortNames);
        }

        private static void collect(JsonNode node, String parentPath, Set<String> paths,
                Map<String, List<String>> fields) {
            ElementType type = ElementType.of(node);
            if (type != ElementType.GROUP && type != ElementType.FIELD) {
                return;
            }
            String name = node.path(DmKeys.NAME).asText();
            String path = parentPath + "/" + name;
            paths.add(path);
            if (type == ElementType.FIELD) {
                fields.computeIfAbsent(name, ignored -> new ArrayList<>()).add(path);
                return;
            }
            for (JsonNode child : node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                collect(child, path, paths, fields);
            }
        }
    }

    // ── included reference remap (kernel include expansion: parsed reference spans moved with their targets) ──

    /** Remap every absolute included reference in a mounted rule's / computation's condition(s) onto its
     *  post-mount location, per {@code prefixMap}. An absolute reference the map cannot rewrite (a first segment
     *  naming no mounted top-group) is left verbatim and its element recorded in {@code elementsWithResiduals}, so a
     *  correctness-critical consumer refuses rather than mis-evaluate (A4 / IF122). Recurses into groups. */
    private static void remapMountedConditions(JsonNode node, Map<String, String> prefixMap,
            Set<ObjectNode> elementsWithResiduals, ModelSymbols symbols,
            Map<ObjectNode, String> conditionLanguages, String parentPath) {
        ElementType type = ElementType.of(node);
        if (type == ElementType.RULE) {
            ObjectNode element = (ObjectNode) node;
            boolean residual = remapConditionKey((ObjectNode) node.path(ElementType.RULE.wireName()),
                    DmKeys.ERROR_CONDITION, referenceContext(prefixMap, symbols, parentPath,
                            conditionLanguages.get(element)));
            updateResidual(elementsWithResiduals, element, residual);
        } else if (type == ElementType.COMPUTATION) {
            ObjectNode element = (ObjectNode) node;
            ObjectNode body = (ObjectNode) node.path(ElementType.COMPUTATION.wireName());
            ConditionReferenceRemapper.ReferenceContext context = referenceContext(
                    prefixMap, symbols, parentPath, conditionLanguages.get(element));
            boolean residual = remapConditionKey(body, "commonPrecondition", context);
            for (JsonNode alt : body.path("computationAlternatives")) {
                if (alt instanceof ObjectNode altObj) {
                    residual |= remapConditionKey(altObj, "precondition", context);
                    residual |= remapConditionKey(altObj, "operation", context);
                }
            }
            updateResidual(elementsWithResiduals, element, residual);
        } else if (type == ElementType.GROUP) {
            String groupPath = parentPath + "/" + node.path(DmKeys.NAME).asText();
            for (JsonNode child : node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                remapMountedConditions(child, prefixMap, elementsWithResiduals, symbols, conditionLanguages,
                        groupPath);
            }
        }
    }

    private static ConditionReferenceRemapper.ReferenceContext referenceContext(
            Map<String, String> prefixMap, ModelSymbols symbols, String declaringGroup,
            String conditionLanguageCode) {
        return new ConditionReferenceRemapper.ReferenceContext(
                prefixMap, declaringGroup, symbols.entityPaths(), symbols.fieldsByShortName(),
                symbols.shortNamesAllowed(), conditionLanguageCode);
    }

    private static void updateResidual(Set<ObjectNode> elementsWithResiduals, ObjectNode element, boolean residual) {
        if (residual) {
            elementsWithResiduals.add(element);
        } else {
            elementsWithResiduals.remove(element);
        }
    }

    /** Remap one reference-bearing fragment in place; returns whether it retained a genuine unmapped reference. */
    private static boolean remapConditionKey(ObjectNode body, String key,
            ConditionReferenceRemapper.ReferenceContext context) {
        if (!body.hasNonNull(key)) {
            return false;
        }
        String cond = body.path(key).asText("");
        ConditionReferenceRemapper.Result r = ConditionReferenceRemapper.remapReferences(cond, context);
        if (!r.text().equals(cond)) {
            body.put(key, r.text());
        }
        return r.hadResidual();
    }

    private static List<String> residualElementPaths(JsonNode root, Set<ObjectNode> elementsWithResiduals) {
        List<String> paths = new ArrayList<>();
        collectResidualElementPaths(root, elementsWithResiduals, paths);
        return List.copyOf(paths);
    }

    private static void collectResidualElementPaths(JsonNode node, Set<ObjectNode> elementsWithResiduals,
            List<String> paths) {
        if (node instanceof ObjectNode object) {
            if (elementsWithResiduals.contains(object)) {
                paths.add(object.path(DmKeys.ID).asText());
            }
            object.forEach(child -> collectResidualElementPaths(child, elementsWithResiduals, paths));
        } else if (node.isArray()) {
            node.forEach(child -> collectResidualElementPaths(child, elementsWithResiduals, paths));
        }
    }

    /** The rewritten condition text plus whether it left an absolute reference no mount prefix could map. */
    record RemapResult(String text, boolean hadResidual) {}

    /**
     * Rewrite each absolute-path reference in [condition] whose first segment {@code /<seg>} is a key of
     * {@code prefixMap}, replacing that prefix with the mapped post-mount prefix (keeping the rest of the path and
     * any leading {@code $} outer-correlation marker) — the kernel's move-remap. A {@code /<seg>/rest},
     * a bare {@code /<seg>}, an outer {@code [$/<seg>/…]}, and a MODEL_ROOT {@code /<top>/…} are all handled
     * uniformly. Every other token — relative refs, divisions, literals — is left verbatim; an absolute ref whose
     * first segment is not in the map is left verbatim and flagged as a residual. Package-private for a focused
     * unit test.
     */
    static RemapResult remapAbsolutePaths(String condition, Map<String, String> prefixMap) {
        return remapAbsolutePaths(condition, prefixMap, "en_US");
    }

    static RemapResult remapAbsolutePaths(
            String condition,
            Map<String, String> prefixMap,
            String conditionLanguageCode) {
        ConditionReferenceRemapper.Result result =
                ConditionReferenceRemapper.remapAbsolutePaths(condition, prefixMap, conditionLanguageCode);
        return new RemapResult(result.text(), result.hadResidual());
    }

    // ── effective types (typedef inlining) ────────────────────────────────────────────────────────────────

    /**
     * Remember each field's authored named type before effective-type inlining removes that distinction.
     * Object identity survives include mounting, inlining, and id normalization, so this side table can be
     * keyed by the final path without leaking provenance into the expanded DM-JSON.
     */
    private static void captureTypeDefRefs(JsonNode node, Map<ObjectNode, String> typeRefs) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (ElementType.FIELD.matches(obj)) {
                JsonNode fieldType = obj.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE);
                if ("TypeDefType".equals(fieldType.path(DmKeys.TYPE).asText())) {
                    String id = fieldType.path("TypeDefType").path(DmKeys.TYPE_DEFINITION_ID).asText("");
                    if (!id.isEmpty()) {
                        typeRefs.put(obj, id);
                    }
                }
            }
            obj.forEach(child -> captureTypeDefRefs(child, typeRefs));
        } else if (node.isArray()) {
            node.forEach(child -> captureTypeDefRefs(child, typeRefs));
        }
    }

    /** Re-key one identity side table by the normalized element ids written by {@link #normalizeIds}. */
    private static Map<String, String> provenanceByPath(Map<ObjectNode, String> byNode) {
        Map<String, String> out = new LinkedHashMap<>();
        byNode.forEach((node, value) -> {
            String path = node.path(DmKeys.ID).asText("");
            if (!path.isEmpty()) {
                out.put(path, value);
            }
        });
        return out;
    }

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
    private static void indexRootGroups(JsonNode modelRoot, IndexState index) {
        for (JsonNode group : modelRoot.path(DmKeys.ROOT_GROUPS)) {
            indexElement(group, "", index);
        }
    }

    /** Rules and computations are listed, not keyed: the name-path index holds fields and groups only. */
    private static void indexElement(JsonNode node, String prefix, IndexState index) {
        ElementType type = ElementType.of(node);
        if (type == null) {
            return;
        }
        switch (type) {
            case COMPUTATION -> index.computations().add(computationInfo(node, prefix));
            case RULE -> index.rules().add(ruleInfo(node, prefix));
            case FIELD -> index.byPath().put(prefix + "/" + node.path(DmKeys.NAME).asText(), node);
            case GROUP -> {
                String path = prefix + "/" + node.path(DmKeys.NAME).asText();
                index.byPath().put(path, node);
                for (JsonNode child : node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                    indexElement(child, path, index);
                }
            }
        }
    }

    /** Map a Computation element in the group at {@code groupPath} to its {@link ComputationInfo} — the
     *  declared {@code computedFieldRelPath} resolved against the computation ELEMENT's own path. */
    private static ComputationInfo computationInfo(JsonNode node, String groupPath) {
        String name = node.path(DmKeys.NAME).asText();
        String elementPath = groupPath + "/" + name;
        String relPath = node.path(ElementType.COMPUTATION.wireName()).path("computedFieldRelPath").asText("");
        return new ComputationInfo(elementPath, groupPath, name, resolveRelPath(elementPath, relPath));
    }

    /** Map a Rule element in the group at {@code groupPath} to its {@link RuleInfo}, the text as written. */
    private static RuleInfo ruleInfo(JsonNode node, String groupPath) {
        String name = node.path(DmKeys.NAME).asText();
        JsonNode body = node.path(ElementType.RULE.wireName());
        Map<String, String> messages = new LinkedHashMap<>();
        for (JsonNode message : body.path(DmKeys.ERROR_MESSAGE)) {
            messages.put(message.path(DmKeys.LOCALE).asText(), message.path(DmKeys.TEXT).asText());
        }
        return RuleInfo.builder().path(groupPath + "/" + name).groupPath(groupPath).name(name)
                .errorEntityRelPath(body.path(DmKeys.ERROR_ENTITY_REL_PATH).asText(""))
                .errorCondition(body.path(DmKeys.ERROR_CONDITION).asText(""))
                .errorCode(body.path(DmKeys.ERROR_CODE).asText(""))
                .severity(body.path(DmKeys.SEVERITY).asText(DEFAULT_RULE_SEVERITY))
                .messages(messages)
                .build();
    }

    /**
     * Resolve {@code relPath} against the element at {@code basePath} — so a computation's sibling field
     * {@code "../Field"} resolves to {@code <groupPath>/Field}. Package-private for a focused test.
     *
     * <p>The algebra lives on {@link EntityPath#resolve}, beside its inverse {@code relativize}, because that
     * is where a call site looking for it goes: while this direction was reachable only as a static on the
     * model reader, callers who did not find it wrote their own — and one of them inverted the convention
     * (see {@code EntityPath#resolve}). This method stays as the string-boundary spelling its callers use.
     */
    static String resolveRelPath(String basePath, String relPath) {
        return EntityPath.of(basePath).resolve(relPath).value();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * {@code content.modelInfo.baseYear} as A12's reader binds it ({@link A12IntBinding}): A12's own writer stores it
     * as a string, {@code 2024.7} is 2024, and the text {@code null} is none. An unbindable or absent declaration is
     * "none" rather than an error, because this is a read accessor over a model some other tool wrote.
     */
    private static Integer readBaseYear(ObjectNode root) {
        return A12IntBinding.of(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_INFO).path(DmKeys.BASE_YEAR))
                instanceof A12IntBinding.Bound(int year) ? year : null;
    }

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
            throw new ModelInputException("DM-JSON root is not a JSON object");
        } catch (IOException e) {
            throw new ModelInputException("not valid DM-JSON: " + e.getMessage(), e);
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
