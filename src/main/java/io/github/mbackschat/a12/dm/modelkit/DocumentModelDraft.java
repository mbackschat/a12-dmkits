package io.github.mbackschat.a12.dm.modelkit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * modelkit M1 — a typed builder for document-model <b>structure</b>, the peer of rulekit's rule
 * builder (fork #8 / ARCHITECTURE §5.2; rationale in {@code docs/PROJECT-DESIGN.md} §21). Author a
 * model from scratch — {@code model(id, locale) → group(name) → field(name, kind)} — and emit
 * DM-JSON the real kernel accepts ({@code checkConsistency} + the {@code BasicConsistencyCheck} write
 * gate, proven by {@code DocumentModelDraftTest}).
 *
 * <p><b>Pure emit, by design.</b> This package depends only on Jackson — never on the kernel or on
 * rulekit's {@code dsl} (the umbrella's {@code structure ⊥ dsl} rule, D10). Validation is the
 * caller's job (load the JSON through {@code model.ModelLoader}); keeping structure kernel-free is
 * what keeps the eventual {@code :modelkit} extraction mechanical and the TypeScript twin possible.
 *
 * <p>The fluent builders are the only constructor, so a model without a locale or a root group, or a
 * group with a non-positive repeatability, can't be built.
 */
public final class DocumentModelDraft {

    /** Document-model schema version stamped on new-from-scratch models — the shared {@code dm.core}
     *  {@link io.github.mbackschat.a12.dm.core.ModelFormat#VERSION} (the load-time version check and the
     *  {@code --version} surface compare a loaded model's {@code header.modelVersion} against the same value). */
    private static final String MODEL_VERSION = io.github.mbackschat.a12.dm.core.ModelFormat.VERSION;

    /**
     * The default condition KEYWORD language for a from-scratch model — English, regardless of the display
     * locale. Condition language is independent of display locale (which governs labels + messages): the
     * kernel's own models (a12-kernel-mm) declare a de_DE display locale yet author English-keyword rules.
     * Single-sourced so the model-config default and the attachment-template authoring can't drift (a de_DE
     * model authoring German attachment conditions against an en_US config is exactly what this prevents).
     * Change the rare German-keyword case explicitly via {@code config().conditionLanguage("de_DE")}.
     */
    static final String DEFAULT_CONDITION_LANGUAGE = "en_US";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final List<String> locales;
    private final List<Include> includes;
    private final List<TypeDefDecl> typeDefs;
    private final List<ElementDraft.Group> rootGroups;
    private final Integer baseYear;
    private final Map<String, String> labels;
    private final List<ElementDraft.Annotation> annotations;
    private final List<String> supportedCharacters;
    private final List<String> typeDefImports;
    private final String comment;

    /**
     * Snapshot the {@link Builder}'s state into an immutable draft. The builder passes itself (rather than a
     * dozen positional args), and the defensive {@code copyOf} lives here, so the draft is immutable however
     * {@code build()} calls it. The only call site is {@link Builder#build()}.
     */
    private DocumentModelDraft(Builder b) {
        this.id = b.id;
        this.locales = List.copyOf(b.locales);
        this.includes = List.copyOf(b.includes);
        this.typeDefs = List.copyOf(b.typeDefs);
        this.rootGroups = List.copyOf(b.rootGroups);
        this.baseYear = b.baseYear;
        this.labels = Map.copyOf(b.labels);
        this.annotations = List.copyOf(b.annotations);
        this.supportedCharacters = List.copyOf(b.supportedCharacters);
        this.typeDefImports = List.copyOf(b.typeDefImports);
        this.comment = b.comment;
    }

    /** A reusable named type, declared once in {@code content.typeDefinitions} and referenced by fields. */
    private record TypeDefDecl(String id, FieldTypeSpec type, List<ElementDraft.Annotation> annotations,
            Map<String, String> internalDescription, Map<String, String> externalDescription) {}

    /** A header include: {@code alias} (used by mount groups) → {@code reference} (the included model id). */
    private record Include(String alias, String reference) {}

    /** Start a draft for the model with this id (also its info name). */
    public static Builder named(String id) {
        return new Builder(id);
    }

    /**
     * Start a <b>type-def-only</b> model — one that carries only reusable {@code typeDefinitions} and no
     * structure, meant to be <em>imported</em> by other models ({@link Builder#importTypeDefs(String)} /
     * {@code typedef import}). It is a normal {@code modelType: "document"} model with an empty root (the
     * kernel accepts it standalone); declaring a {@link Builder#rootGroup} on it is an error. Add types with
     * {@link Builder#typeDef}.
     */
    public static Builder typeDefModel(String id) {
        Builder b = new Builder(id);
        b.typeDefOnly = true;
        return b;
    }

    /** The model id. */
    public String id() {
        return id;
    }

    /** Emit the model as pretty-printed DM-JSON — the public, polyglot write surface (D9). */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();

        ObjectNode header = root.putObject("header");
        header.put("id", id);
        header.put("modelType", "document");
        header.put("modelVersion", MODEL_VERSION);
        ArrayNode locs = header.putArray("locales");
        locales.forEach(code -> locs.addObject().put("code", code));
        ArrayNode labelArr = header.putArray("labels");
        labels.forEach((loc, text) -> labelArr.addObject().put("locale", loc).put("text", text));
        ArrayNode annoArr = header.putArray("annotations");
        annotations.forEach(a -> annoArr.addObject().put("name", a.name()).put("value", a.value()));
        checkMountReferences();
        ArrayNode refs = header.putArray("modelReferences");
        for (Include inc : includes) {
            refs.addObject()
                    .put("alias", inc.alias())
                    .put("modelType", "document")
                    .put("purpose", "include")
                    .put("reference", inc.reference());
        }
        for (String importRef : typeDefImports) {
            // a type-def import: purpose "typeDefinitions" (kernel PURPOSE_4_ONLY_TYPE_DEFS) — merged by id, not mounted
            refs.addObject()
                    .put("alias", importRef)
                    .put("modelType", "document")
                    .put("purpose", "typeDefinitions")
                    .put("reference", importRef);
        }

