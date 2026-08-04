package io.github.mbackschat.a12.dm.modelkit;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.core.Verification;

/**
 * An existing document model, read from DM-JSON into its <em>authored source tree</em> (M7) — the read
 * counterpart of {@link DocumentModelDraft} (build-from-scratch). It lets you enumerate the model's
 * structure (header, doc config, includes, type definitions, the group/field tree, name-path addressed)
 * and round-trip it: {@link #toJson()} re-serializes the retained Jackson tree, preserving includes and
 * any element this kit does not (yet) model — the substrate the M8 surgical structure edits build on.
 *
 * <p><b>Round-trip fidelity.</b> The round-trip rides the <em>generic</em> Jackson tree, never the
 * kernel's typed serializer (whose {@code NON_EMPTY}/{@code NON_DEFAULT} mapper omits empty/default
 * fields — KERNEL-FINDINGS), so read → write-back drops nothing and defaults nothing; it is identity
 * modulo pretty-printing. Reading is purely textual (no kernel, no include resolution), so a model with
 * unresolved includes still reads and enumerates.
 */
public final class ModelDocument {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // DM-JSON is parsed the way A12 parses it (`ObjectMapperProvider.createPreconfiguredObjectMapper`):
            // a number keeps the authored literal's exact scale, and a comment is legal. A DEFAULT mapper builds a
            // DoubleNode, so `0.50` is already `0.5` before anything reads it — and because this surface writes the
            // tree back, an unrelated edit then rewrites every number in the model it passes through (measured
            // 2026-07-28; INTERPRETER-FINDINGS IF228). Behavioural guard, not a shared constant, keeps these in
            // step: `cli … NumberSpecScaleFidelityTest` + `cli … CommentedModelSourceTest`.
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** The {@code modelReferences.purpose} value for a type-def import (kernel {@code PURPOSE_4_ONLY_TYPE_DEFS}). */
    private static final String IMPORT_PURPOSE = "typeDefinitions";
    /** A direct, authored mount (the classic include). Absent/blank purpose is treated as this. */
    private static final String INCLUDE_PURPOSE = "include";
    /** A reference the kernel adds for an include pulled in <em>transitively</em> (DG6/A12K-3234). */
    private static final String TRANSITIVE_INCLUDE_PURPOSE = "transitiveInclude";

    private final ObjectNode root;

    /** The exact DM-JSON {@link #validate(ModelResolver)} last gated, and what it decided — see {@link #consistencyGate()}. */
    private String gatedJson;
    private Verification gatedBy;

    private ModelDocument(ObjectNode root) {
        this.root = root;
    }

