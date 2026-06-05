package io.github.mbackschat.a12.rulekit.structure;

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

    /** Kernel model-version stamp written into the header (matches the fixtures' authored value). */
    private static final String MODEL_VERSION = "28.4.0";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final List<String> locales;
    private final List<TypeDefDecl> typeDefs;
    private final List<ElementDraft.Group> rootGroups;

    private DocumentModelDraft(String id, List<String> locales, List<TypeDefDecl> typeDefs,
            List<ElementDraft.Group> rootGroups) {
        this.id = id;
        this.locales = locales;
        this.typeDefs = typeDefs;
        this.rootGroups = rootGroups;
    }

    /** A reusable named type, declared once in {@code content.typeDefinitions} and referenced by fields. */
    private record TypeDefDecl(String id, FieldTypeSpec type) {}

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
        header.putArray("labels");
        header.putArray("annotations");
        header.putArray("modelReferences");

        ObjectNode content = root.putObject("content");
        ObjectNode info = content.putObject("modelInfo");
        info.put("name", id);
        info.put("variant", "cockpit");
        info.put("revision", "Arbeitsversion");
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

    private static ObjectNode groupNode(ElementDraft.Group group, String parentPath, List<String> locales) {
        String path = parentPath + "/" + group.name();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Group");
        node.put("id", path);
        node.put("name", group.name());
        ObjectNode body = node.putObject("Group");
        body.put("repeatability", group.repeatability());
        ArrayNode elements = body.putArray("elements");
        for (ElementDraft element : group.elements()) {
            elements.add(switch (element) {
                case ElementDraft.Group sub -> groupNode(sub, path, locales);
                case ElementDraft.Field field -> fieldNode(field, path, locales);
            });
        }
        return node;
    }

    private static ObjectNode fieldNode(ElementDraft.Field field, String parentPath, List<String> locales) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Field");
        node.put("id", parentPath + "/" + field.name());
        node.put("name", field.name());
        ObjectNode body = node.putObject("Field");
        writeFieldType(body.putObject("fieldType"), field.type(), locales);
        String mode = field.requiredness().mode();
        if (mode != null) {
            body.putObject("requirednessConfig").put("mode", mode);
        }
        return node;
    }

    /** Write the {@code type} discriminator plus its inner config block. */
    private static void writeFieldType(ObjectNode fieldType, FieldTypeSpec spec, List<String> locales) {
        fieldType.put("type", spec.jsonType());
        writeTypeConfig(fieldType, spec, locales);
    }

    /** Write the kernel {@code fieldType.<Type>} inner block for the configured slots (omitted when empty). */
    private static void writeTypeConfig(ObjectNode fieldType, FieldTypeSpec spec, List<String> locales) {
        switch (spec) {
            case FieldTypeSpec.StringSpec s -> {
                if (s.minLength() != null || s.maxLength() != null || s.pattern() != null) {
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
                }
            }
            case FieldTypeSpec.NumberSpec n -> {
                ObjectNode inner = fieldType.putObject("NumberType");
                inner.put("maxFractionalDigits", n.fractionDigits() != null ? n.fractionDigits() : 0);
                if (n.minValue() != null) {
                    inner.put("minValue", n.minValue());
                }
                if (n.maxValue() != null) {
                    inner.put("maxValue", n.maxValue());
                }
            }
            case FieldTypeSpec.DateSpec d ->
                    fieldType.putObject("DateType").put("format", d.format() != null ? d.format() : "yyyy-MM-dd");
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
                ArrayNode values = fieldType.putObject("EnumerationType").putArray("values");
                for (FieldTypeSpec.EnumValue v : e.values()) {
                    ObjectNode valueNode = values.addObject();
                    valueNode.put("value", v.value());
                    if (!v.labels().isEmpty()) {
                        ArrayNode labels = valueNode.putArray("label");
                        locales.forEach(loc -> labels.addObject().put("locale", loc).put("text", v.labels().get(loc)));
                    }
                }
            }
        }
    }

    /** Fluent builder for a {@link DocumentModelDraft}. */
    public static final class Builder {

        private final String id;
        private final List<String> locales = new ArrayList<>();
        private final List<TypeDefDecl> typeDefs = new ArrayList<>();
        private final List<ElementDraft.Group> rootGroups = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        /** Add a declared locale (e.g. {@code "en_US"}); a full {@code language_COUNTRY} code (KERNEL-FINDINGS §13). */
        public Builder locale(String code) {
            locales.add(code);
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
            return new DocumentModelDraft(id, List.copyOf(locales), List.copyOf(typeDefs), List.copyOf(rootGroups));
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

        /** Add a bare, optional field of the given easy kind. */
        public GroupBuilder field(String name, FieldKind kind) {
            return field(name, kind, Requiredness.OPTIONAL);
        }

        /** Add a bare field of the given easy kind with explicit requiredness. */
        public GroupBuilder field(String name, FieldKind kind, Requiredness requiredness) {
            elements.add(new ElementDraft.Field(name, bareSpec(kind), requiredness));
            return this;
        }

        /** Add a string field with optional length/pattern config and requiredness. */
        public GroupBuilder stringField(String name, Consumer<StringFieldBuilder> body) {
            StringFieldBuilder b = new StringFieldBuilder();
            body.accept(b);
            elements.add(new ElementDraft.Field(name, b.spec(), b.requiredness));
            return this;
        }

        /** Add a number field with optional scale/range config and requiredness. */
        public GroupBuilder numberField(String name, Consumer<NumberFieldBuilder> body) {
            NumberFieldBuilder b = new NumberFieldBuilder();
            body.accept(b);
            elements.add(new ElementDraft.Field(name, b.spec(), b.requiredness));
            return this;
        }

        /** Add an enumeration field; needs at least one value. */
        public GroupBuilder enumField(String name, Consumer<EnumFieldBuilder> body) {
            EnumFieldBuilder b = new EnumFieldBuilder();
            body.accept(b);
            elements.add(new ElementDraft.Field(name, b.spec(), b.requiredness));
            return this;
        }

        /** Add a date field with an explicit constant format and requiredness. */
        public GroupBuilder dateField(String name, Consumer<DateFieldBuilder> body) {
            DateFieldBuilder b = new DateFieldBuilder();
            body.accept(b);
            elements.add(new ElementDraft.Field(name, b.spec(), b.requiredness));
            return this;
        }

        /** Add a date-range field (format + range separator, both required by the kernel). */
        public GroupBuilder dateRangeField(String name, Consumer<DateRangeFieldBuilder> body) {
            DateRangeFieldBuilder b = new DateRangeFieldBuilder();
            body.accept(b);
            elements.add(new ElementDraft.Field(name, b.spec(), b.requiredness));
            return this;
        }

        /** Add a date-fragment field (e.g. year-month) with its fragment format. */
        public GroupBuilder dateFragmentField(String name, Consumer<DateFragmentFieldBuilder> body) {
            DateFragmentFieldBuilder b = new DateFragmentFieldBuilder();
            body.accept(b);
            elements.add(new ElementDraft.Field(name, b.spec(), b.requiredness));
            return this;
        }

        /** Add a field whose type is a reusable {@code typeDef} (referenced by id), optional. */
        public GroupBuilder typedefField(String name, String typeDefId) {
            return typedefField(name, typeDefId, Requiredness.OPTIONAL);
        }

        /** Add a field whose type is a reusable {@code typeDef} (referenced by id), with requiredness. */
        public GroupBuilder typedefField(String name, String typeDefId, Requiredness requiredness) {
            elements.add(new ElementDraft.Field(name, new FieldTypeSpec.TypeDefRef(typeDefId), requiredness));
            return this;
        }

        /** Add a nested group, populated by {@code body}. */
        public GroupBuilder group(String name, Consumer<GroupBuilder> body) {
            GroupBuilder child = new GroupBuilder(name);
            body.accept(child);
            elements.add(child.toDraft());
            return this;
        }

        private ElementDraft.Group toDraft() {
            return new ElementDraft.Group(name, repeatability, List.copyOf(elements));
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
            }
        }
    }

    private static FieldTypeSpec bareSpec(FieldKind kind) {
        return switch (kind) {
            case STRING -> new FieldTypeSpec.StringSpec(null, null, null, null);
            case NUMBER -> new FieldTypeSpec.NumberSpec(null, null, null);
            case DATE -> new FieldTypeSpec.DateSpec(null);
            case BOOLEAN -> new FieldTypeSpec.BooleanSpec();
            case CONFIRM -> new FieldTypeSpec.ConfirmSpec();
        };
    }

    /** Configures a string field. Cross-kind nonsense (a numeric range) is simply not on this surface. */
    public static final class StringFieldBuilder {
        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        private String patternMessage;
        private Requiredness requiredness = Requiredness.OPTIONAL;

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

        public StringFieldBuilder required() {
            this.requiredness = Requiredness.REQUIRED;
            return this;
        }

        public StringFieldBuilder requiredIfParentPresent() {
            this.requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.StringSpec(minLength, maxLength, pattern, patternMessage);
        }
    }

    /** Configures a number field (scale = fraction digits, optional value range). */
    public static final class NumberFieldBuilder {
        private Integer fractionDigits;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private Requiredness requiredness = Requiredness.OPTIONAL;

        public NumberFieldBuilder fractionDigits(int n) {
            this.fractionDigits = n;
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

        public NumberFieldBuilder required() {
            this.requiredness = Requiredness.REQUIRED;
            return this;
        }

        public NumberFieldBuilder requiredIfParentPresent() {
            this.requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.NumberSpec(fractionDigits, minValue, maxValue);
        }
    }

    /** Configures an enumeration field — stored values, each with optional per-locale labels. */
    public static final class EnumFieldBuilder {
        private final List<FieldTypeSpec.EnumValue> values = new ArrayList<>();
        private Requiredness requiredness = Requiredness.OPTIONAL;

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

        public EnumFieldBuilder required() {
            this.requiredness = Requiredness.REQUIRED;
            return this;
        }

        public EnumFieldBuilder requiredIfParentPresent() {
            this.requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
            return this;
        }

        private FieldTypeSpec spec() {
            if (values.isEmpty()) {
                throw new IllegalStateException("an enumeration field needs at least one value");
            }
            return new FieldTypeSpec.EnumSpec(values);
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

    /** Configures a date field's constant format and requiredness. */
    public static final class DateFieldBuilder {
        private String format;
        private Requiredness requiredness = Requiredness.OPTIONAL;

        public DateFieldBuilder format(String format) {
            this.format = format;
            return this;
        }

        public DateFieldBuilder required() {
            this.requiredness = Requiredness.REQUIRED;
            return this;
        }

        public DateFieldBuilder requiredIfParentPresent() {
            this.requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
            return this;
        }

        private FieldTypeSpec spec() {
            return new FieldTypeSpec.DateSpec(format);
        }
    }

    /** Configures a date-range field — both a format and a range separator are required by the kernel. */
    public static final class DateRangeFieldBuilder {
        private String format;
        private String separator;
        private Requiredness requiredness = Requiredness.OPTIONAL;

        public DateRangeFieldBuilder format(String format) {
            this.format = format;
            return this;
        }

        public DateRangeFieldBuilder separator(String separator) {
            this.separator = separator;
            return this;
        }

        public DateRangeFieldBuilder required() {
            this.requiredness = Requiredness.REQUIRED;
            return this;
        }

        public DateRangeFieldBuilder requiredIfParentPresent() {
            this.requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
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
    public static final class DateFragmentFieldBuilder {
        private String fragmentFormat;
        private Requiredness requiredness = Requiredness.OPTIONAL;

        public DateFragmentFieldBuilder fragmentFormat(String format) {
            this.fragmentFormat = format;
            return this;
        }

        public DateFragmentFieldBuilder required() {
            this.requiredness = Requiredness.REQUIRED;
            return this;
        }

        public DateFragmentFieldBuilder requiredIfParentPresent() {
            this.requiredness = Requiredness.REQUIRED_IF_PARENT_PRESENT;
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