        ObjectNode content = root.putObject("content");
        ObjectNode info = content.putObject("modelInfo");
        info.put("name", id);
        info.put("variant", "cockpit");
        info.put("revision", "Arbeitsversion");
        if (baseYear != null) {
            // the model-level base year (handles year-less date inputs and enables the BaseYear point-in-time)
            info.put("baseYear", String.valueOf(baseYear));
        }
        if (comment != null) {
            info.put("comment", comment);   // a free-text model-level note (IDocumentModelInfo.getComment)
        }
        info.put("immutable", false);
        ObjectNode config = content.putObject("modelConfig");
        config.put("decimalSeparator", ".");
        config.put("timeZone", "Europe/Berlin");
        // Condition KEYWORD language is independent of the DISPLAY locale (see DEFAULT_CONDITION_LANGUAGE).
        config.putObject("conditionLanguage").put("code", DEFAULT_CONDITION_LANGUAGE);
        config.put("fieldRefByShortNameAllowed", true);
        if (!supportedCharacters.isEmpty()) {
            // the allowed-character set: each entry a char or a range like " -~"; empty = all of Unicode-Plane-0
            ArrayNode chars = config.putArray("supportedCharacters");
            supportedCharacters.forEach(chars::add);
        }

        checkTypeDefReferences();
        if (!typeDefs.isEmpty()) {
            ArrayNode defs = content.putArray("typeDefinitions");
            for (TypeDefDecl def : typeDefs) {
                ObjectNode node = defs.addObject();
                node.put("id", def.id());
                node.put("name", def.id());
                writeAnnotations(node, def.annotations());
                writeLocalizedText(node, "internalDescription", def.internalDescription());
                writeLocalizedText(node, "externalDescription", def.externalDescription());
                writeFieldType(node.putObject("fieldType"), def.type(), locales);
            }
        }