    /** Read a model from DM-JSON, retaining its authored source tree. */
    public static ModelDocument read(String dmJson) {
        try {
            JsonNode node = MAPPER.readTree(dmJson);
            if (!(node instanceof ObjectNode obj)) {
                throw new IllegalArgumentException("not a DM-JSON object");
            }
            return new ModelDocument(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid DM-JSON", e);
        }
    }

    /** The model as DM-JSON text — the retained tree, pretty-printed (lossless modulo whitespace). */
    public String toJson() {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("failed to serialize model", e));
        }
    }

    // --- header ---------------------------------------------------------------------------------

    public String id() {
        return root.path(DmKeys.HEADER).path(DmKeys.ID).asText();
    }

    /**
     * Set the model's header {@code id} (its identity — what cross-model {@code include}/type-def imports
     * bind to). A <b>single-model</b> edit: it does NOT repoint includers in other files (that cross-model
     * cascade is out of scope — the CLI's {@code model rename} gate refuses when a workspace sibling
     * references the old id). Fails fast on a blank id.
     */
    public ModelDocument setModelId(String newId) {
        if (newId == null || newId.isBlank()) {
            throw new IllegalArgumentException("a model id must be non-empty");
        }
        ((ObjectNode) root.path(DmKeys.HEADER)).put(DmKeys.ID, newId);
        return this;
    }

    public String modelVersion() {
        return root.path(DmKeys.HEADER).path("modelVersion").asText();
    }

    /** The {@code header.modelType} discriminator ({@code "document"} for document/type-def-import models). */
    public String modelType() {
        return root.path(DmKeys.HEADER).path(DmKeys.MODEL_TYPE).asText();
    }

    /** The declared locale codes, in document order. */
    public List<String> locales() {
        List<String> out = new ArrayList<>();
        for (JsonNode l : root.path(DmKeys.HEADER).path(DmKeys.LOCALES)) {
            out.add(l.path(DmKeys.CODE).asText());
        }
        return out;
    }

    /**
     * Register a display locale — append {@code code} to the {@code header.locales} registry (idempotent: a
     * no-op if already declared). The registry is explicit (adding per-element labels/messages does NOT
     * derive it), and the kernel then requires every rule/computation message to cover it, so a
     * {@code validate} after this rejects until each carries the locale's message (G16).
     */
    public ModelDocument addLocale(String code) {
        ObjectNode header = (ObjectNode) root.path(DmKeys.HEADER);
        ArrayNode locales = header.path(DmKeys.LOCALES).isArray()
                ? (ArrayNode) header.get(DmKeys.LOCALES)
                : header.putArray(DmKeys.LOCALES);
        for (JsonNode l : locales) {
            if (code.equals(l.path(DmKeys.CODE).asText())) {
                return this;   // already declared
            }
        }
        locales.addObject().put(DmKeys.CODE, code);
        return this;
    }

    /**
     * Remove a display locale from the {@code header.locales} registry (the inverse of {@link #addLocale};
     * no-op if not declared). Per-element labels/messages already written for that locale are left in place
     * (harmless once the locale is undeclared); the {@code validate} after this confirms the model stays
     * kernel-valid. Fails fast on removing the <b>last</b> locale (a model needs at least one).
     */
    public ModelDocument removeLocale(String code) {
        JsonNode header = root.path(DmKeys.HEADER);
        if (!header.path(DmKeys.LOCALES).isArray()) {
            return this;
        }
        ArrayNode locales = (ArrayNode) header.get(DmKeys.LOCALES);
        if (locales.size() == 1 && code.equals(locales.path(0).path(DmKeys.CODE).asText())) {
            throw new IllegalArgumentException("cannot remove the last locale '" + code
                    + "' — a model needs at least one declared locale");
        }
        filterArray((ObjectNode) header, DmKeys.LOCALES, DmKeys.CODE, code);
        return this;
    }

    /** The directly-declared model includes (header {@code modelReferences} with {@code purpose: "include"},
     *  or a blank/absent purpose — the classic default), in document order. <b>Transitive</b> includes the
     *  kernel adds are excluded here — read those via {@link #transitiveIncludes()}. */
    public List<Include> includes() {
        List<Include> out = new ArrayList<>();
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            String purpose = r.path(DmKeys.PURPOSE).asText();
            if (INCLUDE_PURPOSE.equals(purpose) || purpose.isEmpty()) {
                out.add(new Include(r.path(DmKeys.ALIAS).asText(), r.path(DmKeys.REFERENCE).asText()));
            }
        }
        return out;
    }

    /** The <b>transitive</b> includes (header {@code modelReferences} with {@code purpose: "transitiveInclude"})
     *  the kernel records for includes pulled in via another include — distinct from the author's direct
     *  {@link #includes()} (DG6/A12K-3234). */
    public List<Include> transitiveIncludes() {
        List<Include> out = new ArrayList<>();
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (TRANSITIVE_INCLUDE_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText())) {
                out.add(new Include(r.path(DmKeys.ALIAS).asText(), r.path(DmKeys.REFERENCE).asText()));
            }
        }
        return out;
    }

    /**
     * The referenced models this one <b>imports type definitions from</b> (header {@code modelReferences}
     * with {@code purpose: "typeDefinitions"}), in document order — their type-defs merge into this model's
     * type-def namespace by id at expand. Distinct from {@link #includes()} (mounted structure).
     */
    public List<String> typeDefImports() {
        List<String> out = new ArrayList<>();
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (IMPORT_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText())) {
                out.add(r.path(DmKeys.REFERENCE).asText());
            }
        }
        return out;
    }

    /** Direct authored includes and type-definition imports, grouped by purpose and keyed by alias. */
    public Map<ReferencePurpose, Map<String, String>> authoredReferences() {
        Map<ReferencePurpose, Map<String, String>> references = new java.util.EnumMap<>(ReferencePurpose.class);
        for (ReferencePurpose purpose : ReferencePurpose.values()) {
            references.put(purpose, new LinkedHashMap<>());
        }
        for (JsonNode reference : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            String wirePurpose = reference.path(DmKeys.PURPOSE).asText();
            ReferencePurpose purpose = switch (wirePurpose) {
                case "", INCLUDE_PURPOSE -> ReferencePurpose.INCLUDE;
                case IMPORT_PURPOSE -> ReferencePurpose.TYPE_DEFINITION_IMPORT;
                default -> null;
            };
            if (purpose != null) {
                references.get(purpose).put(reference.path(DmKeys.ALIAS).asText(),
                        reference.path(DmKeys.REFERENCE).asText());
            }
        }
        references.replaceAll((purpose, entries) ->
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
        return java.util.Collections.unmodifiableMap(references);
    }

    // --- content / config -----------------------------------------------------------------------

    /** The model-level document configuration (content {@code modelConfig}). */
    public DocConfig config() {
        JsonNode c = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_CONFIG);
        List<String> supported = new ArrayList<>();
        for (JsonNode ch : c.path(DmKeys.SUPPORTED_CHARACTERS)) {
            supported.add(ch.asText());
        }
        return new DocConfig(
                c.path(DmKeys.DECIMAL_SEPARATOR).asText(),
                c.path(DmKeys.TIME_ZONE).asText(),
                c.path(DmKeys.CONDITION_LANGUAGE).path(DmKeys.CODE).asText(),
                c.path(DmKeys.FIELD_REF_BY_SHORT_NAME_ALLOWED).asBoolean(),
                List.copyOf(supported));
    }

    /** The number of validation rules declared anywhere in the element tree (kernel-free count). */
    public int ruleCount() {
        return countByType(ElementType.RULE.wireName());
    }

    /** The number of computations declared anywhere in the element tree (kernel-free count). */
    public int computationCount() {
        return countByType(ElementType.COMPUTATION.wireName());
    }

    private int countByType(String type) {
        int[] count = {0};
        countByType(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), type, count);
        return count[0];
    }

    private static void countByType(JsonNode elements, String type, int[] count) {
        for (JsonNode el : elements) {
            if (type.equals(el.path(DmKeys.TYPE).asText())) {
                count[0]++;
            }
            if (DmNodes.is(ElementType.GROUP, el)) {
                countByType(el.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS), type, count);
            }
        }
    }

    /** The ids of the reusable type definitions declared on the model, in document order. */
    public List<String> typeDefinitionIds() {
        List<String> out = new ArrayList<>();
        for (JsonNode t : root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS)) {
            out.add(t.path(DmKeys.ID).asText());
        }
        return out;
    }

    /** Every reusable type definition as complete typed authored state, keyed by id in document order. */
    public Map<String, TypeDefinitionState> typeDefinitions() {
        Map<String, TypeDefinitionState> definitions = new LinkedHashMap<>();
        for (JsonNode definition : root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS)) {
            String id = definition.path(DmKeys.ID).asText();
            definitions.put(id, new TypeDefinitionState(id,
                    FieldTypeSpecReader.read(definition.path(DmKeys.FIELD_TYPE)),
                    AuthoredStateReader.descriptive(definition)));
        }
        return java.util.Collections.unmodifiableMap(definitions);
    }

    /** The field-type discriminator of the type definition {@code id} (e.g. {@code NumberType}), if declared. */
    public Optional<String> typeDefinitionType(String id) {
        for (JsonNode t : root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS)) {
            if (id.equals(t.path(DmKeys.ID).asText())) {
                return Optional.ofNullable(t.path(DmKeys.FIELD_TYPE).path(DmKeys.TYPE).asText(null));
            }
        }
        return Optional.empty();
    }

    /**
     * The type definition {@code id}'s value kind as a clean {@link ValueKind} name (e.g. {@code "NUMBER"})
     * — the user-facing vocabulary every type-reporting surface uses, never the raw kernel discriminator.
     * Empty when there is no such def.
     */
    public Optional<String> typeDefinitionKind(String id) {
        return typeDefinitionType(id).map(d -> ValueKind.fromKernelType(d).name());
    }

    /**
     * The per-kind CONFIG of the type definition {@code id} as JSON text, projected into the same
     * schema-shape block a {@code typedef add} spec carries ({@code number} min/max/unit/scale, {@code string}
     * pattern/length, {@code enum} values, date/time formats). The sibling of {@link #fieldConfigJson} for
     * typedefs — so a constraint an agent put on a reusable type is verifiable through {@code typedef read}
     * alone. Empty when the def carries no surfaceable config (a bare NUMBER) or there is no such def.
     */
    public Optional<String> typeDefinitionConfigJson(String id) {
        return typeDefinitionConfig(id).map(ObjectNode::toString);
    }

    /** {@link #typeDefinitionConfigJson} as the internal node (the typed read-back peers route here; Set W —
     *  a Jackson type may not cross the published surface). */
    private Optional<ObjectNode> typeDefinitionConfig(String id) {
        JsonNode fieldType = typeDefinitionFieldType(id);
        if (fieldType == null || fieldType.isMissingNode()) {
            return Optional.empty();
        }
        ObjectNode cfg = DocumentModelDraft.readTypeConfig(fieldType);
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    // --- the element tree (name-path addressed) -------------------------------------------------

    /**
     * One immutable, authored-source snapshot for consumers that need both element order and group state.
     * It is collected in a single tree walk: callers do not need to call the path-addressed group getters
     * repeatedly (which would re-scan the tree for every property of every group).
     */
    public AuthoredState authoredState() {
        return AuthoredStateReader.read(root);
    }

    /** The authored element sequence and typed field/group/rule lookups, all in document order. */
    public record AuthoredState(List<AuthoredElement> elements, Map<String, FieldState> fields,
            Map<String, GroupState> groups, Map<String, RuleState> rules) {
        public AuthoredState {
            elements = List.copyOf(elements);
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            groups = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(groups));
            rules = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        }
    }

    /** An authored field/group/rule/computation at its name-path, used for mixed-family ordering. */
    public record AuthoredElement(ElementType type, String path) {}

    /** A field's complete authored type, requirements, and metadata. */
    public record FieldState(String path, FieldTypeSpec type, FieldRequirements requirements, FieldMeta meta) {}

    /** Requiredness plus the optional per-locale custom required-field messages. */
    public record FieldRequirements(Requiredness requiredness, Map<String, String> messages) {
        public FieldRequirements {
            messages = OrderedMap.copyOf(messages);
        }
    }

    /** A group's own authored properties; child structure stays represented by the child elements themselves. */
    public record GroupState(String path, GroupStructure structure, GroupMeta meta) {}

    /** A group's structural properties, excluding its children. */
    public record GroupStructure(int repeatability, Optional<String> indexField,
            List<ElementDraft.SortField> sortFields, Optional<MountState> mount) {
        public GroupStructure {
            indexField = indexField == null ? Optional.empty() : indexField;
            sortFields = sortFields == null ? List.of() : List.copyOf(sortFields);
            mount = mount == null ? Optional.empty() : mount;
        }
    }

    /** An include mount's alias and complete option set. */
    public record MountState(String alias, MountOptions options) {}

    /** A rule's authored descriptive metadata; logic and messages come from the bound adapter. */
    public record RuleState(String path, DescriptiveState meta) {}

    /** Descriptions and annotations shared by authored rules and type definitions. */
    public record DescriptiveState(Map<String, String> internalDescription, Map<String, String> externalDescription,
            List<ElementDraft.Annotation> annotations) {
        public DescriptiveState {
            internalDescription = OrderedMap.copyOf(internalDescription);
            externalDescription = OrderedMap.copyOf(externalDescription);
            annotations = List.copyOf(annotations);
        }
    }

    /** A reusable type definition's complete authored type and descriptive metadata. */
    public record TypeDefinitionState(String id, FieldTypeSpec type, DescriptiveState meta) {}

    /** The two authored model-reference purposes that participate in semantic diff. */
    public enum ReferencePurpose { INCLUDE, TYPE_DEFINITION_IMPORT }

    /** Every group's name-path (root groups + nested), in document order. */
    public List<String> groupPaths() {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (isGroup) {
                out.add(path);
            }
        });
        return out;
    }

    /** Every field's name-path, in document order. */
    public List<String> fieldPaths() {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (!isGroup) {
                out.add(path);
            }
        });
        return out;
    }

    /** The field-type discriminator at {@code fieldPath} (e.g. {@code StringType}, {@code TypeDefType}). */
    public Optional<String> fieldType(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(field.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE).path(DmKeys.TYPE).asText(null));
    }

    /**
     * The field's value kind at {@code fieldPath} as a clean {@link ValueKind} name (e.g. {@code "NUMBER"})
     * — the single user-facing vocabulary every type-reporting surface uses, so the raw kernel discriminator
     * ({@code NumberType}, {@code TypeDefType}) never leaks. A typedef-typed field resolves to its underlying
     * kind, matching what {@code model describe} reports (which classifies the {@code getEffectiveType()}).
     * Empty when there is no such field.
     */
    public Optional<String> fieldKind(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            return Optional.empty();
        }
        JsonNode fieldType = field.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE);
        if (!fieldType.hasNonNull(DmKeys.TYPE)) {
            return Optional.empty();
        }
        return Optional.of(kindOf(fieldType));
    }

    /** Map a stored {@code fieldType} node to its clean {@link ValueKind} name, following a typedef reference
     *  to its underlying discriminator first (a dangling/empty reference is honestly {@code UNKNOWN}). */
    private String kindOf(JsonNode fieldType) {
        String discriminator = fieldType.path(DmKeys.TYPE).asText(null);
        if ("TypeDefType".equals(discriminator)) {
            String typeDefId = fieldType.path("TypeDefType").path(DmKeys.TYPE_DEFINITION_ID).asText(null);
            discriminator = typeDefId == null ? null : typeDefinitionType(typeDefId).orElse(null);
        }
        return ValueKind.fromKernelType(discriminator).name();
    }

    /**
     * The field's per-kind CONFIG at {@code fieldPath} as JSON text, in the SAME schema-block shape
     * {@code field add}/{@code field modify} accept ({@code number}/{@code string}/{@code enum}/{@code date}/…,
     * or a {@code typedef} ref). The read-back of what was written, so a constraint (a NUMBER {@code min}/{@code
     * max}/{@code unit}, a STRING {@code pattern}/length, enum {@code values}, a date {@code format}) is
     * verifiable through {@code field read} alone. Empty when the field carries no surfaceable config
     * (a bare BOOLEAN/CONFIRM) or there is no such field. For a single declared constraint, prefer the typed
     * peers ({@link #fieldPattern}, {@link #fieldMinValue}, {@link #fieldMaxFractionalDigits}, …).
     */
    public Optional<String> fieldConfigJson(String fieldPath) {
        return fieldConfig(fieldPath).map(ObjectNode::toString);
    }

    /** {@link #fieldConfigJson} as the internal node (the typed read-back peers route here; Set W — a Jackson
     *  type may not cross the published surface). */
    private Optional<ObjectNode> fieldConfig(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            return Optional.empty();
        }
        JsonNode fieldType = field.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE);
        if (fieldType.isMissingNode()) {
            return Optional.empty();
        }
        ObjectNode cfg = DocumentModelDraft.readTypeConfig(fieldType);
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    /**
     * The stored values of an ENUMERATION field at {@code fieldPath}, in document order — the typed
     * read-back peer of the enum builder, so a consumer need not navigate the raw {@link #fieldConfig}
     * {@code enum.values[].value} block. Empty when the field is not an enumeration or has no values.
     */
    public List<String> enumValues(String fieldPath) {
        return fieldConfig(fieldPath)
                .map(config -> {
                    List<String> values = new ArrayList<>();
                    config.path("enum").path(DmKeys.VALUES).forEach(v -> values.add(v.path(DmKeys.VALUE).asText()));
                    return values;
                })
                .orElseGet(List::of);
    }

    /**
     * The declared regex {@code pattern} of a STRING field at {@code fieldPath}, if any — a typed read-back
     * peer of {@link #enumValues}, so a consumer reads a declared constraint without navigating the raw
     * {@link #fieldConfig} schema-block. Empty when the field has no pattern (or is not a string / not present).
     */
    public Optional<String> fieldPattern(String fieldPath) {
        return blockText(fieldConfig(fieldPath), "string", "pattern");
    }

    /** The declared {@code minLength} of a STRING field at {@code fieldPath}, if any. */
    public OptionalInt fieldMinLength(String fieldPath) {
        return blockInt(fieldConfig(fieldPath), "string", "minLength");
    }

    /** The declared {@code maxLength} of a STRING field at {@code fieldPath}, if any. */
    public OptionalInt fieldMaxLength(String fieldPath) {
        return blockInt(fieldConfig(fieldPath), "string", "maxLength");
    }

    /** The declared minimum value of a NUMBER field at {@code fieldPath}, if any (stored as text → {@link BigDecimal}). */
    public Optional<BigDecimal> fieldMinValue(String fieldPath) {
        return blockDecimal(fieldConfig(fieldPath), "number", "min");
    }

    /** The declared maximum value of a NUMBER field at {@code fieldPath}, if any. */
    public Optional<BigDecimal> fieldMaxValue(String fieldPath) {
        return blockDecimal(fieldConfig(fieldPath), "number", "max");
    }

    /**
     * The declared decimal scale ({@code maxFractionalDigits}) of a NUMBER field at {@code fieldPath}, if any.
     * An a12 number is always scale-bounded (the kernel defaults the scale to 0 — KERNEL-FINDINGS), so this is
     * present for any NUMBER that surfaces config; empty for a non-number field or one with no config.
     */
    public OptionalInt fieldMaxFractionalDigits(String fieldPath) {
        return blockInt(fieldConfig(fieldPath), "number", "maxFractionalDigits");
    }

    /** The declared {@code pattern} of a leaf typeDefinition {@code id}, if any — the typedef peer of {@link
     *  #fieldPattern} (a typedef-typed field's own {@link #fieldConfig} is empty; its constraints live here). */
    public Optional<String> typeDefinitionPattern(String id) {
        return blockText(typeDefinitionConfig(id), "string", "pattern");
    }

    /** The declared {@code minLength} of a leaf typeDefinition {@code id}, if any. */
    public OptionalInt typeDefinitionMinLength(String id) {
        return blockInt(typeDefinitionConfig(id), "string", "minLength");
    }

    /** The declared {@code maxLength} of a leaf typeDefinition {@code id}, if any. */
    public OptionalInt typeDefinitionMaxLength(String id) {
        return blockInt(typeDefinitionConfig(id), "string", "maxLength");
    }

    /** The declared minimum value of a leaf number typeDefinition {@code id}, if any. */
    public Optional<BigDecimal> typeDefinitionMinValue(String id) {
        return blockDecimal(typeDefinitionConfig(id), "number", "min");
    }

    /** The declared maximum value of a leaf number typeDefinition {@code id}, if any. */
    public Optional<BigDecimal> typeDefinitionMaxValue(String id) {
        return blockDecimal(typeDefinitionConfig(id), "number", "max");
    }

    /** The declared decimal scale of a leaf number typeDefinition {@code id}, if any. */
    public OptionalInt typeDefinitionMaxFractionalDigits(String id) {
        return blockInt(typeDefinitionConfig(id), "number", "maxFractionalDigits");
    }

    /** Read a textual leaf from a schema-block config ({@code <block>.<key>}); the read-back peers route here. */
    private static Optional<String> blockText(Optional<ObjectNode> config, String block, String key) {
        return config.map(c -> c.path(block).path(key)).filter(JsonNode::isTextual).map(JsonNode::asText);
    }

    /** Read an integral leaf from a schema-block config; empty when absent or not integral. */
    private static OptionalInt blockInt(Optional<ObjectNode> config, String block, String key) {
        if (config.isEmpty()) {
            return OptionalInt.empty();
        }
        JsonNode node = config.get().path(block).path(key);
        return node.canConvertToInt() ? OptionalInt.of(node.asInt()) : OptionalInt.empty();
    }

    /** Read a decimal leaf stored as text ({@code number.min}/{@code number.max} are text); empty if unparseable. */
    private static Optional<BigDecimal> blockDecimal(Optional<ObjectNode> config, String block, String key) {
        return blockText(config, block, key).flatMap(ModelDocument::parseDecimal);
    }

    private static Optional<BigDecimal> parseDecimal(String text) {
        try {
            return Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The field's requiredness at {@code fieldPath} — the friendly form of the stored
     * {@code Field.requirednessConfig.mode} (absent = {@link Requiredness#OPTIONAL}). Empty if no such field.
     */
    public Optional<Requiredness> requiredness(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            return Optional.empty();
        }
        return Optional.of(Requiredness.fromMode(
                field.path(ElementType.FIELD.wireName()).path(DmKeys.REQUIREDNESS_CONFIG).path("mode").asText(null)));
    }

    /** Whether the group at {@code groupPath} is repeatable (repeatability &gt; 1). */
    public boolean isRepeating(String groupPath) {
        JsonNode group = locate(groupPath, true);
        return group != null && group.path(ElementType.GROUP.wireName()).path(DmKeys.REPEATABILITY).asInt(1) > 1;
    }

    /** The group's maximum repetitions — the repeatability count ({@code 1} = non-repeatable). */
    public int maxRepetitions(String groupPath) {
        JsonNode group = locate(groupPath, true);
        return group == null ? 1 : group.path(ElementType.GROUP.wireName()).path(DmKeys.REPEATABILITY).asInt(1);
    }

    // --- structure edits (M8) — surgical mutations of the source tree; validate() is the kernel gate -

    /**
     * Add a field of {@code kind} to the group at {@code groupPath} (an "easy" kind — String/Number/Date/
     * Boolean/Confirm — whose type node needs no inline config). Mutates this document; chain
     * {@link #validate()} to confirm the result is kernel-valid. Fails fast if the group does not exist.
     */
    public ModelDocument addField(String groupPath, String name, FieldKind kind) {
        return addField(groupPath, name, DocumentModelDraft.bareSpec(kind));
    }

    /**
     * Add a field with full type config — a {@link FieldTypeSpec} (string bounds/pattern, number scale,
     * enum values + categories, date format/precision, a {@code TypeDefRef}, …). The richer peer of the
     * {@link FieldKind} overload (which is just {@code addField(g, n, bareSpec(kind))}).
     */
    public ModelDocument addField(String groupPath, String name, FieldTypeSpec type) {
        return addField(groupPath, new ElementDraft.Field(name, type, Requiredness.OPTIONAL));
    }

    /**
     * Add a fully-configured {@link ElementDraft.Field} — its type, requiredness, per-locale labels, element
     * annotations, and the field-level metadata (global/transient flags, descriptions, helper text) — emitted via
     * the same node builder a freshly-authored field uses. The rich peer of the {@code (name, type)} overload;
     * the CLI {@code field add <spec.json>} path builds the full field, then chains {@link #validate}.
     */
    public ModelDocument addField(String groupPath, ElementDraft.Field field) {
        containerElements(groupPath).add(DocumentModelDraft.fieldNode(field, groupPath, locales()));
        return this;
    }

    /** Re-type an existing field — replace its {@code fieldType} with {@code type} (full config). */
    public ModelDocument setFieldType(String fieldPath, FieldTypeSpec type) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            throw new IllegalArgumentException("no field at path: " + fieldPath);
        }
        DocumentModelDraft.writeFieldType(((ObjectNode) field.path(ElementType.FIELD.wireName())).putObject(DmKeys.FIELD_TYPE),
                type, locales());
        return this;
    }

    /**
     * Set the per-locale display labels of a field or group. A label must cover <em>every</em> declared
     * locale (the kernel's rule, uniform with the builder) — fails fast otherwise.
     */
    public ModelDocument setLabel(String elementPath, Map<String, String> localeToText) {
        ObjectNode payload = elementPayload(elementPath);
        ArrayNode label = payload.putArray(DmKeys.LABEL);
        for (String loc : locales()) {
            if (!localeToText.containsKey(loc)) {
                throw new IllegalArgumentException("label for " + elementPath
                        + " is missing the declared locale '" + loc + "'");
            }
            label.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, localeToText.get(loc));
        }
        return this;
    }

    /** Set a name/value annotation on any describable element or type definition (replacing a same-named entry). */
    public ModelDocument setAnnotation(String elementPath, String name, String value) {
        ObjectNode element = metaElement(elementPath);
        ArrayNode annotations = element.has(DmKeys.ANNOTATIONS)
                ? (ArrayNode) element.get(DmKeys.ANNOTATIONS)
                : element.putArray(DmKeys.ANNOTATIONS);
        for (JsonNode a : annotations) {
            if (name.equals(a.path(DmKeys.NAME).asText())) {
                ((ObjectNode) a).put(DmKeys.VALUE, value);
                return this;
            }
        }
        annotations.addObject().put(DmKeys.NAME, name).put(DmKeys.VALUE, value);
        return this;
    }

    /** Remove the annotation named {@code name} from the element at {@code elementPath} (no-op if absent). */
    public ModelDocument removeAnnotation(String elementPath, String name) {
        filterArray(metaElement(elementPath), DmKeys.ANNOTATIONS, DmKeys.NAME, name);
        return this;
    }

    /**
     * Upsert one per-locale {@code internalDescription} entry on the element at {@code elementPath} — a
     * field, group, rule, computation, or type definition (the kernel's {@code IDescribed},
     * authoring/maintenance text). Replaces the
     * text for an existing locale.
     */
    public ModelDocument setInternalDescription(String elementPath, String locale, String text) {
        upsertLocalized(metaElement(elementPath), DmKeys.INTERNAL_DESCRIPTION, locale, text);
        return this;
    }

    /** Upsert one per-locale {@code externalDescription} entry on the element at {@code elementPath} (end-user text). */
    public ModelDocument setExternalDescription(String elementPath, String locale, String text) {
        upsertLocalized(metaElement(elementPath), DmKeys.EXTERNAL_DESCRIPTION, locale, text);
        return this;
    }

    /** Remove the {@code internalDescription} entry for {@code locale} from the element at {@code elementPath}. */
    public ModelDocument removeInternalDescription(String elementPath, String locale) {
        filterArray(metaElement(elementPath), DmKeys.INTERNAL_DESCRIPTION, DmKeys.LOCALE, locale);
        return this;
    }

    /** Remove the {@code externalDescription} entry for {@code locale} from the element at {@code elementPath}. */
    public ModelDocument removeExternalDescription(String elementPath, String locale) {
        filterArray(metaElement(elementPath), DmKeys.EXTERNAL_DESCRIPTION, DmKeys.LOCALE, locale);
        return this;
    }

    /** Upsert a {@code [{locale, text}]} entry under {@code key} on {@code element}. */
    private static void upsertLocalized(ObjectNode element, String key, String locale, String text) {
        ArrayNode array = element.has(key) ? (ArrayNode) element.get(key) : element.putArray(key);
        for (JsonNode e : array) {
            if (locale.equals(e.path(DmKeys.LOCALE).asText())) {
                ((ObjectNode) e).put(DmKeys.TEXT, text);
                return;
            }
        }
        array.addObject().put(DmKeys.LOCALE, locale).put(DmKeys.TEXT, text);
    }

    /** Drop every entry of {@code element[key]} whose {@code matchField} equals {@code matchValue}; remove the array if it empties. */
    private static void filterArray(ObjectNode element, String key, String matchField, String matchValue) {
        JsonNode array = element.path(key);
        if (!array.isArray()) {
            return;
        }
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode e : array) {
            if (!matchValue.equals(e.path(matchField).asText())) {
                kept.add(e);
            }
        }
        if (kept.isEmpty()) {
            element.remove(key);
        } else {
            element.set(key, kept);
        }
    }

    /** Upsert one model-level header <b>label</b> locale (the model-level peer of {@link #setLabel}). */
    public ModelDocument setModelLabel(String locale, String text) {
        ArrayNode labels = headerArray(DmKeys.LABELS);
        for (JsonNode l : labels) {
            if (locale.equals(l.path(DmKeys.LOCALE).asText())) {
                ((ObjectNode) l).put(DmKeys.TEXT, text);
                return this;
            }
        }
        labels.addObject().put(DmKeys.LOCALE, locale).put(DmKeys.TEXT, text);
        return this;
    }

    /** Upsert a model-level header <b>annotation</b> (replacing any existing entry with the same name). */
    public ModelDocument setModelAnnotation(String name, String value) {
        ArrayNode annotations = headerArray(DmKeys.ANNOTATIONS);
        for (JsonNode a : annotations) {
            if (name.equals(a.path(DmKeys.NAME).asText())) {
                ((ObjectNode) a).put(DmKeys.VALUE, value);
                return this;
            }
        }
        annotations.addObject().put(DmKeys.NAME, name).put(DmKeys.VALUE, value);
        return this;
    }

    /** Remove the model-level header annotation named {@code name} (no-op if absent; drops the array if it empties). */
    public ModelDocument removeModelAnnotation(String name) {
        JsonNode header = root.path(DmKeys.HEADER);
        if (header.isObject()) {
            filterArray((ObjectNode) header, DmKeys.ANNOTATIONS, DmKeys.NAME, name);
        }
        return this;
    }

    /** Set the model-level {@code comment} ({@code modelInfo.comment} — a free-text model description/note). */
    public ModelDocument setModelComment(String text) {
        modelInfo().put(DmKeys.COMMENT, text);
        return this;
    }

    /** Remove the model-level {@code comment}. */
    public ModelDocument clearModelComment() {
        modelInfo().remove(DmKeys.COMMENT);
        return this;
    }

    /** The model-level {@code comment} ({@code modelInfo.comment}), if set. */
    public Optional<String> modelComment() {
        JsonNode comment = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_INFO).path(DmKeys.COMMENT);
        return comment.isTextual() ? Optional.of(comment.asText()) : Optional.empty();
    }

    /** The model-level header <b>annotations</b> as a name→value map (the read peer of {@link #setModelAnnotation}). */
    public Map<String, String> modelAnnotations() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode a : root.path(DmKeys.HEADER).path(DmKeys.ANNOTATIONS)) {
            out.put(a.path(DmKeys.NAME).asText(), a.path(DmKeys.VALUE).asText());
        }
        return out;
    }

    /** The model-level header <b>labels</b> as a locale→text map (the read peer of {@link #setModelLabel}). */
    public Map<String, String> modelLabels() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode l : root.path(DmKeys.HEADER).path(DmKeys.LABELS)) {
            out.put(l.path(DmKeys.LOCALE).asText(), l.path(DmKeys.TEXT).asText());
        }
        return out;
    }

    /** The header's {@code key} array, created if absent. */
    private ArrayNode headerArray(String key) {
        ObjectNode header = (ObjectNode) root.path(DmKeys.HEADER);
        return header.has(key) ? (ArrayNode) header.get(key) : header.putArray(key);
    }

    /** The {@code Field} body object at {@code fieldPath}; fails fast if there is no field there. */
    private ObjectNode fieldBody(String fieldPath) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            throw new IllegalArgumentException("no field at path: " + fieldPath);
        }
        return (ObjectNode) field.path(ElementType.FIELD.wireName());
    }

    /** Set (or clear, with {@link Requiredness#OPTIONAL}) the requiredness of an existing field. A standalone
     *  mode change mutates {@code mode} in place, so a custom required-error message already stored in the same
     *  {@code requirednessConfig} (see {@link #setRequiredErrorMessages}) survives; clearing to OPTIONAL drops
     *  the whole config (an optional field has no required-error message). */
    public ModelDocument setRequiredness(String fieldPath, Requiredness requiredness) {
        ObjectNode body = fieldBody(fieldPath);
        String mode = requiredness.mode();
        if (mode == null) {
            body.remove(DmKeys.REQUIREDNESS_CONFIG);
        } else {
            JsonNode existing = body.path(DmKeys.REQUIREDNESS_CONFIG);
            ObjectNode config = existing.isObject() ? (ObjectNode) existing : body.putObject(DmKeys.REQUIREDNESS_CONFIG);
            config.put("mode", mode);
        }
        return this;
    }

    /**
     * Set the per-locale custom error message shown when a <b>required</b> field is left empty
     * ({@code requirednessConfig.errorMessage} — the "Use Default Error Messages" deselected case, G23). The
     * field must already be required (carry a {@code requirednessConfig}); the kernel requires every declared
     * locale to be covered. Omit it to fall back to the kernel's default required-field message.
     */
    public ModelDocument setRequiredErrorMessages(String fieldPath, Map<String, String> localeToText) {
        ObjectNode body = fieldBody(fieldPath);
        JsonNode rc = body.path(DmKeys.REQUIREDNESS_CONFIG);
        if (!rc.isObject()) {
            throw new IllegalArgumentException("a required error message needs " + fieldPath
                    + " to be required first (set required:true or \"ifParentPresent\")");
        }
        ArrayNode messages = ((ObjectNode) rc).putArray(DmKeys.ERROR_MESSAGE);
        localeToText.forEach((loc, text) -> messages.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, text));
        return this;
    }

    /** Set a field's per-locale helper text (replacing any existing). Unlike a label it need not cover every locale. */
    public ModelDocument setHelperText(String fieldPath, Map<String, String> localeToText) {
        ObjectNode body = fieldBody(fieldPath);
        ArrayNode helper = body.putArray("helperText");
        localeToText.forEach((loc, text) -> helper.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, text));
        return this;
    }

    /** Set a field's {@code global} flag (shared across repetitions); {@code false} removes it (the kernel default). */
    public ModelDocument setGlobal(String fieldPath, boolean global) {
        return setFieldFlag(fieldPath, "global", global);
    }

    /** Set a field's {@code transient} flag (not persisted); {@code false} removes it (the kernel default). */
    public ModelDocument setTransient(String fieldPath, boolean isTransient) {
        return setFieldFlag(fieldPath, "transient", isTransient);
    }

    private ModelDocument setFieldFlag(String fieldPath, String flag, boolean on) {
        ObjectNode body = fieldBody(fieldPath);
        if (on) {
            body.put(flag, true);
        } else {
            body.remove(flag);
        }
        return this;
    }

    /**
     * Apply a {@link FieldModification} to the field at {@code fieldPath} — each present aspect is set, each
     * absent one left as-is. This is the <b>single</b> place a partial field edit is realized, shared by the
     * CLI's standalone {@code field modify} and the {@code apply}/{@code batch} field-modify op, so neither
     * can silently drop an aspect the spec carried (the false-{@code applied} trap a type-only modify caused).
     */
    public ModelDocument applyModification(String fieldPath, FieldModification mod) {
        mod.type().ifPresent(t -> setFieldType(fieldPath, t));
        mod.requiredness().ifPresent(r -> setRequiredness(fieldPath, r));
        // after requiredness, so the requirednessConfig exists for the custom message to attach to (G23)
        mod.requiredMessages().ifPresent(m -> setRequiredErrorMessages(fieldPath, m));
        mod.label().ifPresent(l -> setLabel(fieldPath, l));
        mod.helperText().ifPresent(h -> setHelperText(fieldPath, h));
        mod.internalDescription().ifPresent(m -> m.forEach((loc, t) -> setInternalDescription(fieldPath, loc, t)));
        mod.externalDescription().ifPresent(m -> m.forEach((loc, t) -> setExternalDescription(fieldPath, loc, t)));
        mod.annotations().ifPresent(list -> list.forEach(a -> setAnnotation(fieldPath, a.name(), a.value())));
        mod.global().ifPresent(g -> setGlobal(fieldPath, g));
        mod.isTransient().ifPresent(t -> setTransient(fieldPath, t));
        return this;
    }

    /** The element node (field or group) at {@code path}; fails fast if absent. */
    private Optional<ObjectNode> findElement(String path) {
        JsonNode[] found = new JsonNode[1];
        walk((p, node, isGroup) -> {
            if (found[0] == null && p.equals(path)) {
                found[0] = node;
            }
        });
        return Optional.ofNullable((ObjectNode) found[0]);
    }

    private ObjectNode element(String path) {
        return findElement(path).orElseThrow(() -> new IllegalArgumentException("no element at path: " + path));
    }

    /**
     * Read the per-locale display labels of the element at {@code elementPath}. Present (possibly an empty
     * map) only for a <b>field or group</b> — the only describables that carry a display label; empty
     * {@code Optional} for a rule/computation/type-def (they have no label slot). The read peer of
     * {@link #setLabel}.
     */
    public Optional<Map<String, String>> labels(String elementPath) {
        return findElement(elementPath)
                .map(el -> localizedOf((ObjectNode) el.path(el.path(DmKeys.TYPE).asText()), DmKeys.LABEL));
    }

    /** The typed payload object ({@code "Field"} or {@code "Group"}) of the element at {@code path}. */
    private ObjectNode elementPayload(String path) {
        ObjectNode element = element(path);
        return (ObjectNode) element.path(element.path(DmKeys.TYPE).asText());
    }

    /**
     * Locate any describable node by its name-path or type-def id: a field, group, rule, or computation (by name-path,
     * broader than {@link #element}, which {@code walk} limits to fields/groups), or a <b>type definition</b>
     * (by id, since type-defs live in {@code content.typeDefinitions}, not the element tree). All carry the
     * same element-level {@code internalDescription}/{@code externalDescription}/{@code annotations} shape, so
     * one metadata API ({@link #setInternalDescription} / {@link #setAnnotation} / …) covers them all.
     */
    private ObjectNode metaElement(String pathOrTypeDefId) {
        ObjectNode found = findByPath(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), "", pathOrTypeDefId);
        if (found != null) {
            return found;
        }
        for (JsonNode td : root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS)) {
            if (pathOrTypeDefId.equals(td.path(DmKeys.ID).asText())) {
                return (ObjectNode) td;
            }
        }
        throw new IllegalArgumentException("no element or type definition: " + pathOrTypeDefId);
    }

    /**
     * Whether a describable node exists at {@code pathOrTypeDefId} — a field/group/rule/computation by name-path, or a
     * type definition by id. The pre-check for the metadata edit verbs (so a bogus reference refuses cleanly).
     */
    public boolean describableExists(String pathOrTypeDefId) {
        if (findByPath(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), "", pathOrTypeDefId) != null) {
            return true;
        }
        for (JsonNode td : root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS)) {
            if (pathOrTypeDefId.equals(td.path(DmKeys.ID).asText())) {
                return true;
            }
        }
        return false;
    }

    /** Read an element's per-locale {@code internalDescription}/{@code externalDescription} (the metadata read verbs). */
    public Map<String, String> internalDescription(String pathOrTypeDefId) {
        return localizedOf(metaElement(pathOrTypeDefId), DmKeys.INTERNAL_DESCRIPTION);
    }

    /** Read an element's external description, by locale. */
    public Map<String, String> externalDescription(String pathOrTypeDefId) {
        return localizedOf(metaElement(pathOrTypeDefId), DmKeys.EXTERNAL_DESCRIPTION);
    }

    /** Read a field's per-locale helper text; groups and other describable elements return an empty map. */
    public Map<String, String> helperText(String pathOrTypeDefId) {
        ObjectNode element = metaElement(pathOrTypeDefId);
        if (!DmNodes.is(ElementType.FIELD, element)) {
            return Map.of();
        }
        return localizedOf((ObjectNode) element.path(ElementType.FIELD.wireName()), "helperText");
    }

    /** Read an element's annotations as a name→value map. */
    public Map<String, String> annotations(String pathOrTypeDefId) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode a : metaElement(pathOrTypeDefId).path(DmKeys.ANNOTATIONS)) {
            out.put(a.path(DmKeys.NAME).asText(), a.path(DmKeys.VALUE).asText());
        }
        return out;
    }

    private static Map<String, String> localizedOf(ObjectNode element, String key) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode e : element.path(key)) {
            out.put(e.path(DmKeys.LOCALE).asText(), e.path(DmKeys.TEXT).asText());
        }
        return out;
    }

    private static ObjectNode findByPath(JsonNode container, String prefix, String target) {
        for (JsonNode node : container) {
            String path = prefix + "/" + node.path(DmKeys.NAME).asText();
            if (path.equals(target)) {
                return (ObjectNode) node;
            }
            if (DmNodes.is(ElementType.GROUP, node)) {
                ObjectNode inner = findByPath(node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS), path, target);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** Add a non-repeating group named {@code name} under {@code parentPath} ({@code ""} = a root group). */
    public ModelDocument addGroup(String parentPath, String name) {
        return addGroup(parentPath, name, 1);
    }

    /** Add a repeatable group ({@code maxRepetitions >= 2}) named {@code name} under {@code parentPath}. */
    public ModelDocument addRepeatableGroup(String parentPath, String name, int maxRepetitions) {
        if (maxRepetitions < 2) {
            throw new IllegalArgumentException("a repeatable group needs maxRepetitions >= 2, got " + maxRepetitions);
        }
        return addGroup(parentPath, name, maxRepetitions);
    }

    private ModelDocument addGroup(String parentPath, String name, int repeatability) {
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        ElementDraft.Group group = new ElementDraft.Group(name, repeatability, null, List.of(), GroupMeta.none());
        container.add(DocumentModelDraft.groupNode(group, parentPath, locales()));
        return this;
    }

    /**
     * Add a <b>Multi-Select</b> group template (FEATURE-GAPS) named {@code name} under {@code parentPath}
     * — a repeatable group with one enum/string selection field; {@code body} supplies the field + maxRows
     * via the same {@link DocumentModelDraft.MultiSelectBuilder} the from-scratch authoring path uses. The
     * kernel's {@code MultiSelectGroupRule} validates it on the next {@code validate(...)}.
     */
    public ModelDocument addMultiSelectGroup(String parentPath, String name,
            Consumer<DocumentModelDraft.MultiSelectBuilder> body) {
        DocumentModelDraft.MultiSelectBuilder b = new DocumentModelDraft.MultiSelectBuilder();
        body.accept(b);
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        container.add(DocumentModelDraft.groupNode(b.toGroup(name), parentPath, locales()));
        return this;
    }

    /**
     * Add an <b>Attachment</b> group template (FEATURE-GAPS) named {@code name} under {@code parentPath}
     * — the kernel's canonical file-attachment composition (8 fields + 4 rules + {@code content} config). The
     * mandated rules are emitted in the model's condition language; the model must be {@code en_US}/{@code
     * de_DE} (else the builder refuses). Validated by {@code AttachmentGroupRule} on the next {@code validate(...)}.
     */
    public ModelDocument addAttachmentGroup(String parentPath, String name,
            Consumer<DocumentModelDraft.AttachmentBuilder> body) {
        DocumentModelDraft.AttachmentBuilder b = new DocumentModelDraft.AttachmentBuilder();
        body.accept(b);
        ElementDraft.Group group = b.toGroup(name, locales(), config().conditionLanguage());
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        container.add(DocumentModelDraft.groupNode(group, parentPath, locales()));
        return this;
    }

    /** Remove the field at {@code fieldPath}. Fails fast if no field is there. */
    public ModelDocument removeField(String fieldPath) {
        removeElement(fieldPath, ElementType.FIELD.wireName());
        return this;
    }

    /** Remove the group at {@code groupPath} (and everything under it). Fails fast if no group is there. */
    public ModelDocument removeGroup(String groupPath) {
        removeElement(groupPath, ElementType.GROUP.wireName());
        return this;
    }

    /** Set the group at {@code groupPath} repeatable to {@code maxRepetitions} ({@code >= 2}). */
    public ModelDocument setRepeatable(String groupPath, int maxRepetitions) {
        if (maxRepetitions < 2) {
            throw new IllegalArgumentException("a repeatable group needs maxRepetitions >= 2, got " + maxRepetitions);
        }
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        ((ObjectNode) group.path(ElementType.GROUP.wireName())).put(DmKeys.REPEATABILITY, maxRepetitions);
        return this;
    }

    /** Set the group at {@code groupPath} non-repeating (a single group, {@code repeatability == 1}). */
    public ModelDocument setNonRepeatable(String groupPath) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        ((ObjectNode) group.path(ElementType.GROUP.wireName())).put(DmKeys.REPEATABILITY, 1);
        return this;
    }

    /** The group's index field (repetition row-key), if one is set. Fails fast if no group is there. */
    public Optional<String> indexField(String groupPath) {
        ObjectNode body = groupBody(groupPath);
        return Optional.ofNullable(body.path(DmKeys.INDEX_FIELD_NAME).asText(null));
    }

    /**
     * Designate a direct-child field of the <b>repeatable</b> group at {@code groupPath} as its index field
     * — the repetition row-key that arms {@code RepetitionNotUnique} + parallel iteration. Requires a
     * repeatable group (an index field on a single group is a degenerate row-key) and an existing
     * direct-child field; fails fast otherwise. The {@code validate()} gate stays the authoritative check.
     */
    public ModelDocument setIndexField(String groupPath, String fieldName) {
        ObjectNode body = groupBody(groupPath);
        if (body.path(DmKeys.REPEATABILITY).asInt(1) <= 1) {
            throw new IllegalArgumentException("an index field needs a repeatable group; " + groupPath
                    + " is not repeatable");
        }
        boolean childField = false;
        for (JsonNode e : body.path(DmKeys.ELEMENTS)) {
            if (DmNodes.is(ElementType.FIELD, e) && fieldName.equals(e.path(DmKeys.NAME).asText())) {
                childField = true;
                break;
            }
        }
        if (!childField) {
            throw new IllegalArgumentException("index field '" + fieldName
                    + "' is not a direct child field of " + groupPath);
        }
        body.put(DmKeys.INDEX_FIELD_NAME, fieldName);
        return this;
    }

    /** Clear the group's index field (no-op if none). Fails fast if no group is there. */
    public ModelDocument clearIndexField(String groupPath) {
        groupBody(groupPath).remove(DmKeys.INDEX_FIELD_NAME);
        return this;
    }

    /** The group's declared row sort fields (display/USB ordering), in declared order; empty if none. */
    public List<ElementDraft.SortField> sortFields(String groupPath) {
        List<ElementDraft.SortField> out = new ArrayList<>();
        for (JsonNode n : groupBody(groupPath).path(DmKeys.SORT_FIELDS)) {
            out.add(new ElementDraft.SortField(n.path(DmKeys.NAME).asText(),
                    SortOrder.valueOf(n.path(DmKeys.ORDER).asText("ASC"))));
        }
        return out;
    }

    /**
     * Set the declared row <b>sort fields</b> of the <b>repeatable</b> group at {@code groupPath} (replacing any
     * existing) — each a direct-child field + {@link SortOrder}, applied in list order. Requires a repeatable
     * group (sort order is per-row) and existing direct-child fields; fails fast otherwise. Display/USB-only —
     * the kernel does not reorder rows for validate/compute (KERNEL-FINDINGS / INTERPRETER-FINDINGS IF22). The
     * {@code validate()} gate stays the authoritative check.
     */
    public ModelDocument setSortFields(String groupPath, List<ElementDraft.SortField> fields) {
        ObjectNode body = groupBody(groupPath);
        if (body.path(DmKeys.REPEATABILITY).asInt(1) <= 1) {
            throw new IllegalArgumentException("sort fields need a repeatable group; " + groupPath
                    + " is not repeatable");
        }
        for (ElementDraft.SortField sf : fields) {
            boolean childField = false;
            for (JsonNode e : body.path(DmKeys.ELEMENTS)) {
                if (DmNodes.is(ElementType.FIELD, e) && sf.fieldName().equals(e.path(DmKeys.NAME).asText())) {
                    childField = true;
                    break;
                }
            }
            if (!childField) {
                throw new IllegalArgumentException("sort field '" + sf.fieldName()
                        + "' is not a direct child field of " + groupPath);
            }
        }
        ArrayNode arr = body.putArray(DmKeys.SORT_FIELDS);
        for (ElementDraft.SortField sf : fields) {
            ObjectNode n = arr.addObject();
            n.put(DmKeys.NAME, sf.fieldName());
            n.put(DmKeys.ORDER, sf.order().name());
        }
        return this;
    }

    /** Clear the group's declared row sort fields (no-op if none). Fails fast if no group is there. */
    public ModelDocument clearSortFields(String groupPath) {
        groupBody(groupPath).remove(DmKeys.SORT_FIELDS);
        return this;
    }

    /** The {@code "Group"} payload object at {@code groupPath}; fails fast if no group is there. */
    private ObjectNode groupBody(String groupPath) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        return (ObjectNode) group.path(ElementType.GROUP.wireName());
    }

    // --- doc config (content.modelConfig) -------------------------------------------------------

    /** Set the decimal separator (e.g. {@code "."} or {@code ","} — the comma-vs-point concern). */
    public ModelDocument setDecimalSeparator(String separator) {
        modelConfig().put(DmKeys.DECIMAL_SEPARATOR, separator);
        return this;
    }

    /**
     * Set the model's time zone. A missing slot defaults to UTC; exact {@code UTC}, {@code GMT}, and
     * {@code Europe/Berlin} are supported by the interpreter-backed evaluator. Another known Java time-zone
     * id is kernel-valid but outside that evaluator set, while null, blank, and unknown/typo ids are invalid.
     * The next kernel validation remains authoritative.
     */
    public ModelDocument setTimeZone(String timeZone) {
        modelConfig().put(DmKeys.TIME_ZONE, timeZone);
        return this;
    }

    /** Set the condition language code (e.g. {@code "en_US"}, {@code "de_DE"}). */
    public ModelDocument setConditionLanguage(String localeCode) {
        ObjectNode config = modelConfig();
        JsonNode cl = config.path(DmKeys.CONDITION_LANGUAGE);
        ObjectNode node = cl.isObject() ? (ObjectNode) cl : config.putObject(DmKeys.CONDITION_LANGUAGE);
        node.put(DmKeys.CODE, localeCode);
        return this;
    }

    /** Allow (or forbid) short-name field references where unique — the {@code modelConfig} flag. */
    public ModelDocument setFieldRefByShortNameAllowed(boolean allowed) {
        modelConfig().put(DmKeys.FIELD_REF_BY_SHORT_NAME_ALLOWED, allowed);
        return this;
    }

    /**
     * Set the {@code modelConfig} allowed-character set (checked against string/enum/custom values). Each
     * entry is a singleton BMP character, an inclusive range such as {@code " -~"}, or one atomic combined
     * grapheme. An empty list removes the slot and selects the full-BMP default; surrogates and supplementary
     * characters remain illegal.
     *
     * @see SupportedCharactersContract
     */
    public ModelDocument setSupportedCharacters(List<String> chars) {
        List<String> valid = SupportedCharactersContract.requireValid(chars);
        ObjectNode config = modelConfig();
        config.remove(DmKeys.SUPPORTED_CHARACTERS);
        if (!valid.isEmpty()) {
            ArrayNode array = config.putArray(DmKeys.SUPPORTED_CHARACTERS);
            valid.forEach(array::add);
        }
        return this;
    }

    /** Set the model base year ({@code modelInfo.baseYear}) — resolves year-less dates / the BaseYear point-in-time. */
    public ModelDocument setBaseYear(int year) {
        modelInfo().put(DmKeys.BASE_YEAR, String.valueOf(year));
        return this;
    }

    /** The model's base year ({@code modelInfo.baseYear}), if set — the read peer of {@link #setBaseYear}
     *  (the kernel stores it as a string, so tolerate both textual and numeric). */
    public Optional<Integer> baseYear() {
        JsonNode by = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_INFO).path(DmKeys.BASE_YEAR);
        if (by.isInt()) {
            return Optional.of(by.asInt());
        }
        if (by.isTextual() && !by.asText().isBlank()) {
            try {
                return Optional.of(Integer.parseInt(by.asText().trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private ObjectNode modelConfig() {
        JsonNode config = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_CONFIG);
        if (!config.isObject()) {
            throw new IllegalStateException("model has no content.modelConfig object");
        }
        return (ObjectNode) config;
    }

    private ObjectNode modelInfo() {
        JsonNode info = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_INFO);
        if (!info.isObject()) {
            throw new IllegalStateException("model has no content.modelInfo object");
        }
        return (ObjectNode) info;
    }

    // --- type definitions (content.typeDefinitions) ---------------------------------------------

    /** Declare a reusable type definition {@code id} over an easy {@code underlying} kind. */
    public ModelDocument addTypeDefinition(String id, FieldKind underlying) {
        return addTypeDefinition(id, DocumentModelDraft.bareSpec(underlying));
    }

    /** Declare a reusable type definition {@code id} over a fully-configured {@code type} (scale, pattern, enum, …). */
    public ModelDocument addTypeDefinition(String id, FieldTypeSpec type) {
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        ArrayNode array = tds.isArray() ? (ArrayNode) tds
                : ((ObjectNode) root.path(DmKeys.CONTENT)).putArray(DmKeys.TYPE_DEFINITIONS);
        ObjectNode node = array.addObject();
        node.put(DmKeys.ID, id);
        node.put(DmKeys.NAME, id);
        DocumentModelDraft.writeFieldType(node.putObject(DmKeys.FIELD_TYPE), type, locales());
        return this;
    }

    /** Re-configure the type definition {@code id} to a bare easy kind (config-free); fails fast if absent. */
    public ModelDocument setTypeDefinitionType(String id, FieldKind underlying) {
        return setTypeDefinitionType(id, DocumentModelDraft.bareSpec(underlying));
    }

    /** Re-configure the underlying type of the type definition {@code id}; fails fast if absent. */
    public ModelDocument setTypeDefinitionType(String id, FieldTypeSpec type) {
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        if (tds.isArray()) {
            for (JsonNode td : tds) {
                if (id.equals(td.path(DmKeys.ID).asText())) {
                    DocumentModelDraft.writeFieldType(((ObjectNode) td).putObject(DmKeys.FIELD_TYPE), type, locales());
                    return this;
                }
            }
        }
        throw new IllegalArgumentException("no type definition with id: " + id);
    }

    /**
     * Every field path typed by the type definition {@code id} (its {@code typeDefinitionId} pointer), in
     * document order — the structural <em>find</em> leg a typedef refactor rewrites against (task #2).
     */
    public List<String> fieldsUsingTypeDefinition(String id) {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (!isGroup && id.equals(node.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE)
                    .path("TypeDefType").path(DmKeys.TYPE_DEFINITION_ID).asText(null))) {
                out.add(path);
            }
        });
        return out;
    }

    // --- rename refactors (CLI-SPEC §6 — structural; the CLI safety gate refuses if the element is referenced) ---

    /** Rename the field at {@code fieldPath} to {@code newName} (its name + id). Fails fast if absent. */
    public ModelDocument renameField(String fieldPath, String newName) {
        JsonNode field = locate(fieldPath, false);
        if (field == null) {
            throw new IllegalArgumentException("no field at: " + fieldPath);
        }
        ((ObjectNode) field).put(DmKeys.NAME, newName).put(DmKeys.ID, renamePath(fieldPath, newName));
        return this;
    }

    /**
     * Rename the group at {@code groupPath} to {@code newName} — its name + id AND the id-path of every
     * descendant element (fields, sub-groups, rules, computations), in step. Descendant <em>names</em> are
     * unchanged, so rules inside the subtree (which reference relatively) keep working. Fails fast if absent.
     */
    public ModelDocument renameGroup(String groupPath, String newName) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at: " + groupPath);
        }
        ((ObjectNode) group).put(DmKeys.NAME, newName);
        reprefixIds(group, groupPath, renamePath(groupPath, newName));
        return this;
    }

    /** Rename the rule at {@code ruleFullName} to {@code newName} (its name + id). Fails fast if absent. */
    public ModelDocument renameRule(String ruleFullName, String newName) {
        ObjectNode rule = findById(ruleFullName);
        if (rule == null || !DmNodes.is(ElementType.RULE, rule)) {
            throw new IllegalArgumentException("no rule at: " + ruleFullName);
        }
        rule.put(DmKeys.NAME, newName).put(DmKeys.ID, renamePath(ruleFullName, newName));
        return this;
    }

    // --- move refactors (CLI-SPEC §6 — relocate to a different parent; the CLI rewrites references first) ---

    /**
     * Move the field at {@code fieldPath} to become a child of the group at {@code newParentPath} — detach
     * the node, re-home it, and re-prefix its id to the new parent (the {@code name} is unchanged; only the
     * location, hence the id-path, moves). References to the field are the caller's concern — the CLI
     * rewrites them via {@code adapter.ReferenceRewriter} (the kernel's {@code MoveSupportDM}) <em>before</em>
     * this call; {@link #validate()} is the kernel backstop. Fails fast if the field is absent or the
     * destination group does not exist.
     */
    public ModelDocument moveField(String fieldPath, String newParentPath) {
        JsonNode node = locate(fieldPath, false);
        if (node == null) {
            throw new IllegalArgumentException("no field at: " + fieldPath);
        }
        relocate(node, fieldPath, newParentPath, ElementType.FIELD.wireName());
        return this;
    }

    /**
     * Move the group at {@code groupPath} (and its whole subtree) under the group at {@code newParentPath}
     * ({@code ""} = a root group) — the group node and every descendant id move in step; descendant
     * <em>names</em> are unchanged, so an internal relative-pathed rule travels intact. References from
     * outside the subtree are the caller's concern (rewritten first, as for {@link #moveField}). Fails fast
     * if the group is absent, the destination group does not exist, or the destination lies inside the
     * moved subtree (which would orphan it).
     */
    public ModelDocument moveGroup(String groupPath, String newParentPath) {
        JsonNode node = locate(groupPath, true);
        if (node == null) {
            throw new IllegalArgumentException("no group at: " + groupPath);
        }
        if (newParentPath.equals(groupPath) || newParentPath.startsWith(groupPath + "/")) {
            throw new IllegalArgumentException(
                    "cannot move group " + groupPath + " into itself or its own subtree: " + newParentPath);
        }
        relocate(node, groupPath, newParentPath, ElementType.GROUP.wireName());
        return this;
    }

    /**
     * Detach {@code node} from its current parent and re-home it under {@code newParentPath}, re-prefixing
     * its id-subtree from {@code path} to the new location. The destination is resolved <em>before</em> any
     * detach, so an unknown parent leaves the tree untouched (fail-fast, no half-move).
     */
    private void relocate(JsonNode node, String path, String newParentPath, String type) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String newPath = newParentPath.isEmpty() ? "/" + name : newParentPath + "/" + name;
        ArrayNode destination = newParentPath.isEmpty() ? rootGroups() : containerElements(newParentPath);
        removeElement(path, type);
        reprefixIds(node, path, newPath);
        destination.add(node);
    }

    private static String renamePath(String path, String newName) {
        return path.substring(0, path.lastIndexOf('/') + 1) + newName;
    }

    /** Re-prefix the id of {@code element} and every descendant from {@code oldPrefix} to {@code newPrefix}. */
    private static void reprefixIds(JsonNode element, String oldPrefix, String newPrefix) {
        if (!(element instanceof ObjectNode obj)) {
            return;
        }
        String id = obj.path(DmKeys.ID).asText(null);
        if (id != null && (id.equals(oldPrefix) || id.startsWith(oldPrefix + "/"))) {
            obj.put(DmKeys.ID, newPrefix + id.substring(oldPrefix.length()));
        }
        if (DmNodes.is(ElementType.GROUP, obj)) {
            for (JsonNode child : obj.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                reprefixIds(child, oldPrefix, newPrefix);
            }
        } else if (DmNodes.is(ElementType.RULE, obj) && obj.path(ElementType.RULE.wireName()) instanceof ObjectNode rule) {
            // a carried rule's ABSOLUTE condition operands must re-base to the new root too (probe 01-de
            // Finding 6) — else the sub-model references the old foreign root (MVK_INVALID_ROOT_GROUP). The
            // errorEntityRelPath is relative ('../X') so it rides along unchanged.
            reprefixText(rule, "errorCondition", oldPrefix, newPrefix);
        } else if (DmNodes.is(ElementType.COMPUTATION, obj) && obj.path(ElementType.COMPUTATION.wireName()) instanceof ObjectNode comp) {
            reprefixText(comp, "commonPrecondition", oldPrefix, newPrefix);
            for (JsonNode alt : comp.path("computationAlternatives")) {
                if (alt instanceof ObjectNode altObj) {
                    reprefixText(altObj, "precondition", oldPrefix, newPrefix);
                    reprefixText(altObj, "operation", oldPrefix, newPrefix);
                }
            }
        }
    }

    /** Re-prefix every ABSOLUTE path operand in {@code holder.field} from {@code oldPrefix} to {@code newPrefix},
     *  only at a path-segment boundary (so {@code /Order/ShippingExtra} is never matched by {@code /Order/Shipping}). */
    private static void reprefixText(ObjectNode holder, String field, String oldPrefix, String newPrefix) {
        String text = holder.path(field).asText(null);
        if (text == null || !text.contains(oldPrefix)) {
            return;
        }
        String rebased = text.replaceAll(
                java.util.regex.Pattern.quote(oldPrefix) + "(?=[/)\\],\\s]|$)",
                java.util.regex.Matcher.quoteReplacement(newPrefix));
        holder.put(field, rebased);
    }

    /** Find any element (field/group/rule/computation) by its full-name id; {@code null} if absent. */
    private ObjectNode findById(String id) {
        ObjectNode[] found = {null};
        for (JsonNode rootGroup : root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS)) {
            findById(rootGroup, id, found);
        }
        return found[0];
    }

    private void findById(JsonNode node, String id, ObjectNode[] found) {
        if (found[0] != null) {
            return;
        }
        if (node instanceof ObjectNode obj && id.equals(obj.path(DmKeys.ID).asText(null))) {
            found[0] = obj;
            return;
        }
        if (DmNodes.is(ElementType.GROUP, node)) {
            for (JsonNode child : node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                findById(child, id, found);
            }
        }
    }

    /**
     * Rename the type definition {@code oldId} to {@code newId} — its {@code typeDefinitions} entry AND
     * every using field's {@code typeDefinitionId}, in step, so nothing dangles (task #3, a structural
     * refactor: a typedef never appears in condition DSL). Fails fast if {@code oldId} is absent; a
     * collision with an existing id is the caller's gate to refuse, with {@link #validate()} as backstop.
     */
    public ModelDocument renameTypeDefinition(String oldId, String newId) {
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        boolean renamed = false;
        if (tds.isArray()) {
            for (JsonNode td : tds) {
                if (oldId.equals(td.path(DmKeys.ID).asText())) {
                    ObjectNode node = (ObjectNode) td;
                    node.put(DmKeys.ID, newId);
                    if (oldId.equals(node.path(DmKeys.NAME).asText())) {
                        node.put(DmKeys.NAME, newId);   // a default name (== id) tracks the rename; a custom one stays
                    }
                    renamed = true;
                }
            }
        }
        if (!renamed) {
            throw new IllegalArgumentException("no type definition with id: " + oldId);
        }
        walk((path, node, isGroup) -> {
            JsonNode ref = node.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE).path("TypeDefType");
            if (!isGroup && oldId.equals(ref.path(DmKeys.TYPE_DEFINITION_ID).asText(null))) {
                ((ObjectNode) ref).put(DmKeys.TYPE_DEFINITION_ID, newId);
            }
        });
        return this;
    }

    /**
     * Extract the fields at {@code fieldPaths} — which must all share an identical CONCRETE type — into
     * one reusable type definition {@code newId}, repointing each field at it (task #4, a structural
     * refactor). Fails fast if a field is absent, already typed by a definition, or the fields do not
     * share a single concrete type; {@link #validate()} is the kernel backstop.
     */
    public ModelDocument extractTypeDefinition(String newId, List<String> fieldPaths) {
        JsonNode shared = sharedConcreteFieldType(fieldPaths);
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        ArrayNode array = tds.isArray() ? (ArrayNode) tds
                : ((ObjectNode) root.path(DmKeys.CONTENT)).putArray(DmKeys.TYPE_DEFINITIONS);
        ObjectNode td = array.addObject();
        td.put(DmKeys.ID, newId);
        td.put(DmKeys.NAME, newId);
        td.set(DmKeys.FIELD_TYPE, shared.deepCopy());
        for (String fieldPath : fieldPaths) {
            setFieldType(fieldPath, new FieldTypeSpec.TypeDefRef(newId));
        }
        return this;
    }

    /**
     * Whether the fields at {@code fieldPaths} all share one concrete type to factor out — present and
     * not already a {@code TypeDefType}, identical across the set. The CLI gate calls this before
     * mutating (see {@code SafetyChecks}); {@link #extractTypeDefinition} relies on the same answer.
     */
    public boolean fieldsShareConcreteType(List<String> fieldPaths) {
        try {
            sharedConcreteFieldType(fieldPaths);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonNode sharedConcreteFieldType(List<String> fieldPaths) {
        if (fieldPaths.isEmpty()) {
            throw new IllegalArgumentException("no fields to extract");
        }
        JsonNode shared = null;
        for (String fieldPath : fieldPaths) {
            JsonNode field = locate(fieldPath, false);
            if (field == null) {
                throw new IllegalArgumentException("no field at: " + fieldPath);
            }
            JsonNode fieldType = field.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE);
            if ("TypeDefType".equals(fieldType.path(DmKeys.TYPE).asText())) {
                throw new IllegalArgumentException(fieldPath + " is already typed by a definition");
            }
            if (shared == null) {
                shared = fieldType;
            } else if (!shared.equals(fieldType)) {
                throw new IllegalArgumentException("fields do not share an identical type: " + fieldPath);
            }
        }
        return shared;
    }

    /**
     * Inline the type definition {@code id} — replace every using field's {@code TypeDefType} with the
     * definition's underlying concrete type, then drop the definition (task #4, the inverse of extract).
     * Fails fast if {@code id} is absent.
     */
    public ModelDocument inlineTypeDefinition(String id) {
        JsonNode underlying = typeDefinitionFieldType(id);
        if (underlying == null) {
            throw new IllegalArgumentException("no type definition with id: " + id);
        }
        walk((path, node, isGroup) -> {
            JsonNode fieldType = node.path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE);
            if (!isGroup && id.equals(fieldType.path("TypeDefType").path(DmKeys.TYPE_DEFINITION_ID).asText(null))) {
                ((ObjectNode) node.path(ElementType.FIELD.wireName())).set(DmKeys.FIELD_TYPE, underlying.deepCopy());
            }
        });
        removeTypeDefinition(id);
        return this;
    }

    private JsonNode typeDefinitionFieldType(String id) {
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        if (tds.isArray()) {
            for (JsonNode td : tds) {
                if (id.equals(td.path(DmKeys.ID).asText())) {
                    return td.path(DmKeys.FIELD_TYPE);
                }
            }
        }
        return null;
    }

    /** Add a field at {@code groupPath} typed by the type definition {@code typeDefId}. */
    public ModelDocument addTypedefField(String groupPath, String name, String typeDefId) {
        ElementDraft.Field field = new ElementDraft.Field(
                name, new FieldTypeSpec.TypeDefRef(typeDefId), Requiredness.OPTIONAL);
        containerElements(groupPath).add(DocumentModelDraft.fieldNode(field, groupPath, locales()));
        return this;
    }

    /**
     * The include alias the group at {@code groupPath} mounts ({@code Group.modelAlias}), or empty if it is
     * not an include mount. A mount group's own {@code elements} would <em>shadow</em> the include (the kernel
     * stops expanding it, silently — KERNEL-FINDINGS), so callers use this to refuse editing into a mount.
     */
    public Optional<String> mountAliasAt(String groupPath) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            return Optional.empty();
        }
        String alias = group.path(ElementType.GROUP.wireName()).path(DmKeys.MODEL_ALIAS).asText(null);
        return alias == null || alias.isBlank() ? Optional.empty() : Optional.of(alias);
    }

    // --- includes (header.modelReferences + mount groups) ---------------------------------------

    /** Declare a model include: a header {@code modelReference} aliased {@code alias} → {@code reference}. */
    public ModelDocument addInclude(String alias, String reference) {
        modelReferences().addObject()
                .put(DmKeys.ALIAS, alias)
                .put(DmKeys.MODEL_TYPE, DmKeys.DOCUMENT)
                .put(DmKeys.PURPOSE, "include")
                .put(DmKeys.REFERENCE, reference);
        return this;
    }

    /**
     * Declare a <b>type-definition import</b>: a header {@code modelReference} with
     * {@code purpose: "typeDefinitions"} (the kernel constant {@code PURPOSE_4_ONLY_TYPE_DEFS}). The
     * referenced model's type-defs merge into this model's type-def namespace by id at expand, so a field
     * can be typed by an imported id with a plain {@code typeDefinitionId} (no alias prefix). The alias is a
     * label (defaults to {@code reference}); unlike an include, an import is <em>not</em> mounted.
     */
    public ModelDocument addImport(String reference) {
        modelReferences().addObject()
                .put(DmKeys.ALIAS, reference)
                .put(DmKeys.MODEL_TYPE, DmKeys.DOCUMENT)
                .put(DmKeys.PURPOSE, IMPORT_PURPOSE)
                .put(DmKeys.REFERENCE, reference);
        return this;
    }

    /**
     * Remove a type-definition import (the inverse of {@link #addImport} — the editor's "REMOVE IMPORT"):
     * drop the header {@code modelReference} with {@code purpose: "typeDefinitions"} and this {@code reference}.
     * Fails fast if no such import is declared. (A field still typed by an imported id will then dangle, so the
     * {@code validate} after this rejects it — like the safe-delete contract.)
     */
    public ModelDocument removeImport(String reference) {
        boolean present = false;
        for (JsonNode r : root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES)) {
            if (IMPORT_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText()) && reference.equals(r.path(DmKeys.REFERENCE).asText())) {
                present = true;
                break;
            }
        }
        if (!present) {
            throw new IllegalArgumentException("no type-definition import for '" + reference + "'");
        }
        JsonNode header = root.path(DmKeys.HEADER);
        ArrayNode kept = MAPPER.createArrayNode();
        for (JsonNode r : header.path(DmKeys.MODEL_REFERENCES)) {
            boolean isThisImport = IMPORT_PURPOSE.equals(r.path(DmKeys.PURPOSE).asText())
                    && reference.equals(r.path(DmKeys.REFERENCE).asText());
            if (!isThisImport) {
                kept.add(r);
            }
        }
        ((ObjectNode) header).set(DmKeys.MODEL_REFERENCES, kept);
        return this;
    }

    /** The header's {@code modelReferences} array, created if absent. */
    private ArrayNode modelReferences() {
        JsonNode refs = root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES);
        return refs.isArray() ? (ArrayNode) refs
                : ((ObjectNode) root.path(DmKeys.HEADER)).putArray(DmKeys.MODEL_REFERENCES);
    }

    /** Mount a declared include at {@code parentPath} as a group named {@code name} (a {@code modelAlias} group). */
    public ModelDocument mount(String parentPath, String name, String alias) {
        return mount(parentPath, name, alias, 1);
    }

    /** Mount a declared include as a <b>repeatable</b> sub-form ({@code repeatability} rows; FEATURE-GAPS). */
    public ModelDocument mount(String parentPath, String name, String alias, int repeatability) {
        return mount(parentPath, name, alias, MountOptions.repeatable(repeatability));
    }

    /**
     * Mount a declared include with explicit {@link MountOptions} — repeatability plus whether to exclude
     * the included model's rules / computations on expand (FEATURE-GAPS).
     */
    public ModelDocument mount(String parentPath, String name, String alias, MountOptions options) {
        ArrayNode container = parentPath.isEmpty() ? rootGroups() : containerElements(parentPath);
        container.add(DocumentModelDraft.mountNode(
                new ElementDraft.Mount(name, alias, options, List.of()), parentPath));
        return this;
    }

    /**
     * Extract the group at {@code groupPath} into a standalone sub-model — returned as DM-JSON — and
     * replace it in <em>this</em> base with a <b>mount</b> of that sub-model at the same name, so every
     * composed path is preserved (task #7, a Tier-3 structural refactor). The sub-model reuses the base's
     * envelope (locales, config) with header id {@code reference} and a single root group = the extracted
     * subtree rebased to {@code /<name>}; the base gains the {@code modelReference} + {@code modelAlias}
     * mount. Rules inside the subtree travel into the sub-model (they reference relatively). A rule inside
     * the subtree that references <em>outside</em> it, or a repeatable group, is the caller's gate to
     * refuse (the kernel re-validate of the composed pair is the backstop). Fails fast if the group is absent.
     */
    public String extractGroupAsInclude(String groupPath, String reference) {
        return extractGroupAsInclude(groupPath, reference, null);
    }

    /**
     * As {@link #extractGroupAsInclude(String, String)}, but {@code rootName} (when non-blank) names the
     * sub-model's single <b>root group</b> — independent of the base's <b>mount</b> name, which always keeps
     * the extracted group's name so every composed path is preserved. Use it when the sub-model is meant for
     * <b>reuse</b> under a different mount name (extract {@code /Person/HomeAddress} as a shared {@code Address}
     * mounted under both Home and Work); {@code null} keeps the extracted group's name (probe F05-3 spared the
     * follow-up {@code group rename}). The mount is by {@code reference}/alias, not by the root name, so the
     * two are free to differ.
     */
    public String extractGroupAsInclude(String groupPath, String reference, String rootName) {
        JsonNode group = locate(groupPath, true);
        if (group == null) {
            throw new IllegalArgumentException("no group at: " + groupPath);
        }
        String mountName = group.path(DmKeys.NAME).asText();   // the base mount keeps this — preserves composed paths
        String subRoot = (rootName != null && !rootName.isBlank()) ? rootName : mountName;

        // the sub-model: the base envelope, its own id, no inherited includes, one root = the rebased subtree.
        ObjectNode sub = root.deepCopy();
        ObjectNode subHeader = (ObjectNode) sub.path(DmKeys.HEADER);
        subHeader.put(DmKeys.ID, reference);
        subHeader.putArray(DmKeys.MODEL_REFERENCES);   // empty (not absent — the kernel expects a list), no inherited includes
        // the deep-copy carried the parent's modelInfo.name — rename it for the new sub-model (else an
        // extracted "address" sub-model stays named "person"; probe P4).
        JsonNode subInfo = sub.path(DmKeys.CONTENT).path(DmKeys.MODEL_INFO);
        if (subInfo.isObject()) {
            ((ObjectNode) subInfo).put(DmKeys.NAME, reference);
        }
        ObjectNode subGroup = group.deepCopy();
        subGroup.put(DmKeys.NAME, subRoot);   // the sub-model root's own name (= the group's, unless rootName overrides)
        reprefixIds(subGroup, groupPath, "/" + subRoot);
        ((ObjectNode) sub.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT)).putArray(DmKeys.ROOT_GROUPS).add(subGroup);
        String subModelJson;
        try {
            subModelJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sub);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("failed to serialize the extracted sub-model", e));
        }

        // the base: drop the group, declare the reference, mount it at the same name (path preserved).
        removeGroup(groupPath);
        addInclude(reference, reference);
        mount(groupPath.substring(0, groupPath.lastIndexOf('/')), mountName, reference);
        return subModelJson;
    }

    /**
     * Inline the include aliased {@code alias} — the inverse of {@link #extractGroupAsInclude}: fold the
     * included model's content (passed as {@code includedModelJson}) back into every mount as a real group
     * (rebasing the sub-model's root path to the mount path), then drop the header reference. The included
     * JSON is the caller's to supply (it lives in a separate file; this class is resolver-free). Fails fast
     * if no mount carries {@code alias}.
     */
    public ModelDocument inlineInclude(String alias, String includedModelJson) {
        ModelDocument included = read(includedModelJson);
        JsonNode includedRoot = included.root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS).path(0);
        if (includedRoot.isMissingNode()) {
            throw new IllegalArgumentException("included model has no root group for alias: " + alias);
        }
        String includedRootId = includedRoot.path(DmKeys.ID).asText();
        JsonNode includedElements = includedRoot.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS);
        boolean[] folded = {false};
        inlineMounts(rootGroups(), alias, includedRootId, includedElements, folded);
        if (!folded[0]) {
            throw new IllegalArgumentException("no mount with alias: " + alias);
        }
        removeInclude(alias);   // mounts no longer carry modelAlias, so this only drops the header reference
        return this;
    }

    private void inlineMounts(JsonNode elements, String alias, String includedRootId, JsonNode includedElements,
            boolean[] folded) {
        for (JsonNode element : elements) {
            if (!DmNodes.is(ElementType.GROUP, element)) {
                continue;
            }
            ObjectNode body = (ObjectNode) element.path(ElementType.GROUP.wireName());
            if (alias.equals(body.path(DmKeys.MODEL_ALIAS).asText(null))) {
                String mountPath = element.path(DmKeys.ID).asText();
                body.remove(DmKeys.MODEL_ALIAS);
                ArrayNode destination = body.putArray(DmKeys.ELEMENTS);
                for (JsonNode child : includedElements) {
                    JsonNode copy = child.deepCopy();
                    reprefixIds(copy, includedRootId, mountPath);
                    destination.add(copy);
                }
                folded[0] = true;
            } else if (body.path(DmKeys.ELEMENTS).isArray()) {
                inlineMounts(body.path(DmKeys.ELEMENTS), alias, includedRootId, includedElements, folded);
            }
        }
    }

    /** Every mount-group path that mounts the include aliased {@code alias} ({@code modelAlias}), in document order (task #2). */
    public List<String> mountsOfInclude(String alias) {
        List<String> out = new ArrayList<>();
        walk((path, node, isGroup) -> {
            if (isGroup && alias.equals(node.path(ElementType.GROUP.wireName()).path(DmKeys.MODEL_ALIAS).asText(null))) {
                out.add(path);
            }
        });
        return out;
    }

    /** Remove the type definition {@code id}. Fails fast if absent; {@link #validate()} catches a field still using it. */
    public ModelDocument removeTypeDefinition(String id) {
        JsonNode tds = root.path(DmKeys.CONTENT).path(DmKeys.TYPE_DEFINITIONS);
        if (tds.isArray()) {
            ArrayNode array = (ArrayNode) tds;
            for (int i = 0; i < array.size(); i++) {
                if (id.equals(array.get(i).path(DmKeys.ID).asText())) {
                    array.remove(i);
                    return this;
                }
            }
        }
        throw new IllegalArgumentException("no type definition with id: " + id);
    }

    /**
     * Remove the include aliased {@code alias} — its header {@code modelReference} <em>and</em> every mount
     * group ({@code modelAlias == alias}). Fails fast if neither is present; {@link #validate()} catches a
     * rule still referencing the mounted content.
     */
    public ModelDocument removeInclude(String alias) {
        boolean removed = false;
        JsonNode refs = root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES);
        if (refs.isArray()) {
            ArrayNode array = (ArrayNode) refs;
            for (int i = array.size() - 1; i >= 0; i--) {
                if (alias.equals(array.get(i).path(DmKeys.ALIAS).asText())) {
                    array.remove(i);
                    removed = true;
                }
            }
        }
        boolean[] mountRemoved = {false};
        removeMounts(rootGroups(), alias, mountRemoved);
        if (!removed && !mountRemoved[0]) {
            throw new IllegalArgumentException("no include with alias: " + alias);
        }
        return this;
    }

    /** Re-point the include {@code alias} at a different referenced model — the mounts, keyed by alias, are untouched. */
    public ModelDocument setIncludeReference(String alias, String newReference) {
        JsonNode refs = root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES);
        if (refs.isArray()) {
            for (JsonNode ref : refs) {
                if (alias.equals(ref.path(DmKeys.ALIAS).asText())) {
                    ((ObjectNode) ref).put(DmKeys.REFERENCE, newReference);
                    return this;
                }
            }
        }
        throw new IllegalArgumentException("no include with alias: " + alias);
    }

    /**
     * Rename an include's alias — its header {@code modelReference} <em>and</em> every mount's {@code
     * modelAlias}, in step, so nothing dangles. Mount group names/ids are unchanged, so rules over mounted
     * content (keyed by path, not alias) are unaffected. Fails fast if the alias is present nowhere.
     */
    public ModelDocument renameInclude(String oldAlias, String newAlias) {
        boolean renamed = false;
        JsonNode refs = root.path(DmKeys.HEADER).path(DmKeys.MODEL_REFERENCES);
        if (refs.isArray()) {
            for (JsonNode ref : refs) {
                if (oldAlias.equals(ref.path(DmKeys.ALIAS).asText())) {
                    ((ObjectNode) ref).put(DmKeys.ALIAS, newAlias);
                    renamed = true;
                }
            }
        }
        boolean[] mountRenamed = {false};
        renameMounts(rootGroups(), oldAlias, newAlias, mountRenamed);
        if (!renamed && !mountRenamed[0]) {
            throw new IllegalArgumentException("no include with alias: " + oldAlias);
        }
        return this;
    }

    private void renameMounts(ArrayNode elements, String oldAlias, String newAlias, boolean[] any) {
        for (JsonNode el : elements) {
            if (!DmNodes.is(ElementType.GROUP, el)) {
                continue;
            }
            ObjectNode body = (ObjectNode) el.path(ElementType.GROUP.wireName());
            if (oldAlias.equals(body.path(DmKeys.MODEL_ALIAS).asText())) {
                body.put(DmKeys.MODEL_ALIAS, newAlias);
                any[0] = true;
            } else if (body.path(DmKeys.ELEMENTS).isArray()) {
                renameMounts((ArrayNode) body.path(DmKeys.ELEMENTS), oldAlias, newAlias, any);
            }
        }
    }

    private void removeMounts(ArrayNode elements, String alias, boolean[] any) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            JsonNode el = elements.get(i);
            if (!DmNodes.is(ElementType.GROUP, el)) {
                continue;
            }
            JsonNode body = el.path(ElementType.GROUP.wireName());
            if (alias.equals(body.path(DmKeys.MODEL_ALIAS).asText())) {
                elements.remove(i);
                any[0] = true;
            } else if (body.path(DmKeys.ELEMENTS).isArray()) {
                removeMounts((ArrayNode) body.path(DmKeys.ELEMENTS), alias, any);
            }
        }
    }

    /**
     * The kernel gate (tier 2): re-bind the edited model through {@code checkConsistency} (self-contained).
     * Throws {@link ModelConsistencyException} if any edit made the model invalid — the full structural
     * constraint set, enforced by the engine rather than reimplemented here. Returns this on success.
     */
    public ModelDocument validate() {
        return validate(ReferenceResolvers.selfContained());
    }

    /** As {@link #validate()}, resolving includes / type-def imports via a <b>kernel-free</b>
     *  {@link ModelResolver} ({@code id → DM-JSON}). */
    public ModelDocument validate(ModelResolver resolver) {
        // OUTSIDE the try on purpose: serializing our own edited tree is not a gate, so a failure here is a
        // tool defect and must not be relabelled as "the edited model is not kernel-valid" (see the catch below).
        String json = toJson();
        Verification gate;
        try {
            gate = ModelLoader.loadAndCheck(json, resolver);
        } catch (ModelConsistencyException e) {
            throw e; // checkConsistency tier — already the uniform "model invalid" type
        } catch (UnresolvedReferenceException e) {
            // OUR resolver refused, not the gate. It has its own documented contract at the CLI seam
            // (RK_UNRESOLVED_REFERENCE, exit 1), so wrapping it published a kernel arbitration that never
            // happened — verification: KERNEL_CONFIRMED over a refusal we made ourselves.
            throw e;
        } catch (RuntimeException e) {
            // deserialize/expand tier (e.g. a duplicate element id surfaces as IllegalArgumentException):
            // funnel into the same type so a caller sees one "invalid edit" signal regardless of tier — and
            // SURFACE the underlying cause's message via the shared factory (probe 04: a bare "not
            // kernel-valid" with no code/where left an agent nothing to act on). Same factory as the session
            // terminal gate (EditSession.validate) so the cause-append can't drift between the two gates.
            throw ModelConsistencyException.notKernelValid(e);
        }
        gatedJson = json;
        gatedBy = gate;
        return this;
    }

    /**
     * The gate's <b>receipt</b> for the tree this document holds <em>right now</em> — empty until
     * {@link #validate()} has run, and empty again after any edit that changes the model.
     *
     * <p>State-checked rather than stored, because a document is mutable and every other witness in the codebase
     * ({@code BoundModel}, {@code KernelAdapter}, {@code ModelReport}) is not. A plain {@code Verification} field
     * would keep reading {@code KERNEL_CONFIRMED} after a post-gate edit, so a caller publishing it would state
     * that the kernel confirmed a model the kernel never saw — the ambient-mode defect displaced by one edit
     * rather than removed. Keeping the receipt against the exact DM-JSON that was gated makes the stale claim
     * unrepresentable instead of merely discouraged: there is no ordering rule for a caller to remember.
     *
     * <p>The comparison costs one serialization per read. That is affordable because a consumer reads the receipt
     * once, at the point it publishes provenance — not per edit.
     */
    public Optional<Verification> consistencyGate() {
        return gatedJson != null && gatedJson.equals(toJson()) ? Optional.of(gatedBy) : Optional.empty();
    }

    private ArrayNode rootGroups() {
        JsonNode rg = root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS);
        if (!rg.isArray()) {
            throw new IllegalStateException("model has no content.modelRoot.rootGroups array");
        }
        return (ArrayNode) rg;
    }

    private ArrayNode containerElements(String groupPath) {
        ArrayNode elements = DmNodes.groupElements(root, groupPath);
        if (elements == null) {
            throw new IllegalArgumentException("no group at path: " + groupPath);
        }
        // never write a local child into an include mount: the kernel would use the local elements and stop
        // expanding the include, silently discarding the mounted content (KERNEL-FINDINGS). The loud backstop
        // under every add/move; the CLI runs SafetyChecks.notAnIncludeMount first for a structured refusal.
        mountAliasAt(groupPath).ifPresent(alias -> {
            throw new IllegalArgumentException("group '" + groupPath + "' mounts the include '" + alias
                    + "' — adding a child would shadow the mounted content; edit the included model, or use a non-mount group");
        });
        return elements;
    }

    private void removeElement(String path, String type) {
        int cut = path.lastIndexOf('/');
        String parent = cut <= 0 ? "" : path.substring(0, cut);
        ArrayNode container = parent.isEmpty() ? rootGroups() : containerElements(parent);
        for (int i = 0; i < container.size(); i++) {
            JsonNode n = container.get(i);
            if (type.equals(n.path(DmKeys.TYPE).asText()) && path.equals(n.path(DmKeys.ID).asText())) {
                container.remove(i);
                return;
            }
        }
        throw new IllegalArgumentException("no " + type + " at path: " + path);
    }

    // --- tree walk ------------------------------------------------------------------------------

    private void walk(ElementVisitor visitor) {
        for (JsonNode rg : root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS)) {
            walk(rg, "", visitor);
        }
    }

    private void walk(JsonNode node, String prefix, ElementVisitor visitor) {
        String path = prefix + "/" + node.path(DmKeys.NAME).asText();
        if (DmNodes.is(ElementType.GROUP, node)) {
            visitor.visit(path, node, true);
            for (JsonNode child : node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
                walk(child, path, visitor);
            }
        } else if (DmNodes.is(ElementType.FIELD, node)) {
            visitor.visit(path, node, false);
        }
        // Other element kinds (Rule/Computation/…) are not enumerated here — they are rulekit's, and
        // structure editing (M8) addresses them by their own type; the tree still round-trips them.
    }

    private JsonNode locate(String namePath, boolean group) {
        JsonNode[] found = new JsonNode[1];
        walk((path, node, isGroup) -> {
            if (found[0] == null && isGroup == group && path.equals(namePath)) {
                found[0] = node;
            }
        });
        return found[0];
    }

    @FunctionalInterface
    private interface ElementVisitor {
        void visit(String namePath, JsonNode node, boolean isGroup);
    }

    /** A declared model include (an entry of the header's {@code modelReferences}). */
    public record Include(String alias, String reference) {}

    /**
     * The model-level document configuration (content {@code modelConfig}). {@code supportedCharacters}
     * retains singleton/range/atomic-grapheme entries verbatim; an empty list denotes the full-BMP default.
     */
    public record DocConfig(String decimalSeparator, String timeZone, String conditionLanguage,
            boolean fieldRefByShortNameAllowed, List<String> supportedCharacters) {}
}
