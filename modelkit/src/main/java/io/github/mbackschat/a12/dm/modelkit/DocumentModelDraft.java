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

/**
 * modelkit M1 — a typed builder for document-model <b>structure</b>, the peer of rulekit's rule
 * builder (fork #8 / ARCHITECTURE §3.3; rationale in {@code docs/PROJECT-DESIGN.md} §21). Author a
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
     * Change the rare German-keyword case explicitly with
     * {@code ModelDocument.read(draft.toJson()).setConditionLanguage("de_DE")} — {@code config()} is the
     * read-only projection, so it carries no setter.
     */
    static final String DEFAULT_CONDITION_LANGUAGE = "en_US";

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
        this.labels = OrderedMap.copyOf(b.labels);
        this.annotations = List.copyOf(b.annotations);
        this.supportedCharacters = SupportedCharactersContract.requireValid(b.supportedCharacters);
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

        ObjectNode header = root.putObject(DmKeys.HEADER);
        header.put(DmKeys.ID, id);
        header.put(DmKeys.MODEL_TYPE, DmKeys.DOCUMENT);
        header.put("modelVersion", MODEL_VERSION);
        ArrayNode locs = header.putArray(DmKeys.LOCALES);
        locales.forEach(code -> locs.addObject().put(DmKeys.CODE, code));
        ArrayNode labelArr = header.putArray(DmKeys.LABELS);
        labels.forEach((loc, text) -> labelArr.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, text));
        ArrayNode annoArr = header.putArray(DmKeys.ANNOTATIONS);
        annotations.forEach(a -> annoArr.addObject().put(DmKeys.NAME, a.name()).put(DmKeys.VALUE, a.value()));
        checkMountReferences();
        ArrayNode refs = header.putArray(DmKeys.MODEL_REFERENCES);
        for (Include inc : includes) {
            refs.addObject()
                    .put(DmKeys.ALIAS, inc.alias())
                    .put(DmKeys.MODEL_TYPE, DmKeys.DOCUMENT)
                    .put(DmKeys.PURPOSE, "include")
                    .put(DmKeys.REFERENCE, inc.reference());
        }
        for (String importRef : typeDefImports) {
            // a type-def import: purpose "typeDefinitions" (kernel PURPOSE_4_ONLY_TYPE_DEFS) — merged by id, not mounted
            refs.addObject()
                    .put(DmKeys.ALIAS, importRef)
                    .put(DmKeys.MODEL_TYPE, DmKeys.DOCUMENT)
                    .put(DmKeys.PURPOSE, "typeDefinitions")
                    .put(DmKeys.REFERENCE, importRef);
        }

        ObjectNode content = root.putObject(DmKeys.CONTENT);
        ObjectNode info = content.putObject(DmKeys.MODEL_INFO);
        info.put(DmKeys.NAME, id);
        info.put("variant", "cockpit");
        info.put("revision", "Arbeitsversion");
        if (baseYear != null) {
            // the model-level base year (handles year-less date inputs and enables the BaseYear point-in-time)
            info.put(DmKeys.BASE_YEAR, String.valueOf(baseYear));
        }
        if (comment != null) {
            info.put(DmKeys.COMMENT, comment);   // a free-text model-level note (IDocumentModelInfo.getComment)
        }
        info.put("immutable", false);
        ObjectNode config = content.putObject(DmKeys.MODEL_CONFIG);
        config.put(DmKeys.DECIMAL_SEPARATOR, ".");
        config.put(DmKeys.TIME_ZONE, "Europe/Berlin");
        // Condition KEYWORD language is independent of the DISPLAY locale (see DEFAULT_CONDITION_LANGUAGE).
        config.putObject(DmKeys.CONDITION_LANGUAGE).put(DmKeys.CODE, DEFAULT_CONDITION_LANGUAGE);
        config.put(DmKeys.FIELD_REF_BY_SHORT_NAME_ALLOWED, true);
        if (!supportedCharacters.isEmpty()) {
            // each entry is one singleton/range/atomic grapheme; an absent list selects the BMP default
            ArrayNode chars = config.putArray(DmKeys.SUPPORTED_CHARACTERS);
            supportedCharacters.forEach(chars::add);
        }

        checkTypeDefReferences();
        if (!typeDefs.isEmpty()) {
            ArrayNode defs = content.putArray(DmKeys.TYPE_DEFINITIONS);
            for (TypeDefDecl def : typeDefs) {
                ObjectNode node = defs.addObject();
                node.put(DmKeys.ID, def.id());
                node.put(DmKeys.NAME, def.id());
                writeAnnotations(node, def.annotations());
                writeLocalizedText(node, DmKeys.INTERNAL_DESCRIPTION, def.internalDescription());
                writeLocalizedText(node, DmKeys.EXTERNAL_DESCRIPTION, def.externalDescription());
                writeFieldType(node.putObject(DmKeys.FIELD_TYPE), def.type(), locales);
            }
        }

        ArrayNode roots = content.putObject(DmKeys.MODEL_ROOT).putArray(DmKeys.ROOT_GROUPS);
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
        node.put(DmKeys.TYPE, ElementType.GROUP.wireName());
        node.put(DmKeys.ID, path);
        node.put(DmKeys.NAME, group.name());
        writeAnnotations(node, group.annotations());
        // internal/external description are element-level (alongside annotations), uniform with fields (IDescribed)
        writeLocalizedText(node, DmKeys.INTERNAL_DESCRIPTION, group.meta().internalDescription());
        writeLocalizedText(node, DmKeys.EXTERNAL_DESCRIPTION, group.meta().externalDescription());
        ObjectNode body = node.putObject(ElementType.GROUP.wireName());
        body.put(DmKeys.REPEATABILITY, group.repeatability());
        // the display label (ILabeled) lives in the Group body, like a field's — verified against real models
        writeLocalizedLabels(body, group.meta().labels(), locales);
        if (group.usageType() != null) {
            // the kernel group-template marker (attachment / multi-select), FEATURE-GAPS
            body.put("usageType", group.usageType());
        }
        if (group.indexFieldName() != null) {
            body.put(DmKeys.INDEX_FIELD_NAME, group.indexFieldName());
        }
        if (!group.sortFields().isEmpty()) {
            // FEATURE-GAPS G28: each sort field is {"name": <relative child path>, "order": ASC|DESC}
            // (the kernel's GroupSortFieldMixin maps the field ref to "name"; the Order enum to "order").
            ArrayNode sortFields = body.putArray(DmKeys.SORT_FIELDS);
            for (ElementDraft.SortField sf : group.sortFields()) {
                ObjectNode sortNode = sortFields.addObject();
                sortNode.put(DmKeys.NAME, sf.fieldName());
                sortNode.put(DmKeys.ORDER, sf.order().name());
            }
        }
        ArrayNode elements = body.putArray(DmKeys.ELEMENTS);
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
    // package-private: reused by ModelDocument when emitting a template group's mandated rules
    static ObjectNode ruleNode(ElementDraft.Rule rule, String parentPath) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(DmKeys.TYPE, ElementType.RULE.wireName());
        node.put(DmKeys.ID, parentPath + "/" + rule.name());
        node.put(DmKeys.NAME, rule.name());
        ObjectNode body = node.putObject(ElementType.RULE.wireName());
        body.put("errorEntityRelPath", rule.errorEntityRelPath());
        body.put("errorCode", rule.errorCode());
        body.put("errorCondition", rule.errorCondition());
        body.put("severity", rule.severity());
        ArrayNode messages = body.putArray(DmKeys.ERROR_MESSAGE);
        rule.messages().forEach((loc, text) -> messages.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, text));
        return node;
    }

    /** An include mount: a group carrying {@code modelAlias} and <b>no</b> inline elements (§10). */
    // package-private: reused by ModelDocument (M10+ include editing)
    static ObjectNode mountNode(ElementDraft.Mount mount, String parentPath) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(DmKeys.TYPE, ElementType.GROUP.wireName());
        node.put(DmKeys.ID, parentPath + "/" + mount.name());
        node.put(DmKeys.NAME, mount.name());
        writeAnnotations(node, mount.annotations());
        ObjectNode body = node.putObject(ElementType.GROUP.wireName());
        MountOptions options = mount.options();
        body.put(DmKeys.REPEATABILITY, options.repeatability());
        body.put(DmKeys.MODEL_ALIAS, mount.alias());
        // includeLevel sits on the mount group beside modelAlias; omit SINGLE_RG (the kernel default-absent, A12K-3234)
        if (options.includeLevel() == IncludeLevel.MODEL_ROOT) {
            body.put("includeLevel", "MODEL_ROOT");
        }
        // rule/computation exclusions sit on the mount group beside modelAlias (metamodel MM_Group)
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
        node.put(DmKeys.TYPE, ElementType.FIELD.wireName());
        node.put(DmKeys.ID, parentPath + "/" + field.name());
        node.put(DmKeys.NAME, field.name());
        FieldMeta meta = field.meta();
        writeAnnotations(node, meta.annotations());
        // internal/external description are element-level (alongside annotations), per the kernel serializer
        writeLocalizedText(node, DmKeys.INTERNAL_DESCRIPTION, meta.internalDescription());
        writeLocalizedText(node, DmKeys.EXTERNAL_DESCRIPTION, meta.externalDescription());
        ObjectNode body = node.putObject(ElementType.FIELD.wireName());
        writeFieldType(body.putObject(DmKeys.FIELD_TYPE), field.type(), locales);
        String mode = field.requiredness().mode();
        if (mode != null) {
            body.putObject(DmKeys.REQUIREDNESS_CONFIG).put("mode", mode);
        }
        writeLocalizedLabels(body, meta.labels(), locales);
        // global / transient flags + helperText live inside the Field body (kernel FieldSerializer)
        if (meta.global()) {
            body.put("global", true);
        }
        if (meta.notPersisted()) {
            body.put("transient", true);
        }
        writeLocalizedText(body, "helperText", meta.helperText());
        return node;
    }

    /**
     * Emit a per-locale display {@code label} into an element body (a field's or a group's — both are the
     * kernel's {@code ILabeled}) for every locale the author supplied, declared ones first.
     *
     * <p>A label here owes NO locale coverage: the kernel accepts a field or group label that answers only one
     * of two declared locales, and one written solely for an undeclared locale
     * ({@code rulekit.validate.laws.ElementLabelLocaleLawsTest}). This writer refused both until 2026-09-14,
     * by analogy with the enum-VALUE law — a real law about a different member — so the refusal made a model
     * the kernel takes both unauthorable and, since the draft is also the read model, unreadable.
     *
     * <p>Ordering declared locales first keeps a fully-answered element's bytes identical to what this writer
     * produced before it learned to carry the rest; the remainder follows in the order they were added.
     */
    private static void writeLocalizedLabels(ObjectNode body, Map<String, String> labels, List<String> locales) {
        if (labels.isEmpty()) {
            return;
        }
        ArrayNode label = body.putArray(DmKeys.LABEL);
        locales.stream().filter(labels::containsKey)
                .forEach(loc -> label.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, labels.get(loc)));
        labels.forEach((loc, text) -> {
            if (!locales.contains(loc)) {
                label.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, text);
            }
        });
    }

    /** Emit an optional per-locale text slot as {@code key: [{locale, text}]} (omitted when empty). */
    private static void writeLocalizedText(ObjectNode target, String key, Map<String, String> textByLocale) {
        if (textByLocale.isEmpty()) {
            return;
        }
        ArrayNode array = target.putArray(key);
        textByLocale.forEach((loc, text) -> array.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, text));
    }

    /** Emit element-level {@code annotations: [{name, value}]} (omitted when none). */
    private static void writeAnnotations(ObjectNode elementNode, List<ElementDraft.Annotation> annotations) {
        if (annotations.isEmpty()) {
            return;
        }
        ArrayNode array = elementNode.putArray(DmKeys.ANNOTATIONS);
        for (ElementDraft.Annotation a : annotations) {
            array.addObject().put(DmKeys.NAME, a.name()).put(DmKeys.VALUE, a.value());
        }
    }

    /** Write the {@code type} discriminator plus its inner config block. */
    // package-private: reused by ModelDocument (M10+ type-definition editing)
    static void writeFieldType(ObjectNode fieldType, FieldTypeSpec spec, List<String> locales) {
        fieldType.put(DmKeys.TYPE, spec.jsonType());
        writeTypeConfig(fieldType, spec, locales);
    }

    /**
     * Write the stored-value overrides of a boolean-valued field, omitting the inner block when neither is set.
     *
     * The two are independent: a model may override only one, so this writes whichever are present rather
     * than treating them as a pair.
     */
    private static void storedValues(ObjectNode fieldType, String type, String trueValue, String falseValue) {
        if (trueValue == null && falseValue == null) {
            return;
        }
        ObjectNode inner = fieldType.putObject(type);
        if (trueValue != null) {
            inner.put("notInDCustomTrueValue", trueValue);
        }
        if (falseValue != null) {
            inner.put("notInDCustomFalseValue", falseValue);
        }
    }

    /** Write the kernel {@code fieldType.<Type>} inner block for the configured slots (omitted when empty). */
    private static void writeTypeConfig(ObjectNode fieldType, FieldTypeSpec spec, List<String> locales) {
        switch (spec) {
            case FieldTypeSpec.StringSpec s -> {
                if (s.minLength() != null || s.maxLength() != null || s.pattern() != null
                        || s.lineBreaksPermitted() || s.alphabeticalSorting() || !s.suggestions().isEmpty()
                        || s.noValueValidation() || !s.patternMessages().isEmpty()) {
                    ObjectNode inner = fieldType.putObject("StringType");
                    // KF490 judges declared locales; undeclared entries are legal authored content too.
                    Map<String, String> messages = new LinkedHashMap<>();
                    if (s.patternMessage() != null) {
                        locales.forEach(loc -> messages.put(loc, s.patternMessage()));
                    }
                    messages.putAll(s.patternMessages());
                    writeLocalizedText(inner, DmKeys.ERROR_MESSAGE, messages);
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
                            entry.put(DmKeys.LOCALE, loc);
                            ArrayNode values = entry.putArray(DmKeys.VALUES);
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
                // Emitted only when set, like its two flag neighbours: A12's default is signed, and the
                // kernel reads this key inverted as hasSign().
                if (n.positivesOnly()) {
                    inner.put("positivesOnly", true);
                }
                if (n.leadingZerosAllowed()) {
                    inner.put("leadingZerosAllowed", true);
                }
                if (n.trait() != null) {
                    inner.put("trait", n.trait().json());
                }
                if (n.minLength() != null) {
                    inner.put("minLength", n.minLength());
                }
                if (n.maxLength() != null) {
                    inner.put("maxLength", n.maxLength());
                }
                if (n.maxIntegerDigits() != null) {
                    inner.put("maxIntegerDigits", n.maxIntegerDigits());
                }
                if (n.positivesOnly()) {
                    inner.put("positivesOnly", true);
                }
            }
            case FieldTypeSpec.DateSpec d -> {
                ObjectNode inner = fieldType.putObject("DateType");
                inner.put("format", d.format() != null ? d.format() : "yyyy-MM-dd");
                // datePrecision is omitted for FULL (the kernel default), matching its serializer
                if (d.datePrecision() != null && !d.datePrecision().equals("FULL")) {
                    inner.put("datePrecision", d.datePrecision());
                }
                if (d.rejectBefore1900()) {
                    inner.put("youngerThan1900Check", true);
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
                // STANDARD has no DM-JSON spelling: the kernel's deserializer refuses the token and reads an
                // ABSENT key as the default, so the key is written only for FROM/TO.
                dr.interpretationOfYear().json().ifPresent(v -> inner.put("interpretationOfYear", v));
                if (dr.rejectBefore1900()) {
                    inner.put("youngerThan1900Check", true);
                }
            }
            case FieldTypeSpec.DateFragmentSpec df -> {
                ObjectNode inner = fieldType.putObject("DateFragmentType");
                inner.put("formatOfFragment", df.fragmentFormat());
                if (df.rejectBefore1900()) {
                    inner.put("youngerThan1900Check", true);
                }
            }
            case FieldTypeSpec.TypeDefRef ref ->
                    fieldType.putObject("TypeDefType").put(DmKeys.TYPE_DEFINITION_ID, ref.typeDefinitionId());
            case FieldTypeSpec.BooleanSpec b -> storedValues(fieldType, "BooleanType",
                    b.customTrueValue(), b.customFalseValue());
            case FieldTypeSpec.ConfirmSpec c -> storedValues(fieldType, "ConfirmType",
                    c.customTrueValue(), c.customFalseValue());
            case FieldTypeSpec.EnumSpec e -> {
                // The enum-VALUE label law is armed by an entry for a DECLARED locale, not by any entry at
                // all: labels written solely for an undeclared locale are accepted (KERNEL-FINDINGS KF321),
                // so reading `anyMatch(labels not empty)` as the trigger refused a model the kernel takes.
                boolean labelled = e.values().stream()
                        .anyMatch(v -> locales.stream().anyMatch(v.labels()::containsKey));
                if (labelled) {
                    // Once armed it is all-or-nothing: every value needs a label for every declared locale,
                    // else MVK_COUNT_OF_VALUES_INVALID. Fail fast with a clear message.
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
                ArrayNode values = enumeration.putArray(DmKeys.VALUES);
                for (FieldTypeSpec.EnumValue v : e.values()) {
                    ObjectNode valueNode = values.addObject();
                    valueNode.put(DmKeys.VALUE, v.value());
                    writeLocalizedLabels(valueNode, v.labels(), locales);
                }
                if (!e.categories().isEmpty()) {
                    ArrayNode categories = enumeration.putArray("categories");
                    for (FieldTypeSpec.EnumCategory c : e.categories()) {
                        ObjectNode categoryNode = categories.addObject();
                        categoryNode.put(DmKeys.NAME, c.name());
                        if (c.description() != null) {
                            categoryNode.put(DmKeys.DESCRIPTION, c.description());   // editor note; kernel tolerates it
                        }
                        ArrayNode catValues = categoryNode.putArray(DmKeys.VALUES);
                        c.values().forEach(catValues::add);
                    }
                }
                if (e.alphabeticalSorting()) {
                    enumeration.put("alphabeticalSorting", true);
                }
                if (e.sValue() != null) {
                    enumeration.put("sValue", e.sValue());   // the index-default stored token (SPEC-2026-07-23-14)
                }
                if (!e.invalidValueMessages().isEmpty()) {
                    // G23: per-locale invalid-value text — written verbatim (the kernel requires every declared
                    // locale to be covered, like the enum value labels)
                    ArrayNode messages = enumeration.putArray(DmKeys.ERROR_MESSAGE);
                    e.invalidValueMessages().forEach((loc, text) -> messages.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, text));
                } else if (e.invalidValueMessage() != null) {
                    // the single-text shorthand, expanded across every declared locale (the kernel's localized map)
                    ArrayNode messages = enumeration.putArray(DmKeys.ERROR_MESSAGE);
                    locales.forEach(loc -> messages.addObject().put(DmKeys.LOCALE, loc).put(DmKeys.TEXT, e.invalidValueMessage()));
                }
            }
            case FieldTypeSpec.CustomSpec cf -> {
                ObjectNode inner = fieldType.putObject("CustomFieldType");
                inner.put(DmKeys.NAME, cf.name());
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
        String type = fieldType.path(DmKeys.TYPE).asText("");
        JsonNode c = fieldType.path(type);   // the config lives under the discriminator key
        switch (type) {
            case "StringType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "pattern", b, "pattern");
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                if (c.path("lineBreaksPermitted").asBoolean(false)) b.put("lineBreaks", true);
                if (c.path("noValueValidation").asBoolean(false)) b.put("noValueValidation", true);
                if (c.path("alphabeticalSorting").asBoolean(false)) b.put("alphabeticalSorting", true);
                localizedMap(c.path(DmKeys.ERROR_MESSAGE)).ifPresent(m -> b.set("patternMessages", m));
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
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                copyInt(c, "maxIntegerDigits", b, "maxIntegerDigits");
                if (c.path("zeroNotAllowed").asBoolean(false)) b.put("zeroNotAllowed", true);
                if (c.path("leadingZerosAllowed").asBoolean(false)) b.put("leadingZerosAllowed", true);
                if (c.path("positivesOnly").asBoolean(false)) b.put("positivesOnly", true);
                if (!b.isEmpty()) out.set("number", b);
            }
            case "EnumerationType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                ArrayNode values = b.putArray(DmKeys.VALUES);
                for (JsonNode v : c.path(DmKeys.VALUES)) {
                    ObjectNode valueNode = values.addObject();
                    valueNode.put(DmKeys.VALUE, v.path(DmKeys.VALUE).asText());
                    localizedMap(v.path(DmKeys.LABEL)).ifPresent(m -> valueNode.set(DmKeys.LABELS, m));
                }
                JsonNode categories = c.path("categories");
                if (categories.isArray() && !categories.isEmpty()) {
                    ArrayNode catsOut = b.putArray("categories");
                    for (JsonNode cat : categories) {
                        ObjectNode catOut = catsOut.addObject();
                        catOut.put(DmKeys.NAME, cat.path(DmKeys.NAME).asText());
                        if (cat.hasNonNull(DmKeys.DESCRIPTION)) catOut.put(DmKeys.DESCRIPTION, cat.get(DmKeys.DESCRIPTION).asText());
                        ArrayNode vals = catOut.putArray(DmKeys.VALUES);
                        cat.path(DmKeys.VALUES).forEach(val -> vals.add(val.asText()));
                    }
                }
                if (c.path("alphabeticalSorting").asBoolean(false)) b.put("alphabeticalSorting", true);
                copyText(c, "sValue", b, "sValue");   // the index-default stored token — must survive a read→modify round-trip
                localizedMap(c.path(DmKeys.ERROR_MESSAGE)).ifPresent(m -> b.set("errorMessages", m));
                out.set("enum", b);
            }
            case "DateType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "format", b, "format");
                copyText(c, "datePrecision", b, "precision");
                if (c.path("youngerThan1900Check").asBoolean(false)) b.put("rejectBefore1900", true);
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
                // Absent when the declaration omits it, which is how STANDARD is expressed — so the read block
                // stays a legal `field add`/`field modify` input in both cases.
                copyText(c, "interpretationOfYear", b, "interpretationOfYear");
                if (c.path("youngerThan1900Check").asBoolean(false)) b.put("rejectBefore1900", true);
                if (!b.isEmpty()) out.set("dateRange", b);
            }
            case "DateFragmentType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, "formatOfFragment", b, "format");
                if (c.path("youngerThan1900Check").asBoolean(false)) b.put("rejectBefore1900", true);
                if (!b.isEmpty()) out.set("dateFragment", b);
            }
            case "CustomFieldType" -> {
                ObjectNode b = MAPPER.createObjectNode();
                copyText(c, DmKeys.NAME, b, "typeName");
                copyInt(c, "minLength", b, "minLength");
                copyInt(c, "maxLength", b, "maxLength");
                if (!b.isEmpty()) out.set("custom", b);
            }
            case "TypeDefType" -> out.put("typedef", c.path(DmKeys.TYPE_DEFINITION_ID).asText());
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
            map.put(entry.path(DmKeys.LOCALE).asText(), entry.path(DmKeys.TEXT).asText());
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
            ArrayNode values = map.putArray(entry.path(DmKeys.LOCALE).asText());
            entry.path(DmKeys.VALUES).forEach(v -> values.add(v.asText()));
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
         * Restrict the {@code modelConfig} allowed-character set. Each entry is a singleton BMP character,
         * an inclusive range such as {@code " -~"}, or one atomic combined grapheme (for example decomposed
         * {@code "e\u0301"}). The kernel checks string/enum/custom values against the complete entries. Omit
         * or pass nothing for the full-BMP default (surrogates/supplementary characters remain illegal).
         *
         * @see SupportedCharactersContract
         */
        public Builder supportedCharacters(String... chars) {
            List<String> additional = List.of(chars);
            List<String> prospective = new ArrayList<>(supportedCharacters);
            prospective.addAll(additional);
            SupportedCharactersContract.requireValid(prospective);
            supportedCharacters.addAll(additional);
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
                    OrderedMap.copyOf(b.internalDescription), OrderedMap.copyOf(b.externalDescription)));
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

        /** Add a top-level <b>Multi-Select</b> group template (FEATURE-GAPS); see {@link GroupBuilder#multiSelectGroup}. */
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
         * Add a top-level <b>Attachment</b> group template (FEATURE-GAPS) — the kernel's canonical
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
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final List<ElementDraft> elements = new ArrayList<>();
        private final List<ElementDraft.SortField> sortFields = new ArrayList<>();

        private GroupBuilder(String name) {
            this.name = name;
        }

        /**
         * Set this group's per-locale display {@code label} (its caption — the kernel's {@code ILabeled}, which
         * a group carries via {@code IEntity} just like a field). Call once per locale.
         *
         * <p>A partial label is legal: the kernel accepts a label answering fewer than all declared locales and
         * reads the uncovered ones back as absent (KF406, locked by {@code ElementLabelLocaleTest}). The
         * all-or-nothing completeness law is the <em>enum value</em> one (KF321) and does not reach here — this
         * javadoc claimed it did, "uniform with field/enum labels", after the demand itself was removed as
         * unfounded (AF149).
         */
        public GroupBuilder label(String locale, String text) {
            labels.put(locale, text);
            return this;
        }

        /**
         * Make this group a repeatable list with the given maximum (≥ 2; {@code 1} is the single default).
         *
         * <p>The {@code ≥ 2} bound is <b>not</b> the kernel's {@code repeatability} domain, which is any
         * integer {@code > 0} ([KF221](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf221)) — it is this builder refusing a
         * self-contradictory instruction. A non-repeatable group serializes as {@code "repeatability": 1},
         * and the kernel reads that value as not repeatable: starring such a group draws
         * {@code MVK_INVALID_WILDCARD} with the message "Since '…' is not repeatable, it is not allowed to
         * specify an asterisk" (measured 2026-08-31). So {@code repeatable(1)} would ask for a repetition
         * list the kernel would then refuse to iterate; leave the group non-repeatable instead.
         */
        public GroupBuilder repeatable(int max) {
            if (max < 2) {
                throw new IllegalArgumentException("a repeatable group needs max ≥ 2 (1 is non-repeating): " + max);
            }
            this.repeatability = max;
            return this;
        }

        /**
         * Preserve this group's relative index-field reference ({@code indexFieldName}). Its short name
         * must name a direct child; the model's consistency gate decides whether the reference resolves.
         * Non-repeatable groups may also declare one ({@code IndexFieldTest}).
         */
        public GroupBuilder indexField(String fieldName) {
            this.indexFieldName = fieldName;
            return this;
        }

        /**
         * Add a declared row <b>sort field</b> (FEATURE-GAPS G28): order this repeatable group's rows by the
         * direct-child field {@code fieldName}, {@code order} direction. Call repeatedly for a multi-key sort —
         * the keys apply in declaration order. Requires {@link #repeatable(int)}; the named field must be a
         * direct child (both checked at build). Display/USB-only — the kernel does not reorder rows for
         * validation/computation (KERNEL-FINDINGS / INTERPRETER-FINDINGS IF22).
         */
        public GroupBuilder sortField(String fieldName, SortOrder order) {
            sortFields.add(new ElementDraft.SortField(fieldName, order));
            return this;
        }

        /** Add a bare, optional field of the given easy kind. */
        public GroupBuilder field(String name, FieldKind kind) {
            return field(name, kind, Requiredness.OPTIONAL);
        }

        /** Add a bare field of the given easy kind with explicit requiredness. */
        public GroupBuilder field(String name, FieldKind kind, Requiredness requiredness) {
            elements.add(new ElementDraft.Field(name, bareSpec(kind), requiredness));
            return this;
        }

        /** Add a string field with optional length/pattern config, requiredness, labels, annotations. */
        public GroupBuilder stringField(String name, Consumer<StringFieldBuilder> body) {
            StringFieldBuilder b = new StringFieldBuilder();
            body.accept(b);
            return addField(name, b);
        }

        /** Add a three-state Boolean field, optionally overriding the text stored for true and false. */
        public GroupBuilder booleanField(String name, Consumer<BooleanFieldBuilder> body) {
            BooleanFieldBuilder b = new BooleanFieldBuilder(FieldKind.BOOLEAN);
            body.accept(b);
            return addField(name, b);
        }

        /** Add a Confirm field (unspecified reads as False), with the same two stored-value overrides. */
        public GroupBuilder confirmField(String name, Consumer<BooleanFieldBuilder> body) {
            BooleanFieldBuilder b = new BooleanFieldBuilder(FieldKind.CONFIRM);
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
                    requiredness));
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
         * Add a <b>Multi-Select</b> group template (FEATURE-GAPS): a repeatable group holding one
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

        /** Mount an included model as a <b>repeatable</b> sub-form ({@code repeatability} rows). */
        public GroupBuilder mount(String name, String alias, int repeatability) {
            return mount(name, alias, MountOptions.repeatable(repeatability));
        }

        /**
         * Mount an included model with explicit {@link MountOptions} — repeatability plus whether to
         * exclude the included model's rules / computations on expand. E.g.
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
            // A12 refuses a model in which one group holds two children of one name, whatever kinds they
            // are, and refuses it while BUILDING — so it reports no code and judges nothing else in the
            // model (`rulekit.validate.laws.ElementNameUniquenessLawsTest`, kernel 30.8.1, measured
            // 2026-09-13 across field/group/rule/computation pairings). Emitting one anyway hands the
            // author a codeless refusal naming neither the group nor the name. Deliberately on the
            // AUTHORING path only: reading such a model must keep working, since reporting on it is what
            // the consistency checker is for.
            Set<String> declared = new HashSet<>();
            for (ElementDraft element : elements) {
                if (!declared.add(element.name())) {
                    throw new IllegalStateException("group '" + name + "' declares '" + element.name()
                            + "' twice; A12 cannot build a model in which one group holds two children of"
                            + " the same name, whatever kinds they are");
                }
            }
            if (indexFieldName != null) {
                // A12 checks the resolved short name, not the landing's parent (GroupIndexAndSortReferenceLawsTest).
                String shortName = indexFieldName.substring(indexFieldName.lastIndexOf('/') + 1);
                boolean present = elements.stream()
                        .anyMatch(e -> e instanceof ElementDraft.Field f && f.name().equals(shortName));
                if (!present) {
                    throw new IllegalStateException("group '" + name + "' index field '" + indexFieldName
                            + "' is not a direct child field");
                }
            }
            for (ElementDraft.SortField sf : sortFields) {
                if (repeatability == 1) {
                    throw new IllegalStateException("group '" + name + "' sets a sort field '"
                            + sf.fieldName() + "' but is not repeatable");
                }
                boolean present = elements.stream()
                        .anyMatch(e -> e instanceof ElementDraft.Field f && f.name().equals(sf.fieldName()));
                if (!present) {
                    throw new IllegalStateException("group '" + name + "' sort field '" + sf.fieldName()
                            + "' is not a direct child field");
                }
            }
            GroupMeta meta = GroupMeta.builder()
                    .labels(labels)
                    .annotations(annotations)
                    .internalDescription(internalDescription)
                    .externalDescription(externalDescription)
                    .build();
            return new ElementDraft.Group(name, repeatability, indexFieldName,
                    List.copyOf(elements), null, List.copyOf(sortFields), meta);
        }
    }

    /**
     * Builds a <b>Multi-Select</b> group template (FEATURE-GAPS) — a repeatable group holding exactly
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
                    new ElementDraft.Field(fieldName, fieldType, requiredness);
            return new ElementDraft.Group(groupName, maxRows, null, List.of(field), "multi-select",
                    GroupMeta.builder().annotations(annotations).build());
        }
    }

    /**
     * Builds an <b>Attachment</b> group template (FEATURE-GAPS) — a {@code usageType: "attachment"}
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
                    Requiredness.OPTIONAL));
            elements.add(new ElementDraft.Field("category", enumOrString(categories),
                    Requiredness.OPTIONAL));
            elements.add(stringField("description"));
            for (AttachmentRule rule : AttachmentRule.values()) {
                elements.add(ruleFor(rule, german, locales));
            }
            return new ElementDraft.Group(groupName, repeatability, null, elements, "attachment",
                    GroupMeta.builder().annotations(annotations).build());
        }

        private static ElementDraft.Field stringField(String name) {
            return new ElementDraft.Field(name, new FieldTypeSpec.StringSpec(null, null, null, null),
                    Requiredness.OPTIONAL);
        }

        /** The kernel requires {@code content} to be a String that skips value validation and permits line breaks. */
        private static ElementDraft.Field contentField() {
            FieldTypeSpec spec = new FieldTypeSpec.StringSpec(null, null, null, null, true, false, Map.of(), true);
            return new ElementDraft.Field("content", spec, Requiredness.OPTIONAL);
        }

        private ElementDraft.Field sizeField() {
            BigDecimal max = maxSizeBytes == null ? null : BigDecimal.valueOf(maxSizeBytes);
            return new ElementDraft.Field("size", FieldTypeSpec.NumberSpec.builder()
                    .maxFractionalDigits(0).maxValue(max).build(),
                    Requiredness.OPTIONAL);
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
        // Imports and mounted includes both contribute to the assembly-wide type-definition namespace. Without
        // the referenced sources this draft knows only the local subset, so the resolver-backed gate owns the check.
        if (!typeDefImports.isEmpty() || !includes.isEmpty()) {
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
            case NUMBER -> FieldTypeSpec.NumberSpec.builder().build();
            case DATE -> new FieldTypeSpec.DateSpec(null, "FULL");
            case BOOLEAN -> new FieldTypeSpec.BooleanSpec(null, null);
            case CONFIRM -> new FieldTypeSpec.ConfirmSpec(null, null);
        };
    }

}