        ArrayNode roots = content.putObject("modelRoot").putArray("rootGroups");
        rootGroups.forEach(group -> roots.add(groupNode(group, "", locales)));

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to emit DM-JSON for model " + id, e);
        }
    }

    // package-private: reused by ModelDocument (M8 structure edits) so the node shape lives once
    static ObjectNode groupNode(ElementDraft.Group group, String parentPath, List<String> locales) {
        String path = parentPath + "/" + group.name();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Group");
        node.put("id", path);
        node.put("name", group.name());
        writeAnnotations(node, group.annotations());
        // internal/external description are element-level (alongside annotations), uniform with fields (IDescribed)
        writeLocalizedText(node, "internalDescription", group.internalDescription());
        writeLocalizedText(node, "externalDescription", group.externalDescription());
        ObjectNode body = node.putObject("Group");
        body.put("repeatability", group.repeatability());
        if (group.usageType() != null) {
            // the kernel group-template marker (attachment / multi-select), FEATURE-GAPS §3.8
            body.put("usageType", group.usageType());
        }
        if (group.indexFieldName() != null) {
            body.put("indexFieldName", group.indexFieldName());
        }
        ArrayNode elements = body.putArray("elements");
        for (ElementDraft element : group.elements()) {
            elements.add(switch (element) {
                case ElementDraft.Group sub -> groupNode(sub, path, locales);
                case ElementDraft.Field field -> fieldNode(field, path, locales);
                case ElementDraft.Mount mount -> mountNode(mount, path);
                case ElementDraft.Rule rule -> ruleNode(rule, path);
            });
        }
        return node;
    }

    /** A raw rule node ({@code {type:"Rule", Rule:{errorCondition, errorCode, severity, errorMessage, …}}}). */
    // package-private: reused by ModelDocument when emitting a template group's mandated rules (§3.8)
    static ObjectNode ruleNode(ElementDraft.Rule rule, String parentPath) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Rule");
        node.put("id", parentPath + "/" + rule.name());
        node.put("name", rule.name());
        ObjectNode body = node.putObject("Rule");
        body.put("errorEntityRelPath", rule.errorEntityRelPath());
        body.put("errorCode", rule.errorCode());
        body.put("errorCondition", rule.errorCondition());
        body.put("severity", rule.severity());
        ArrayNode messages = body.putArray("errorMessage");
        rule.messages().forEach((loc, text) -> messages.addObject().put("locale", loc).put("text", text));
        return node;
    }

    /** An include mount: a group carrying {@code modelAlias} and <b>no</b> inline elements (§10). */
    // package-private: reused by ModelDocument (M10+ include editing)
    static ObjectNode mountNode(ElementDraft.Mount mount, String parentPath) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Group");
        node.put("id", parentPath + "/" + mount.name());
        node.put("name", mount.name());
        writeAnnotations(node, mount.annotations());
        ObjectNode body = node.putObject("Group");
        MountOptions options = mount.options();
        body.put("repeatability", options.repeatability());
        body.put("modelAlias", mount.alias());
        // includeLevel sits on the mount group beside modelAlias; omit SINGLE_RG (the kernel default-absent, A12K-3234)
        if (options.includeLevel() == IncludeLevel.MODEL_ROOT) {
            body.put("includeLevel", "MODEL_ROOT");
        }
        // rule/computation exclusions sit on the mount group beside modelAlias (metamodel MM_Group, §3.9)
        if (options.excludeRules()) {
            body.put("excludeRules", true);
        }
        if (options.excludeComputations()) {
            body.put("excludeComputations", true);
        }
        return node;
    }

    // package-private: reused by ModelDocument (M8 structure edits) so the node shape lives once
    static ObjectNode fieldNode(ElementDraft.Field field, String parentPath, List<String> locales) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Field");
        node.put("id", parentPath + "/" + field.name());
        node.put("name", field.name());
        writeAnnotations(node, field.annotations());
        // internal/external description are element-level (alongside annotations), per the kernel serializer
        writeLocalizedText(node, "internalDescription", field.internalDescription());
        writeLocalizedText(node, "externalDescription", field.externalDescription());
        ObjectNode body = node.putObject("Field");
        writeFieldType(body.putObject("fieldType"), field.type(), locales);
        String mode = field.requiredness().mode();
        if (mode != null) {
            body.putObject("requirednessConfig").put("mode", mode);
        }
        if (!field.labels().isEmpty()) {
            // a label, once present, must cover every declared locale (same rule as enum labels).
            // This is a SPEC error (the caller supplied incomplete labels), not an internal invariant —
            // IllegalArgumentException, so every CLI field-write path (add/modify/apply/batch) reports it as
            // a clean "invalid field spec" rejection instead of letting it escape as RK_INTERNAL_ERROR (02-crash).
            for (String loc : locales) {
                if (!field.labels().containsKey(loc)) {
                    throw new IllegalArgumentException("field '" + field.name()
                            + "' label is missing declared locale '" + loc + "'");
                }
            }
            ArrayNode label = body.putArray("label");
            locales.forEach(loc -> label.addObject().put("locale", loc).put("text", field.labels().get(loc)));
        }
        // global / transient flags + helperText live inside the Field body (kernel FieldSerializer)
        if (field.global()) {
            body.put("global", true);
        }
        if (field.notPersisted()) {
            body.put("transient", true);
        }
        writeLocalizedText(body, "helperText", field.helperText());
        return node;
    }

    /** Emit an optional per-locale text slot as {@code key: [{locale, text}]} (omitted when empty). */
    private static void writeLocalizedText(ObjectNode target, String key, Map<String, String> textByLocale) {
        if (textByLocale.isEmpty()) {
            return;
        }
        ArrayNode array = target.putArray(key);
        textByLocale.forEach((loc, text) -> array.addObject().put("locale", loc).put("text", text));
    }

    /** Emit element-level {@code annotations: [{name, value}]} (omitted when none). */
    private static void writeAnnotations(ObjectNode elementNode, List<ElementDraft.Annotation> annotations) {
        if (annotations.isEmpty()) {
            return;
        }
        ArrayNode array = elementNode.putArray("annotations");
        for (ElementDraft.Annotation a : annotations) {
            array.addObject().put("name", a.name()).put("value", a.value());
        }
    }

    /** Write the {@code type} discriminator plus its inner config block. */
    // package-private: reused by ModelDocument (M10+ type-definition editing)
    static void writeFieldType(ObjectNode fieldType, FieldTypeSpec spec, List<String> locales) {
        fieldType.put("type", spec.jsonType());
        writeTypeConfig(fieldType, spec, locales);
    }

    /** Write the kernel {@code fieldType.<Type>} inner block for the configured slots (omitted when empty). */
    private static void writeTypeConfig(ObjectNode fieldType, FieldTypeSpec spec, List<String> locales) {
        switch (spec) {
            case FieldTypeSpec.StringSpec s -> {
                if (s.minLength() != null || s.maxLength() != null || s.pattern() != null
                        || s.lineBreaksPermitted() || s.alphabeticalSorting() || !s.suggestions().isEmpty()
                        || s.noValueValidation()) {
                    ObjectNode inner = fieldType.putObject("StringType");
                    // a pattern needs an error text for every declared locale (MVK_ERROR_MESSAGE_AND_PATTERN_INVALID).
                    // A per-locale override (patternMessages) wins for its locale; the single patternMessage is the
                    // fallback for the rest — so a bilingual model can carry a localized pattern error (G23).
                    if (s.pattern() != null) {
                        ArrayNode messages = inner.putArray("errorMessage");
                        locales.forEach(loc -> messages.addObject().put("locale", loc)
                                .put("text", s.patternMessages().getOrDefault(loc, s.patternMessage())));
                    }
                    if (s.minLength() != null) {
                        inner.put("minLength", s.minLength());
                    }
                    if (s.maxLength() != null) {
                        inner.put("maxLength", s.maxLength());
                    }
                    if (s.pattern() != null) {
                        inner.put("pattern", s.pattern());
                    }
                    if (s.lineBreaksPermitted()) {
                        inner.put("lineBreaksPermitted", true);
                    }
                    if (s.noValueValidation()) {
                        // skip value validation (base64 blobs etc.); the kernel requires line breaks alongside it
                        inner.put("noValueValidation", true);
                    }
                    if (s.alphabeticalSorting()) {
                        inner.put("alphabeticalSorting", true);
                    }
                    if (!s.suggestions().isEmpty()) {
                        ArrayNode hintList = inner.putArray("hintList");
                        s.suggestions().forEach((loc, vals) -> {
                            ObjectNode entry = hintList.addObject();
                            entry.put("locale", loc);
                            ArrayNode values = entry.putArray("values");
                            vals.forEach(values::add);
                        });
                    }
                }
            }
            case FieldTypeSpec.NumberSpec n -> {
                ObjectNode inner = fieldType.putObject("NumberType");
                inner.put("maxFractionalDigits", n.maxFractionalDigits() != null ? n.maxFractionalDigits() : 0);
                if (n.minFractionalDigits() != null) {
                    inner.put("minFractionalDigits", n.minFractionalDigits());
                }
                if (n.minValue() != null) {
                    inner.put("minValue", n.minValue());
                }
                if (n.maxValue() != null) {
                    inner.put("maxValue", n.maxValue());
                }
                if (n.zeroNotAllowed()) {
                    inner.put("zeroNotAllowed", true);
                }
                if (n.leadingZerosAllowed()) {
                    inner.put("leadingZerosAllowed", true);
                }
                if (n.trait() != null) {
                    inner.put("trait", n.trait().json());
                }
            }
            case FieldTypeSpec.DateSpec d -> {
                ObjectNode inner = fieldType.putObject("DateType");
                inner.put("format", d.format() != null ? d.format() : "yyyy-MM-dd");
                // datePrecision is omitted for FULL (the kernel default), matching its serializer
                if (d.datePrecision() != null && !d.datePrecision().equals("FULL")) {
                    inner.put("datePrecision", d.datePrecision());
                }
            }
            case FieldTypeSpec.TimeSpec t ->
                    fieldType.putObject("TimeType").put("format", t.format() != null ? t.format() : "HH:mm:ss");
            case FieldTypeSpec.DateTimeSpec dt ->
                    fieldType.putObject("DateTimeType")
                            .put("format", dt.format() != null ? dt.format() : "yyyy-MM-dd'T'HH:mm:ss");
            case FieldTypeSpec.DateRangeSpec dr -> {
                ObjectNode inner = fieldType.putObject("DateRangeType");
                inner.put("format", dr.format());
                inner.put("rangeSeparator", dr.separator());
            }
            case FieldTypeSpec.DateFragmentSpec df ->
                    fieldType.putObject("DateFragmentType").put("formatOfFragment", df.fragmentFormat());
            case FieldTypeSpec.TypeDefRef ref ->
                    fieldType.putObject("TypeDefType").put("typeDefinitionId", ref.typeDefinitionId());
            case FieldTypeSpec.BooleanSpec b -> { /* bare */ }
            case FieldTypeSpec.ConfirmSpec c -> { /* bare */ }
            case FieldTypeSpec.EnumSpec e -> {
                boolean labelled = e.values().stream().anyMatch(v -> !v.labels().isEmpty());
                if (labelled) {
                    // all-or-nothing: once any value is labelled, every value needs a label for every
                    // declared locale, else MVK_COUNT_OF_VALUES_INVALID. Fail fast with a clear message.
                    for (FieldTypeSpec.EnumValue v : e.values()) {
                        for (String loc : locales) {
                            if (!v.labels().containsKey(loc)) {
                                // a SPEC error (incomplete enum labels), not an internal invariant — see the
                                // field-label note above (02-crash): IllegalArgumentException so the CLI
                                // field-write paths report a clean "invalid field spec", never RK_INTERNAL_ERROR.
                                throw new IllegalArgumentException("enum value '" + v.value()
                                        + "' is missing a label for declared locale '" + loc
                                        + "' (labels are all-or-nothing per locale)");
                            }
                        }
                    }
                }
                ObjectNode enumeration = fieldType.putObject("EnumerationType");
                ArrayNode values = enumeration.putArray("values");
                for (FieldTypeSpec.EnumValue v : e.values()) {
                    ObjectNode valueNode = values.addObject();
                    valueNode.put("value", v.value());
                    if (!v.labels().isEmpty()) {
                        ArrayNode labels = valueNode.putArray("label");
                        locales.forEach(loc -> labels.addObject().put("locale", loc).put("text", v.labels().get(loc)));
                    }
                }
                if (!e.categories().isEmpty()) {
                    ArrayNode categories = enumeration.putArray("categories");
                    for (FieldTypeSpec.EnumCategory c : e.categories()) {
                        ObjectNode categoryNode = categories.addObject();
                        categoryNode.put("name", c.name());
                        if (c.description() != null) {
                            categoryNode.put("description", c.description());   // editor note; kernel tolerates it
                        }
                        ArrayNode catValues = categoryNode.putArray("values");
                        c.values().forEach(catValues::add);
                    }
                }
                if (e.alphabeticalSorting()) {
                    enumeration.put("alphabeticalSorting", true);
                }
                if (!e.invalidValueMessages().isEmpty()) {
                    // G23: per-locale invalid-value text — written verbatim (the kernel requires every declared
                    // locale to be covered, like the enum value labels)
                    ArrayNode messages = enumeration.putArray("errorMessage");
                    e.invalidValueMessages().forEach((loc, text) -> messages.addObject().put("locale", loc).put("text", text));
                } else if (e.invalidValueMessage() != null) {
                    // the single-text shorthand, expanded across every declared locale (the kernel's localized map)
                    ArrayNode messages = enumeration.putArray("errorMessage");
                    locales.forEach(loc -> messages.addObject().put("locale", loc).put("text", e.invalidValueMessage()));
                }
            }
            case FieldTypeSpec.CustomSpec cf -> {
                ObjectNode inner = fieldType.putObject("CustomFieldType");
                inner.put("name", cf.name());
                if (cf.minLength() != null) {
                    inner.put("minLength", cf.minLength());
                }
                if (cf.maxLength() != null) {
                    inner.put("maxLength", cf.maxLength());
                }
            }
        }
    }

    /**
     * The inverse of {@link #writeTypeConfig}: project a stored kernel {@code fieldType} node back into the
     * SAME per-kind block that {@code field add}/{@code field modify} accept (the {@code number}/{@code
     * string}/{@code enum}/{@code date}/{@code time}/{@code dateRange}/{@code dateFragment}/{@code custom}
     * blocks, or a {@code typedef} ref — see {@code schema field add}). So a config an agent SET round-trips
     * on read: it confirms a constraint persisted through {@code field read} alone, never the raw model JSON
     * (probe 05/07 number, 02 string). Returns a merge-ready object; empty for a bare BOOLEAN/CONFIRM.
     */
    static ObjectNode readTypeConfig(JsonNode fieldType) {
        ObjectNode out = MAPPER.createObjectNode();
        String type = fieldType.path("type").asText("");
        JsonNode c = fieldType.path(type);   // the config lives under the discriminator key
        switch (type) {
            case "StringType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "pattern", b, "pattern");
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                if (c.path("lineBreaksPermitted").asBoolean(false)) b.put("lineBreaks", true);
                if (c.path("alphabeticalSorting").asBoolean(false)) b.put("alphabeticalSorting", true);
                localizedMap(c.path("errorMessage")).ifPresent(m -> b.set("patternMessages", m));
                suggestionsMap(c.path("hintList")).ifPresent(m -> b.set("suggestions", m));
                if (!b.isEmpty()) out.set("string", b);
            }
            case "NumberType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                if (c.hasNonNull("minValue")) b.put("min", c.get("minValue").asText());   // string, as the write schema
                if (c.hasNonNull("maxValue")) b.put("max", c.get("maxValue").asText());
                if (c.hasNonNull("trait")) b.put("unit", c.get("trait").asText().toUpperCase(java.util.Locale.ROOT));
                copyInt(c, "minFractionalDigits", b, "minFractionalDigits");
                copyInt(c, "maxFractionalDigits", b, "maxFractionalDigits");
                if (c.path("zeroNotAllowed").asBoolean(false)) b.put("zeroNotAllowed", true);
                if (c.path("leadingZerosAllowed").asBoolean(false)) b.put("leadingZerosAllowed", true);
                if (!b.isEmpty()) out.set("number", b);
            }
            case "EnumerationType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                ArrayNode values = b.putArray("values");
                for (JsonNode v : c.path("values")) {
                    ObjectNode valueNode = values.addObject();
                    valueNode.put("value", v.path("value").asText());
                    localizedMap(v.path("label")).ifPresent(m -> valueNode.set("labels", m));
                }
                if (c.path("alphabeticalSorting").asBoolean(false)) b.put("alphabeticalSorting", true);
                out.set("enum", b);
            }
            case "DateType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                copyText(c, "datePrecision", b, "precision");
                if (!b.isEmpty()) out.set("date", b);
            }
            case "TimeType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                if (!b.isEmpty()) out.set("time", b);
            }
            case "DateTimeType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                if (!b.isEmpty()) out.set("dateTime", b);
            }
            case "DateRangeType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                copyText(c, "rangeSeparator", b, "separator");
                if (!b.isEmpty()) out.set("dateRange", b);
            }
            case "DateFragmentType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "formatOfFragment", b, "format");
                if (!b.isEmpty()) out.set("dateFragment", b);
            }
            case "CustomFieldType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "name", b, "typeName");
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                if (!b.isEmpty()) out.set("custom", b);
            }
            case "TypeDefType" -> out.put("typedef", c.path("typeDefinitionId").asText());
            default -> { /* a bare type (BOOLEAN/CONFIRM) carries no surfaceable config */ }
        }
        return out;
    }

    /** Copy a text slot under a (possibly renamed) key, only when present. */
    private static void copyText(JsonNode from, String fromKey, ObjectNode to, String toKey) {
        if (from.hasNonNull(fromKey)) {
            to.put(toKey, from.get(fromKey).asText());
        }
    }

    /** Copy an integer slot under a (possibly renamed) key, only when present. */
    private static void copyInt(JsonNode from, String fromKey, ObjectNode to, String toKey) {
        if (from.hasNonNull(fromKey)) {
            to.put(toKey, from.get(fromKey).asInt());
        }
    }

    /** A kernel {@code [{locale, text}]} array back into a {@code {locale: text}} map (empty → none). */
    private static Optional<ObjectNode> localizedMap(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode map = MAPPER.createObjectNode();
        for (JsonNode entry : array) {
            map.put(entry.path("locale").asText(), entry.path("text").asText());
        }
        return Optional.of(map);
    }

    /** A kernel {@code [{locale, values:[…]}]} hint list back into a {@code {locale: [values]}} map. */
    private static Optional<ObjectNode> suggestionsMap(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode map = MAPPER.createObjectNode();
        for (JsonNode entry : array) {
            ArrayNode values = map.putArray(entry.path("locale").asText());
            entry.path("values").forEach(v -> values.add(v.asText()));
        }
        return Optional.of(map);
    }

    /** Fluent builder for a {@link DocumentModelDraft}. */
    public static final class Builder {

        private final String id;
        private final List<String> locales = new ArrayList<>();
        private final List<Include> includes = new ArrayList<>();
        private final List<TypeDefDecl> typeDefs = new ArrayList<>();
        private final List<ElementDraft.Group> rootGroups = new ArrayList<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final List<ElementDraft.Annotation> annotations = new ArrayList<>();
        private final List<String> supportedCharacters = new ArrayList<>();
        private final List<String> typeDefImports = new ArrayList<>();
        private boolean typeDefOnly;
        private Integer baseYear;
        private String comment;

        private Builder(String id) {
            this.id = id;
        }

        /** Add a per-locale model label (the header's localized display name). */
        public Builder label(String locale, String text) {
            labels.put(locale, text);
            return this;
        }

        /** Add a model-level name/value annotation (header metadata). */
        public Builder annotation(String name, String value) {
            annotations.add(new ElementDraft.Annotation(name, value));
            return this;
        }

        /**
         * Set the model's base year. Required for year-less date inputs (date fragments without a year
         * part) and for the {@code BaseYear} point-in-time construct (else {@code MVK_NO_BASE_YEAR}).
         */
        public Builder baseYear(int year) {
            this.baseYear = year;
            return this;
        }

        /** Add a declared locale (e.g. {@code "en_US"}); a full {@code language_COUNTRY} code (KERNEL-FINDINGS §13). */
        public Builder locale(String code) {
            locales.add(code);
            return this;
        }

        /**
         * Restrict the {@code modelConfig} allowed-character set: each entry is a single character or a
         * range like {@code " -~"} (space through tilde). The kernel checks string/enum/custom field
         * values against this set. Omit (or pass nothing) to allow all of Unicode-Plane-0.
         */
        public Builder supportedCharacters(String... chars) {
            supportedCharacters.addAll(List.of(chars));
            return this;
        }

        /** Declare an include: {@code alias} (used by {@code mount(name, alias)}) → {@code reference} (the included model id). */
        public Builder include(String alias, String reference) {
            includes.add(new Include(alias, reference));
            return this;
        }

        /**
         * Import the type definitions of another model ({@code purpose: "typeDefinitions"}): its type-defs
         * merge into this model's namespace by id, so a {@code typedefField} may reference an imported id.
         * Unlike {@link #include}, an import is not mounted — it contributes no structure.
         */
        public Builder importTypeDefs(String reference) {
            typeDefImports.add(reference);
            return this;
        }

        /** Declare a reusable named type; reference it from fields with {@code typedefField(name, id)}. */
        public Builder typeDef(String id, Consumer<TypeDefBuilder> body) {
            TypeDefBuilder b = new TypeDefBuilder();
            body.accept(b);
            typeDefs.add(new TypeDefDecl(id, b.spec(), List.copyOf(b.annotations),
                    Map.copyOf(b.internalDescription), Map.copyOf(b.externalDescription)));
            return this;
        }

        /** Set the model-level {@code comment} (a free-text model note / description). */
        public Builder comment(String text) {
            this.comment = text;
            return this;
        }

        /** Add a top-level group, populated by {@code body}. */
        public Builder rootGroup(String name, Consumer<GroupBuilder> body) {
            if (typeDefOnly) {
                throw new IllegalStateException("a type-def-only model has no root groups; "
                        + "use DocumentModelDraft.named(...) for a structural model");
            }
            GroupBuilder group = new GroupBuilder(name);
            body.accept(group);
            rootGroups.add(group.toDraft());
            return this;
        }

        /** Add a top-level <b>Multi-Select</b> group template (FEATURE-GAPS §3.8); see {@link GroupBuilder#multiSelectGroup}. */
        public Builder multiSelectGroup(String name, Consumer<MultiSelectBuilder> body) {
            if (typeDefOnly) {
                throw new IllegalStateException("a type-def-only model has no root groups");
            }
            MultiSelectBuilder b = new MultiSelectBuilder();
            body.accept(b);
            rootGroups.add(b.toGroup(name));
            return this;
        }

        /**
         * Add a top-level <b>Attachment</b> group template (FEATURE-GAPS §3.8) — the kernel's canonical
         * file-attachment composition (8 fields + 4 rules + {@code content} config). Its rules are authored
         * in the model's condition language (the first declared locale), so a locale must be declared first
         * and the model must be {@code en_US}/{@code de_DE}. See {@link AttachmentBuilder}.
         */
        public Builder attachmentGroup(String name, Consumer<AttachmentBuilder> body) {
            if (typeDefOnly) {
                throw new IllegalStateException("a type-def-only model has no root groups");
            }
            if (locales.isEmpty()) {
                throw new IllegalStateException("declare a locale before adding an attachment group "
                        + "(its rules are authored in the model's condition language)");
            }
            AttachmentBuilder b = new AttachmentBuilder();
            body.accept(b);
            // author the attachment conditions in the model's CONDITION language (English by default), NOT the
            // display locale — they must match the conditionLanguage the config declares (DEFAULT_CONDITION_LANGUAGE)
            // or the kernel rejects the mandated rule strings as the wrong keyword language.
            rootGroups.add(b.toGroup(name, List.copyOf(locales), DEFAULT_CONDITION_LANGUAGE));
            return this;
        }

        /** Build the immutable draft, rejecting models that can't be valid (no locale / empty structure). */
        public DocumentModelDraft build() {
            if (locales.isEmpty()) {
                throw new IllegalStateException("a model needs at least one locale");
            }
            if (typeDefOnly) {
                if (typeDefs.isEmpty()) {
                    throw new IllegalStateException("a type-def-only model needs at least one type definition");
                }
            } else if (rootGroups.isEmpty()) {
                throw new IllegalStateException("a model needs at least one root group "
                        + "(or use DocumentModelDraft.typeDefModel(...) for a type-def-only model)");
            }
            return new DocumentModelDraft(this);
        }
    }

    /**
     * Picks the single underlying type of a reusable type definition (config reused from the field builders),
     * and — via {@link ElementMeta} — its describe/annotate metadata.
     */
    public static final class TypeDefBuilder extends ElementMeta<TypeDefBuilder> {

        private FieldTypeSpec spec;

        public TypeDefBuilder string(Consumer<StringFieldBuilder> body) {
            StringFieldBuilder b = new StringFieldBuilder();
            body.accept(b);
            return set(b.spec());
        }

        public TypeDefBuilder number(Consumer<NumberFieldBuilder> body) {
            NumberFieldBuilder b = new NumberFieldBuilder();
            body.accept(b);
            return set(b.spec());
        }

        public TypeDefBuilder enumeration(Consumer<EnumFieldBuilder> body) {
            EnumFieldBuilder b = new EnumFieldBuilder();
            body.accept(b);
            return set(b.spec());
        }

        private TypeDefBuilder set(FieldTypeSpec s) {
            if (spec != null) {
                throw new IllegalStateException("a type definition has exactly one underlying type");
            }
            spec = s;
            return this;
        }

        private FieldTypeSpec spec() {
            if (spec == null) {
                throw new IllegalStateException("a type definition needs an underlying type");
            }
            return spec;
        }
    }

    /** Fluent builder for a group and its contents — describable/annotatable via {@link ElementMeta}. */
    public static final class GroupBuilder extends ElementMeta<GroupBuilder> {

        private final String name;
        private int repeatability = 1;
        private String indexFieldName;
        private final List<ElementDraft> elements = new ArrayList<>();

        private GroupBuilder(String name) {
            this.name = name;
        }

        /** Make this group a repeatable list with the given maximum (≥ 2; {@code 1} is the single default). */
        public GroupBuilder repeatable(int max) {
            if (max < 2) {
                throw new IllegalArgumentException("a repeatable group needs max ≥ 2 (1 is non-repeating): " + max);
            }
            this.repeatability = max;
            return this;
        }

        /**
         * Mark a direct child field as this repeatable group's row key ({@code indexFieldName}) — the
         * value that identifies a row, letting two lists iterate in lockstep. Requires {@link
         * #repeatable(int)}; the named field must be a direct child (both checked at build).
         */
        public GroupBuilder indexField(String fieldName) {
            this.indexFieldName = fieldName;
            return this;
        }

        /** Add a bare, optional field of the given easy kind. */
        public GroupBuilder field(String name, FieldKind kind) {
            return field(name, kind, Requiredness.OPTIONAL);
        }

        /** Add a bare field of the given easy kind with explicit requiredness. */
        public GroupBuilder field(String name, FieldKind kind, Requiredness requiredness) {
            elements.add(new ElementDraft.Field(name, bareSpec(kind), requiredness, Map.of(), List.of()));
            return this;
        }

        /** Add a string field with optional length/pattern config, requiredness, labels, annotations. */
        public GroupBuilder stringField(String name, Consumer<StringFieldBuilder> body) {
            StringFieldBuilder b = new StringFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a number field with optional scale/range config, requiredness, labels, annotations. */
        public GroupBuilder numberField(String name, Consumer<NumberFieldBuilder> body) {
            NumberFieldBuilder b = new NumberFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add an enumeration field; needs at least one value. */
        public GroupBuilder enumField(String name, Consumer<EnumFieldBuilder> body) {
            EnumFieldBuilder b = new EnumFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a date field with an explicit constant format. */
        public GroupBuilder dateField(String name, Consumer<DateFieldBuilder> body) {
            DateFieldBuilder b = new DateFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a time-of-day field with an explicit constant format. */
        public GroupBuilder timeField(String name, Consumer<TimeFieldBuilder> body) {
            TimeFieldBuilder b = new TimeFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a date-time field with an explicit constant format. */
        public GroupBuilder dateTimeField(String name, Consumer<DateTimeFieldBuilder> body) {
            DateTimeFieldBuilder b = new DateTimeFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a date-range field (format + range separator, both required by the kernel). */
        public GroupBuilder dateRangeField(String name, Consumer<DateRangeFieldBuilder> body) {
            DateRangeFieldBuilder b = new DateRangeFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a date-fragment field (e.g. year-month) with its fragment format. */
        public GroupBuilder dateFragmentField(String name, Consumer<DateFragmentFieldBuilder> body) {
            DateFragmentFieldBuilder b = new DateFragmentFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a custom-typed field (a named kernel custom type + optional length bounds). */
        public GroupBuilder customField(String name, Consumer<CustomFieldBuilder> body) {
            CustomFieldBuilder b = new CustomFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a field whose type is a reusable {@code typeDef} (referenced by id), optional. */
        public GroupBuilder typedefField(String name, String typeDefId) {
            return typedefField(name, typeDefId, Requiredness.OPTIONAL);
        }

        /** Add a field whose type is a reusable {@code typeDef} (referenced by id), with requiredness. */
        public GroupBuilder typedefField(String name, String typeDefId, Requiredness requiredness) {
            elements.add(new ElementDraft.Field(name, new FieldTypeSpec.TypeDefRef(typeDefId),
                    requiredness, Map.of(), List.of()));
            return this;
        }

        /** Add a nested group, populated by {@code body}. */
        public GroupBuilder group(String name, Consumer<GroupBuilder> body) {
            GroupBuilder child = new GroupBuilder(name);
            body.accept(child);
            elements.add(child.toDraft());
            return this;
        }

        /**
         * Add a <b>Multi-Select</b> group template (FEATURE-GAPS §3.8): a repeatable group holding one
         * enum/string selection field. The generator owns the kernel skeleton ({@code usageType},
         * repeatability, the single-field rule); {@code body} supplies the field + {@code maxRows}.
         */
        public GroupBuilder multiSelectGroup(String name, Consumer<MultiSelectBuilder> body) {
            MultiSelectBuilder b = new MultiSelectBuilder();
            body.accept(b);
            elements.add(b.toGroup(name));
            return this;
        }

        /** Mount an included model here: a group named {@code name} that mounts the include {@code alias}. */
        public GroupBuilder mount(String name, String alias) {
            return mount(name, alias, 1);
        }

        /** Mount an included model as a <b>repeatable</b> sub-form ({@code repeatability} rows; §2). */
        public GroupBuilder mount(String name, String alias, int repeatability) {
            return mount(name, alias, MountOptions.repeatable(repeatability));
        }

        /**
         * Mount an included model with explicit {@link MountOptions} — repeatability plus whether to
         * exclude the included model's rules / computations on expand (§3.9). E.g.
         * {@code mount("Prices", "catalog", MountOptions.single().excludingRules())}.
         */
        public GroupBuilder mount(String name, String alias, MountOptions options) {
            elements.add(new ElementDraft.Mount(name, alias, options, List.of()));
            return this;
        }

        private GroupBuilder addField(String name, FieldAttrs<?> attrs) {
            elements.add(attrs.toField(name));   // one assembly point, shared with the edit surface (GroupEditor)
            return this;
        }

        private ElementDraft.Group toDraft() {
            if (indexFieldName != null) {
                if (repeatability == 1) {
                    throw new IllegalStateException("group '" + name + "' sets an index field '"
                            + indexFieldName + "' but is not repeatable");
                }
                boolean present = elements.stream()
                        .anyMatch(e -> e instanceof ElementDraft.Field f && f.name().equals(indexFieldName));
                if (!present) {
                    throw new IllegalStateException("group '" + name + "' index field '" + indexFieldName
                            + "' is not a direct child field");
                }
            }
            return new ElementDraft.Group(name, repeatability, indexFieldName,
                    List.copyOf(elements), List.copyOf(annotations), null,
                    Map.copyOf(internalDescription), Map.copyOf(externalDescription));
        }
    }

    /**
     * Builds a <b>Multi-Select</b> group template (FEATURE-GAPS §3.8) — a repeatable group holding exactly
     * one enum/string selection field (each row = one chosen value). The generator owns the kernel skeleton
     * ({@code usageType: "multi-select"}, repeatability {@code > 1}, the single field, the field's
     * requiredness, no index field — {@code MultiSelectGroupRule}); the caller supplies the field and
     * {@code maxRows}. The field's value is required per row by default (the kernel demands a
     * {@code requirednessConfig}); {@code OPTIONAL} is not a choice here.
     */
    public static final class MultiSelectBuilder {

        private String fieldName;
        private FieldTypeSpec fieldType;
        private int maxRows = -1;
        private Requiredness requiredness = Requiredness.REQUIRED;
        private final List<ElementDraft.Annotation> annotations = new ArrayList<>();

        /** The single selectable field: an enumeration of {@code values} (at least two for a real choice). */
        public MultiSelectBuilder enumField(String name, String... values) {
            List<FieldTypeSpec.EnumValue> enumValues = new ArrayList<>();
            for (String value : values) {
                enumValues.add(new FieldTypeSpec.EnumValue(value, Map.of()));
            }
            this.fieldName = name;
            this.fieldType = new FieldTypeSpec.EnumSpec(enumValues, List.of());
            return this;
        }

        /** The single selectable field: a free-text string. */
        public MultiSelectBuilder stringField(String name) {
            this.fieldName = name;
            this.fieldType = new FieldTypeSpec.StringSpec(null, null, null, null);
            return this;
        }

        /** The maximum number of selections (the group's repeatability); must be {@code > 1}. */
        public MultiSelectBuilder maxRows(int rows) {
            this.maxRows = rows;
            return this;
        }

        /** Each selected row's value is required (the default). */
        public MultiSelectBuilder required() {
            this.requiredness = Requiredness.REQUIRED;
            return this;
        }

        /** Each selected row's value is required only once the group itself is present. */
        public MultiSelectBuilder requiredIfParentPresent() {
            this.requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
            return this;
        }

        /** A name/value annotation on the multi-select group. */
        public MultiSelectBuilder annotation(String name, String value) {
            annotations.add(new ElementDraft.Annotation(name, value));
            return this;
        }

        ElementDraft.Group toGroup(String groupName) {
            if (fieldName == null) {
                throw new IllegalStateException(
                        "a multi-select needs a field — call enumField(...) or stringField(...)");
            }
            if (maxRows <= 1) {
                throw new IllegalStateException(
                        "a multi-select needs maxRows > 1 (the kernel requires a repeatable group); was " + maxRows);
            }
            ElementDraft.Field field =
                    new ElementDraft.Field(fieldName, fieldType, requiredness, Map.of(), List.of());
            return new ElementDraft.Group(groupName, maxRows, null, List.of(field),
                    List.copyOf(annotations), "multi-select");
        }
    }

    /**
     * Builds an <b>Attachment</b> group template (FEATURE-GAPS §3.8) — a {@code usageType: "attachment"}
     * group with the kernel-mandated composition: 8 named fields, the 4 {@link AttachmentRule}s (their exact
     * {@code errorCondition} strings, in the model's condition language), and a {@code content} field with
     * {@code noValueValidation} + {@code lineBreaksPermitted}. The whole skeleton is fixed; the caller only
     * tunes the exposed knobs (repeatability, max size, allowed MIME types, category values, rule-message
     * overrides). The kernel only knows {@code en_US}/{@code de_DE} attachment rule sets, so this refuses on
     * any other locale (rather than emitting rules the kernel can't validate).
     */
    public static final class AttachmentBuilder {

        private static final List<String> SUPPORTED_LOCALES = List.of("en_US", "de_DE");

        private int repeatability = 1;
        private Long maxSizeBytes;
        private List<String> allowedMimeTypes;
        private List<String> categories;
        private final Map<AttachmentRule, Map<String, String>> messageOverrides = new EnumMap<>(AttachmentRule.class);
        private final List<ElementDraft.Annotation> annotations = new ArrayList<>();

        /** Mount a <b>list</b> of attachments — up to {@code rows} (default: a single attachment). */
        public AttachmentBuilder repeatable(int rows) {
            this.repeatability = rows;
            return this;
        }

        /** Constrain the {@code size} field to a maximum (the max file size, in bytes). */
        public AttachmentBuilder maxSizeBytes(long max) {
            this.maxSizeBytes = max;
            return this;
        }

        /** Restrict {@code mime_type} to this set (it becomes an enumeration); omit to leave it a free string. */
        public AttachmentBuilder allowedMimeTypes(String... types) {
            this.allowedMimeTypes = List.of(types);
            return this;
        }

        /** Restrict {@code category} to this set (it becomes an enumeration); omit to leave it a free string. */
        public AttachmentBuilder categories(String... values) {
            this.categories = List.of(values);
            return this;
        }

        /** Override a mandated rule's message for one locale (otherwise a canonical default is used). */
        public AttachmentBuilder message(AttachmentRule rule, String locale, String text) {
            messageOverrides.computeIfAbsent(rule, r -> new LinkedHashMap<>()).put(locale, text);
            return this;
        }

        /** A name/value annotation on the attachment group. */
        public AttachmentBuilder annotation(String name, String value) {
            annotations.add(new ElementDraft.Annotation(name, value));
            return this;
        }

        ElementDraft.Group toGroup(String groupName, List<String> locales, String conditionLanguage) {
            for (String locale : locales) {
                if (!SUPPORTED_LOCALES.contains(locale)) {
                    throw new IllegalStateException("the attachment template generates en/de rules (conditions "
                            + "and messages) and supports only en_US/de_DE models; the model declares '" + locale + "'");
                }
            }
            boolean german = "de_DE".equals(conditionLanguage);
            List<ElementDraft> elements = new ArrayList<>();
            elements.add(stringField("original_filename"));
            elements.add(stringField("internal_filename"));
            elements.add(contentField());
            elements.add(stringField("attachment_id"));
            elements.add(sizeField());
            elements.add(new ElementDraft.Field("mime_type", enumOrString(allowedMimeTypes),
                    Requiredness.OPTIONAL, Map.of(), List.of()));
            elements.add(new ElementDraft.Field("category", enumOrString(categories),
                    Requiredness.OPTIONAL, Map.of(), List.of()));
            elements.add(stringField("description"));
            for (AttachmentRule rule : AttachmentRule.values()) {
                elements.add(ruleFor(rule, german, locales));
            }
            return new ElementDraft.Group(groupName, repeatability, null, elements,
                    List.copyOf(annotations), "attachment");
        }

        private static ElementDraft.Field stringField(String name) {
            return new ElementDraft.Field(name, new FieldTypeSpec.StringSpec(null, null, null, null),
                    Requiredness.OPTIONAL, Map.of(), List.of());
        }

        /** The kernel requires {@code content} to be a String that skips value validation and permits line breaks. */
        private static ElementDraft.Field contentField() {
            FieldTypeSpec spec = new FieldTypeSpec.StringSpec(null, null, null, null, true, false, Map.of(), true);
            return new ElementDraft.Field("content", spec, Requiredness.OPTIONAL, Map.of(), List.of());
        }

        private ElementDraft.Field sizeField() {
            BigDecimal max = maxSizeBytes == null ? null : BigDecimal.valueOf(maxSizeBytes);
            return new ElementDraft.Field("size", new FieldTypeSpec.NumberSpec(0, null, max),
                    Requiredness.OPTIONAL, Map.of(), List.of());
        }

        private static FieldTypeSpec enumOrString(List<String> values) {
            if (values == null || values.isEmpty()) {
                return new FieldTypeSpec.StringSpec(null, null, null, null);
            }
            List<FieldTypeSpec.EnumValue> enumValues = new ArrayList<>();
            for (String value : values) {
                enumValues.add(new FieldTypeSpec.EnumValue(value, Map.of()));
            }
            return new FieldTypeSpec.EnumSpec(enumValues, List.of());
        }

        private ElementDraft.Rule ruleFor(AttachmentRule rule, boolean german, List<String> locales) {
            Map<String, String> messages = new LinkedHashMap<>();
            Map<String, String> overrides = messageOverrides.getOrDefault(rule, Map.of());
            for (String locale : locales) {
                messages.put(locale, overrides.getOrDefault(locale, rule.defaultMessage("de_DE".equals(locale))));
            }
            return new ElementDraft.Rule(rule.ruleName, "../" + rule.errorField, rule.code,
                    rule.condition(german), "ERROR", messages);
        }
    }

    /** Referential integrity: every {@code typedefField} must point at a declared {@code typeDef}. */
    private void checkTypeDefReferences() {
        // when the model imports type-defs from elsewhere, a ref may resolve to an imported id — the local
        // ids aren't the full set, so defer the check to the kernel's expand-time resolution.
        if (!typeDefImports.isEmpty()) {
            return;
        }
        Set<String> declared = new HashSet<>();
        typeDefs.forEach(d -> declared.add(d.id()));
        rootGroups.forEach(g -> checkTypeDefReferences(g, declared));
    }

    private static void checkTypeDefReferences(ElementDraft.Group group, Set<String> declared) {
        for (ElementDraft element : group.elements()) {
            switch (element) {
                case ElementDraft.Group sub -> checkTypeDefReferences(sub, declared);
                case ElementDraft.Field field -> {
                    if (field.type() instanceof FieldTypeSpec.TypeDefRef ref
                            && !declared.contains(ref.typeDefinitionId())) {
                        throw new IllegalStateException("field '" + field.name()
                                + "' references undeclared type definition '" + ref.typeDefinitionId() + "'");
                    }
                }
                case ElementDraft.Mount mount -> { /* mount aliases are checked separately */ }
                case ElementDraft.Rule rule -> { /* rules carry no type-def reference */ }
            }
        }
    }

    /** Referential integrity: every mount's {@code alias} must match a declared {@code include}. */
    private void checkMountReferences() {
        Set<String> aliases = new HashSet<>();
        includes.forEach(i -> aliases.add(i.alias()));
        rootGroups.forEach(g -> checkMountReferences(g, aliases));
    }

    private static void checkMountReferences(ElementDraft.Group group, Set<String> aliases) {
        for (ElementDraft element : group.elements()) {
            switch (element) {
                case ElementDraft.Group sub -> checkMountReferences(sub, aliases);
                case ElementDraft.Field field -> { /* fields carry no mount alias */ }
                case ElementDraft.Rule rule -> { /* rules carry no mount alias */ }
                case ElementDraft.Mount mount -> {
                    if (!aliases.contains(mount.alias())) {
                        throw new IllegalStateException("mount '" + mount.name()
                                + "' references undeclared include alias '" + mount.alias() + "'");
                    }
                }
            }
        }
    }

    // package-private: reused by ModelDocument (M8) so an added easy-kind field gets the same valid
    // type node the builder emits (e.g. DATE carries its default format — not a bare DateType).
    static FieldTypeSpec bareSpec(FieldKind kind) {
        return switch (kind) {
            case STRING -> new FieldTypeSpec.StringSpec(null, null, null, null);
            case NUMBER -> new FieldTypeSpec.NumberSpec(null, null, null);
            case DATE -> new FieldTypeSpec.DateSpec(null, "FULL");
            case BOOLEAN -> new FieldTypeSpec.BooleanSpec();
            case CONFIRM -> new FieldTypeSpec.ConfirmSpec();
        };
    }

}
