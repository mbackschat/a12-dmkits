package io.github.mbackschat.a12.dm.modelkit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * modelkit M1 — a typed builder for document-model <b>structure</b>, the peer of rulekit's rule
 * builder (fork #8 / ARCHITECTURE §5.2; rationale in {@code docs/MODELKIT-PROPOSAL.md}). Author a
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final List<String> locales;
    private final List<Include> includes;
    private final List<TypeDefDecl> typeDefs;
    private final List<ElementDraft.Group> rootGroups;
    private final Integer baseYear;
    private final Map<String, String> labels;
    private final List<ElementDraft.Annotation> annotations;

    private DocumentModelDraft(String id, List<String> locales, List<Include> includes,
            List<TypeDefDecl> typeDefs, List<ElementDraft.Group> rootGroups, Integer baseYear,
            Map<String, String> labels, List<ElementDraft.Annotation> annotations) {
        this.id = id;
        this.locales = locales;
        this.includes = includes;
        this.typeDefs = typeDefs;
        this.rootGroups = rootGroups;
        this.baseYear = baseYear;
        this.labels = labels;
        this.annotations = annotations;
    }

    /** A reusable named type, declared once in {@code content.typeDefinitions} and referenced by fields. */
    private record TypeDefDecl(String id, FieldTypeSpec type) {}

    /** A header include: {@code alias} (used by mount groups) → {@code reference} (the included model id). */
    private record Include(String alias, String reference) {}

    /** Start a draft for the model with this id (also its info name). */
    public static Builder named(String id) {
        return new Builder(id);
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

        ObjectNode content = root.putObject("content");
        ObjectNode info = content.putObject("modelInfo");
        info.put("name", id);
        info.put("variant", "cockpit");
        info.put("revision", "Arbeitsversion");
        if (baseYear != null) {
            // the model-level base year (handles year-less date inputs and enables the BaseYear point-in-time)
            info.put("baseYear", String.valueOf(baseYear));
        }
        info.put("immutable", false);
        ObjectNode config = content.putObject("modelConfig");
        config.put("decimalSeparator", ".");
        config.put("timeZone", "Europe/Berlin");
        config.putObject("conditionLanguage").put("code", locales.get(0));
        config.put("fieldRefByShortNameAllowed", true);

        checkTypeDefReferences();
        if (!typeDefs.isEmpty()) {
            ArrayNode defs = content.putArray("typeDefinitions");
            for (TypeDefDecl def : typeDefs) {
                ObjectNode node = defs.addObject();
                node.put("id", def.id());
                node.put("name", def.id());
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
        ObjectNode body = node.putObject("Group");
        body.put("repeatability", group.repeatability());
        if (group.indexFieldName() != null) {
            body.put("indexFieldName", group.indexFieldName());
        }
        ArrayNode elements = body.putArray("elements");
        for (ElementDraft element : group.elements()) {
            elements.add(switch (element) {
                case ElementDraft.Group sub -> groupNode(sub, path, locales);
                case ElementDraft.Field field -> fieldNode(field, path, locales);
                case ElementDraft.Mount mount -> mountNode(mount, path);
            });
        }
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
        body.put("repeatability", 1);
        body.put("modelAlias", mount.alias());
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
            // a label, once present, must cover every declared locale (same rule as enum labels)
            for (String loc : locales) {
                if (!field.labels().containsKey(loc)) {
                    throw new IllegalStateException("field '" + field.name()
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
                        || s.lineBreaksPermitted() || s.alphabeticalSorting() || !s.suggestions().isEmpty()) {
                    ObjectNode inner = fieldType.putObject("StringType");
                    // a pattern needs an error text for every declared locale (MVK_ERROR_MESSAGE_AND_PATTERN_INVALID)
                    if (s.pattern() != null) {
                        ArrayNode messages = inner.putArray("errorMessage");
                        locales.forEach(loc -> messages.addObject().put("locale", loc).put("text", s.patternMessage()));
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
                                throw new IllegalStateException("enum value '" + v.value()
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
                        ArrayNode catValues = categoryNode.putArray("values");
                        c.values().forEach(catValues::add);
                    }
                }
                if (e.alphabeticalSorting()) {
                    enumeration.put("alphabeticalSorting", true);
                }
                if (e.invalidValueMessage() != null) {
                    // the invalid-value error text, one entry per declared locale (the kernel's localized map)
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

    /** Fluent builder for a {@link DocumentModelDraft}. */
    public static final class Builder {

        private final String id;
        private final List<String> locales = new ArrayList<>();
        private final List<Include> includes = new ArrayList<>();
        private final List<TypeDefDecl> typeDefs = new ArrayList<>();
        private final List<ElementDraft.Group> rootGroups = new ArrayList<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final List<ElementDraft.Annotation> annotations = new ArrayList<>();
        private Integer baseYear;

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

        /** Declare an include: {@code alias} (used by {@code mount(name, alias)}) → {@code reference} (the included model id). */
        public Builder include(String alias, String reference) {
            includes.add(new Include(alias, reference));
            return this;
        }

        /** Declare a reusable named type; reference it from fields with {@code typedefField(name, id)}. */
        public Builder typeDef(String id, Consumer<TypeDefBuilder> body) {
            TypeDefBuilder b = new TypeDefBuilder();
            body.accept(b);
            typeDefs.add(new TypeDefDecl(id, b.spec()));
            return this;
        }

        /** Add a top-level group, populated by {@code body}. */
        public Builder rootGroup(String name, Consumer<GroupBuilder> body) {
            GroupBuilder group = new GroupBuilder(name);
            body.accept(group);
            rootGroups.add(group.toDraft());
            return this;
        }

        /** Build the immutable draft, rejecting models that can't be valid (no locale / no group). */
        public DocumentModelDraft build() {
            if (locales.isEmpty()) {
                throw new IllegalStateException("a model needs at least one locale");
            }
            if (rootGroups.isEmpty()) {
                throw new IllegalStateException("a model needs at least one root group");
            }
            return new DocumentModelDraft(id, List.copyOf(locales), List.copyOf(includes),
                    List.copyOf(typeDefs), List.copyOf(rootGroups), baseYear,
                    Map.copyOf(labels), List.copyOf(annotations));
        }
    }

    /** Picks the single underlying type of a reusable type definition (config reused from the field builders). */
    public static final class TypeDefBuilder {

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

    /** Fluent builder for a group and its contents. */
    public static final class GroupBuilder {

        private final String name;
        private int repeatability = 1;
        private String indexFieldName;
        private final List<ElementDraft> elements = new ArrayList<>();
        private final List<ElementDraft.Annotation> annotations = new ArrayList<>();

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

        /** A name/value annotation on this group element. */
        public GroupBuilder annotation(String name, String value) {
            annotations.add(new ElementDraft.Annotation(name, value));
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
            return addField(name, b, b.spec());
        }

        /** Add a number field with optional scale/range config, requiredness, labels, annotations. */
        public GroupBuilder numberField(String name, Consumer<NumberFieldBuilder> body) {
            NumberFieldBuilder b = new NumberFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
        }

        /** Add an enumeration field; needs at least one value. */
        public GroupBuilder enumField(String name, Consumer<EnumFieldBuilder> body) {
            EnumFieldBuilder b = new EnumFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
        }

        /** Add a date field with an explicit constant format. */
        public GroupBuilder dateField(String name, Consumer<DateFieldBuilder> body) {
            DateFieldBuilder b = new DateFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
        }

        /** Add a time-of-day field with an explicit constant format. */
        public GroupBuilder timeField(String name, Consumer<TimeFieldBuilder> body) {
            TimeFieldBuilder b = new TimeFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
        }

        /** Add a date-time field with an explicit constant format. */
        public GroupBuilder dateTimeField(String name, Consumer<DateTimeFieldBuilder> body) {
            DateTimeFieldBuilder b = new DateTimeFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
        }

        /** Add a date-range field (format + range separator, both required by the kernel). */
        public GroupBuilder dateRangeField(String name, Consumer<DateRangeFieldBuilder> body) {
            DateRangeFieldBuilder b = new DateRangeFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
        }

        /** Add a date-fragment field (e.g. year-month) with its fragment format. */
        public GroupBuilder dateFragmentField(String name, Consumer<DateFragmentFieldBuilder> body) {
            DateFragmentFieldBuilder b = new DateFragmentFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
        }

        /** Add a custom-typed field (a named kernel custom type + optional length bounds). */
        public GroupBuilder customField(String name, Consumer<CustomFieldBuilder> body) {
            CustomFieldBuilder b = new CustomFieldBuilder();
            body.accept(b);
            return addField(name, b, b.spec());
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

        /** Mount an included model here: a group named {@code name} that mounts the include {@code alias}. */
        public GroupBuilder mount(String name, String alias) {
            elements.add(new ElementDraft.Mount(name, alias, List.of()));
            return this;
        }

        private GroupBuilder addField(String name, FieldAttrs<?> attrs, FieldTypeSpec spec) {
            elements.add(new ElementDraft.Field(name, spec, attrs.requiredness,
                    Map.copyOf(attrs.labels), List.copyOf(attrs.annotations),
                    attrs.global, attrs.notPersisted, Map.copyOf(attrs.internalDescription),
                    Map.copyOf(attrs.externalDescription), Map.copyOf(attrs.helperText)));
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
                    List.copyOf(elements), List.copyOf(annotations));
        }
    }

    /** Referential integrity: every {@code typedefField} must point at a declared {@code typeDef}. */
    private void checkTypeDefReferences() {
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

    /**
     * Field-element attributes shared by every field builder — {@code required(...)}, per-locale
     * {@code label(...)}, and name/value {@code annotation(...)} — so they're written once and apply
     * uniformly. The self-type {@code SELF} keeps the fluent chain in the concrete builder's type.
     */
    public abstract static class FieldAttrs<SELF extends FieldAttrs<SELF>> {
        Requiredness requiredness = Requiredness.OPTIONAL;
        final Map<String, String> labels = new LinkedHashMap<>();
        final List<ElementDraft.Annotation> annotations = new ArrayList<>();
        boolean global;
        boolean notPersisted;
        final Map<String, String> internalDescription = new LinkedHashMap<>();
        final Map<String, String> externalDescription = new LinkedHashMap<>();
        final Map<String, String> helperText = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        private SELF self() {
            return (SELF) this;
        }

        public SELF required() {
            requiredness = Requiredness.REQUIRED;
            return self();
        }

        public SELF requiredIfParentPresent() {
            requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
            return self();
        }

        /** A per-locale display label; once any locale is set, all declared locales are required. */
        public SELF label(String locale, String text) {
            labels.put(locale, text);
            return self();
        }

        /** A name/value annotation on the field element. */
        public SELF annotation(String name, String value) {
            annotations.add(new ElementDraft.Annotation(name, value));
            return self();
        }

        /** Mark the field {@code global} — passed to partial validation (a kernel field flag, §3.5). */
        public SELF global() {
            this.global = true;
            return self();
        }

        /** Mark the field {@code transient} — present at runtime but not persisted (a kernel field flag, §3.5). */
        public SELF notPersisted() {
            this.notPersisted = true;
            return self();
        }

        /** Per-locale internal description (authoring/maintenance text, distinct from the label). */
        public SELF internalDescription(String locale, String text) {
            internalDescription.put(locale, text);
            return self();
        }

        /** Per-locale external description (end-user-facing text, distinct from the label). */
        public SELF externalDescription(String locale, String text) {
            externalDescription.put(locale, text);
            return self();
        }

        /** Per-locale helper text (inline UI help shown alongside the field). */
        public SELF helperText(String locale, String text) {
            helperText.put(locale, text);
            return self();
        }
    }

    /** Configures a string field. Cross-kind nonsense (a numeric range) is simply not on this surface. */
    public static final class StringFieldBuilder extends FieldAttrs<StringFieldBuilder> {
        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        private String patternMessage;
        private boolean lineBreaksPermitted;
        private boolean alphabeticalSorting;
        private final Map<String, List<String>> suggestions = new LinkedHashMap<>();

        public StringFieldBuilder minLength(int n) {
            this.minLength = n;
            return this;
        }

        public StringFieldBuilder maxLength(int n) {
            this.maxLength = n;
            return this;
        }

        /** Constrain to a pattern; the kernel requires an error {@code message} alongside it (§7). */
        public StringFieldBuilder pattern(String regex, String message) {
            this.pattern = regex;
            this.patternMessage = message;
            return this;
        }

        /** Permit multi-line input (the kernel forbids line breaks by default). */
        public StringFieldBuilder lineBreaks() {
            this.lineBreaksPermitted = true;
            return this;
        }

        /** Present the field's values sorted alphabetically (a UI hint the kernel records). */
        public StringFieldBuilder alphabeticalSorting() {
            this.alphabeticalSorting = true;
            return this;
        }

        /** Add localized input suggestions (a hint list) for one locale; validated against the field's own pattern. */
        public StringFieldBuilder suggestions(String locale, String... values) {
            this.suggestions.put(locale, List.of(values));
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.StringSpec(minLength, maxLength, pattern, patternMessage,
                    lineBreaksPermitted, alphabeticalSorting, suggestions);
        }
    }

    /** Configures a number field (decimal-place range, optional value range, zero/leading-zero flags). */
    public static final class NumberFieldBuilder extends FieldAttrs<NumberFieldBuilder> {
        private Integer minFractionalDigits;
        private Integer maxFractionalDigits;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private boolean zeroNotAllowed;
        private boolean leadingZerosAllowed;

        /** Fix the maximum scale (0..{@code n} decimal places); the minimum stays 0. */
        public NumberFieldBuilder fractionDigits(int n) {
            this.maxFractionalDigits = n;
            return this;
        }

        /** Bound the scale to a {@code [min, max]} range of decimal places (FEATURE-GAPS §3.4). */
        public NumberFieldBuilder fractionDigits(int min, int max) {
            this.minFractionalDigits = min;
            this.maxFractionalDigits = max;
            return this;
        }

        public NumberFieldBuilder min(String value) {
            this.minValue = new BigDecimal(value);
            return this;
        }

        public NumberFieldBuilder max(String value) {
            this.maxValue = new BigDecimal(value);
            return this;
        }

        /** Reject the value 0 (the kernel emits a dedicated zero-not-allowed error). */
        public NumberFieldBuilder zeroNotAllowed() {
            this.zeroNotAllowed = true;
            return this;
        }

        /** Permit leading zeros in the input (the kernel strips them by default). */
        public NumberFieldBuilder leadingZerosAllowed() {
            this.leadingZerosAllowed = true;
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.NumberSpec(minFractionalDigits, maxFractionalDigits, minValue, maxValue,
                    zeroNotAllowed, leadingZerosAllowed);
        }
    }

    /**
     * Configures an enumeration field — stored values (each with optional per-locale labels) and
     * optional {@code categories} that classify those values positionally.
     */
    public static final class EnumFieldBuilder extends FieldAttrs<EnumFieldBuilder> {
        private final List<FieldTypeSpec.EnumValue> values = new ArrayList<>();
        private final List<FieldTypeSpec.EnumCategory> categories = new ArrayList<>();
        private boolean alphabeticalSorting;
        private String invalidValueMessage;

        public EnumFieldBuilder value(String value) {
            values.add(new FieldTypeSpec.EnumValue(value, Map.of()));
            return this;
        }

        public EnumFieldBuilder value(String value, Consumer<LabelBuilder> labels) {
            LabelBuilder lb = new LabelBuilder();
            labels.accept(lb);
            values.add(new FieldTypeSpec.EnumValue(value, lb.labels));
            return this;
        }

        /**
         * Add a named classification: one category value per enum value, in the order the values were
         * added ({@code classifications[i]} classifies value {@code i}). Length is checked at build.
         */
        public EnumFieldBuilder category(String name, String... classifications) {
            categories.add(new FieldTypeSpec.EnumCategory(name, List.of(classifications)));
            return this;
        }

        /** Present the values sorted alphabetically rather than in declared order (a UI hint the kernel records). */
        public EnumFieldBuilder alphabeticalSorting() {
            this.alphabeticalSorting = true;
            return this;
        }

        /** The error text shown when an out-of-domain value is supplied (one text, used for every declared locale). */
        public EnumFieldBuilder invalidValueMessage(String text) {
            this.invalidValueMessage = text;
            return this;
        }

        private FieldTypeSpec spec() {
            if (values.isEmpty()) {
                throw new IllegalStateException("an enumeration field needs at least one value");
            }
            return new FieldTypeSpec.EnumSpec(values, categories, alphabeticalSorting, invalidValueMessage);
        }
    }

    /** Configures a custom-typed field: a named kernel custom type with optional length bounds (FEATURE-GAPS §3.4). */
    public static final class CustomFieldBuilder extends FieldAttrs<CustomFieldBuilder> {
        private String typeName;
        private Integer minLength;
        private Integer maxLength;

        /** The technical name of the registered kernel custom field type (required). */
        public CustomFieldBuilder type(String name) {
            this.typeName = name;
            return this;
        }

        public CustomFieldBuilder minLength(int n) {
            this.minLength = n;
            return this;
        }

        public CustomFieldBuilder maxLength(int n) {
            this.maxLength = n;
            return this;
        }

        private FieldTypeSpec spec() {
            if (typeName == null) {
                throw new IllegalStateException("a custom field needs a type name — call .type(\"…\")");
            }
            return new FieldTypeSpec.CustomSpec(typeName, minLength, maxLength);
        }
    }

    /** Collects per-locale display labels for one enumeration value. */
    public static final class LabelBuilder {
        private final Map<String, String> labels = new LinkedHashMap<>();

        public LabelBuilder label(String locale, String text) {
            labels.put(locale, text);
            return this;
        }
    }

    /** Configures a date field's constant format. */
    public static final class DateFieldBuilder extends FieldAttrs<DateFieldBuilder> {
        private String format;
        private DatePrecision datePrecision = DatePrecision.FULL;

        public DateFieldBuilder format(String format) {
            this.format = format;
            return this;
        }

        /**
         * The date precision. Any value other than {@link DatePrecision#FULL} makes this a
         * <em>partially known date</em> (a day/month/year part may be unknown) — the field
         * {@code ValueAsDate(field, FirstDay|LastDay)} then resolves it to a concrete date.
         */
        public DateFieldBuilder datePrecision(DatePrecision precision) {
            this.datePrecision = precision;
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.DateSpec(format, datePrecision.jsonValue());
        }
    }

    /** Configures a time-of-day field's constant format. */
    public static final class TimeFieldBuilder extends FieldAttrs<TimeFieldBuilder> {
        private String format;

        public TimeFieldBuilder format(String format) {
            this.format = format;
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.TimeSpec(format);
        }
    }

    /** Configures a date-time field's constant format. */
    public static final class DateTimeFieldBuilder extends FieldAttrs<DateTimeFieldBuilder> {
        private String format;

        public DateTimeFieldBuilder format(String format) {
            this.format = format;
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.DateTimeSpec(format);
        }
    }

    /** Configures a date-range field — both a format and a range separator are required by the kernel. */
    public static final class DateRangeFieldBuilder extends FieldAttrs<DateRangeFieldBuilder> {
        private String format;
        private String separator;

        public DateRangeFieldBuilder format(String format) {
            this.format = format;
            return this;
        }

        public DateRangeFieldBuilder separator(String separator) {
            this.separator = separator;
            return this;
        }

        private FieldTypeSpec spec() {
            if (format == null || separator == null) {
                throw new IllegalStateException("a date range needs both a format and a range separator");
            }
            return new FieldTypeSpec.DateRangeSpec(format, separator);
        }
    }

    /** Configures a date-fragment field (e.g. year-month) — the fragment format is required. */
    public static final class DateFragmentFieldBuilder extends FieldAttrs<DateFragmentFieldBuilder> {
        private String fragmentFormat;

        public DateFragmentFieldBuilder fragmentFormat(String format) {
            this.fragmentFormat = format;
            return this;
        }

        private FieldTypeSpec spec() {
            if (fragmentFormat == null) {
                throw new IllegalStateException("a date fragment needs a fragment format");
            }
            return new FieldTypeSpec.DateFragmentSpec(fragmentFormat);
        }
    }
}
