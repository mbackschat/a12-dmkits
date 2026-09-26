package io.github.mbackschat.a12.dm.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A queryable, <b>kernel-free</b> index over a fully-expanded DocumentModel — the foundation everything else
 * binds to (PROJECT-DESIGN §5a / F0). Maps each element's absolute <b>name-path</b> to its model node so the
 * AST, renderer, and pre-checks can resolve paths, operand kinds, and repetition structure.
 *
 * <p>The typed view over the {@link ModelReader} (kernel-decoupling Stage 2): the reader parses, expands
 * includes, resolves imported type-definitions to effective concrete types, and indexes by name-path — all
 * without the kernel; this class projects that into rulekit's {@link ValueKind}/config vocabulary. The kernel
 * still runs the consistency gate ({@code checkConsistency}, R3) behind {@link ModelLoader#load} — a caller
 * that needs both binds through {@code BoundModel.ofJson}, which gates <em>and</em> indexes.
 *
 * <p>Lookups fail fast with {@link IllegalArgumentException} on an unknown path or a path that resolves to the
 * wrong kind of node.
 */
public final class ModelIndex {

    enum EnumProjectionStep {
        CATEGORY_MATERIALIZATION
    }

    private static final Consumer<EnumProjectionStep> NO_ENUM_PROJECTION_PROBE = ignored -> {};

    private final ModelReader reader;
    private final Consumer<EnumProjectionStep> enumProjectionProbe;

    private ModelIndex(ModelReader reader) {
        this(reader, NO_ENUM_PROJECTION_PROBE);
    }

    ModelIndex(ModelReader reader, Consumer<EnumProjectionStep> enumProjectionProbe) {
        this.reader = reader;
        this.enumProjectionProbe = Objects.requireNonNull(enumProjectionProbe, "enumProjectionProbe");
    }

    /** Index a model from DM-JSON, expanding includes / imported type-definitions against {@code resolver}. */
    public static ModelIndex of(String dmJson, ModelResolver resolver) {
        return new ModelIndex(ModelReader.expand(dmJson, resolver));
    }

    /** Index a <em>self-contained</em> model (no includes / imported type-definitions). */
    public static ModelIndex ofSelfContained(String dmJson) {
        return of(dmJson, ReferenceResolvers.selfContained());
    }

    /** Index an already-expanded {@link ModelReader}. */
    public static ModelIndex of(ModelReader reader) {
        return new ModelIndex(reader);
    }

    /**
     * Resolve an absolute or relative element reference against an element path. A sibling reference such as
     * {@code ../Field} resolves from the rule/computation element, while an absolute path is unchanged.
     */
    public static String resolvePath(String elementPath, String reference) {
        return ModelReader.resolveRelPath(elementPath, reference);
    }

    /**
     * The model's declared locales (header {@code locales}, e.g. {@code en_US}) — any valid Java locale code is
     * possible, EN/DE are <em>not</em> special here. Error messages must cover every declared locale
     * ({@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING}); rulekit additionally rejects messages for
     * <em>undeclared</em> locales (the kernel tolerates them silently, which usually hides a typo or dead text).
     */
    public List<String> locales() {
        return reader.locales();
    }

    /**
     * The model's declared base year, or empty when it declares none. Model-level state a temporal GATE reads,
     * not only an arithmetic constant: the entity-list aggregates add YEAR to every operand's component set
     * before comparing them when one is declared (KERNEL-FINDINGS KF193), which only ever admits more — so a
     * check that cannot see this refuses pairs the kernel accepts.
     */
    public Optional<Integer> baseYear() {
        return reader.baseYear();
    }

    /** The {@link ValueKind} of the field at {@code fieldPath} (type-definitions resolved). */
    public ValueKind kindOf(String fieldPath) {
        return ValueKind.fromKernelType(fieldTypeNode(fieldPath).path(DmKeys.TYPE).asText(null));
    }

    /**
     * The declared decimal scale (max fractional digits) of the number field at {@code fieldPath}.
     *
     * @throws IllegalArgumentException if the field is not a number
     */
    public int numberScale(String fieldPath) {
        JsonNode number = numberTypeOf(fieldPath);
        if (number == null) {
            throw new IllegalArgumentException(
                    fieldPath + " is not a number field (kind=" + kindOf(fieldPath) + ")");
        }
        // NumberType.maxFractionalDigits is a primitive int defaulting to 0 (kernel) — a NumberType that omits
        // it is scale-0 (an integer).
        return number.path("maxFractionalDigits").asInt(0);
    }

    /**
     * The declared inclusive lower bound of the number field at {@code fieldPath} ({@code minValue}), or empty
     * when the field is not a number or no bound is declared. A read-only config accessor (no throw on a
     * non-number, so callers can probe any field) — see {@link #numberMax}/{@link #stringMinLength}/etc.
     */
    public Optional<BigDecimal> numberMin(String fieldPath) {
        return numberBound(fieldPath, "minValue");
    }

    /** The declared inclusive upper bound ({@code maxValue}) of the number field, or empty (see {@link #numberMin}). */
    public Optional<BigDecimal> numberMax(String fieldPath) {
        return numberBound(fieldPath, "maxValue");
    }

    /**
     * Whether the number field at {@code fieldPath} declares itself UNSIGNED (A12's {@code positivesOnly}).
     * {@code false} when the field is not a number or the key is absent, which is A12's own default — the
     * kernel reads the same fact inverted, as {@code hasSign()}.
     *
     * <p>Needed beyond input validation because a date construction's component satisfies the kernel's
     * low-side requirement either by declaring a non-negative minimum OR by being unsigned, so a gate that
     * reads only the minimum refuses legal models (KERNEL-FINDINGS §6).
     */
    public boolean numberPositivesOnly(String fieldPath) {
        JsonNode number = numberTypeOf(fieldPath);
        return number != null && number.path("positivesOnly").asBoolean(false);
    }

    /** The declared {@code maxLength} of the number field, or empty when not a number / unset. */
    public OptionalInt numberMaxLength(String fieldPath) {
        JsonNode number = numberTypeOf(fieldPath);
        if (number == null) {
            return OptionalInt.empty();
        }
        JsonNode value = number.path("maxLength");
        return value.isMissingNode() || value.isNull()
                ? OptionalInt.empty() : OptionalInt.of(value.asInt());
    }

    /** The declared {@code minLength} of the string field, or empty when not a string / unset. */
    public OptionalInt stringMinLength(String fieldPath) {
        return stringInt(fieldPath, "minLength");
    }

    /** The declared {@code maxLength} of the string field, or empty when not a string / unset. */
    public OptionalInt stringMaxLength(String fieldPath) {
        return stringInt(fieldPath, "maxLength");
    }

    /** The declared regex {@code pattern} of the string field, or empty when not a string / no pattern. */
    public Optional<String> stringPattern(String fieldPath) {
        JsonNode string = stringTypeOf(fieldPath);
        if (string == null) {
            return Optional.empty();
        }
        String pattern = string.path("pattern").asText("");
        return pattern.isEmpty() ? Optional.empty() : Optional.of(pattern);
    }

    /**
     * The declared temporal format of the field at {@code fieldPath} — the DateType/TimeType/DateTimeType/
     * DateRangeType {@code format} string, or a DateFragmentType's {@code formatOfFragment}, which spells the
     * same declaration under its own key — or empty when the field carries none
     * (a non-temporal kind, or a temporal one that declares no format and so takes its kind's default).
     *
     * <p>Worth reading rather than inferring from the kind: A12's temporal format vocabulary is
     * kind-independent, and so is everything below the declaration — the stored-value gate, comparability and
     * component extraction all key on this string's component set, never on the field's kind
     * (KERNEL-FINDINGS KF211). A read-only config accessor like {@link #numberMin}: no throw on a non-temporal
     * field, so any field can be probed.
     */
    public Optional<String> temporalFormat(String fieldPath) {
        JsonNode fieldType = fieldTypeNode(fieldPath);
        String discriminator = fieldType.path(DmKeys.TYPE).asText("");
        JsonNode config = fieldType.path(discriminator);
        // A DateFragmentType spells the same declaration `formatOfFragment`; reading only `format` answers
        // empty for a field that plainly declares one, which disables a format-keyed gate instead of failing it.
        String format = config.path("DateFragmentType".equals(discriminator) ? "formatOfFragment" : "format")
                .asText("");
        return format.isEmpty() ? Optional.empty() : Optional.of(format);
    }

    /**
     * The field's declared {@code datePrecision} — {@code DAY_OPTIONAL}, {@code MONTH_OPTIONAL} or
     * {@code YEAR_OPTIONAL} — or empty when it declares none.
     *
     * <p><b>Empty means FULL, not "unknown".</b> A12's own serializer omits the key for the kernel default, so
     * a complete date field and a field whose precision was never written are the same document. A gate reading
     * this must therefore treat absence as the full triple rather than as a reason to refuse.
     *
     * <p>A read-only config accessor like {@link #temporalFormat}: no throw on a non-date field, so any field
     * can be probed.
     */
    public Optional<String> datePrecision(String fieldPath) {
        JsonNode fieldType = fieldTypeNode(fieldPath);
        String discriminator = fieldType.path(DmKeys.TYPE).asText("");
        String precision = fieldType.path(discriminator).path("datePrecision").asText("");
        return precision.isEmpty() ? Optional.empty() : Optional.of(precision);
    }

    /** Whether the string field declares {@code noValueValidation}; false for other kinds and an ordinary string. */
    public boolean stringNoValueValidation(String fieldPath) {
        JsonNode string = stringTypeOf(fieldPath);
        return string != null && string.path("noValueValidation").asBoolean(false);
    }

    /**
     * The allowed stored values of the enumeration field at {@code fieldPath} (the enum domain). Enums compare
     * by stored value, never display text (§5b), so these are the values to compare to.
     *
     * @throws IllegalArgumentException if the field is not an enumeration
     */
    public List<String> enumValues(String fieldPath) {
        JsonNode enumeration = requireEnum(fieldPath);
        List<String> values = new ArrayList<>();
        for (JsonNode v : enumeration.path("values")) {
            values.add(v.path(DmKeys.VALUE).asText());
        }
        return values;
    }

    /**
     * The categories of the enumeration field at {@code fieldPath}: category name → its distinct category values
     * (the model maps positionally — {@code categories[c].values[i]} pairs with enum {@code values[i]}; the
     * comparison domain is the distinct set).
     *
     * @throws IllegalArgumentException if the field is not an enumeration
     */
    public Map<String, List<String>> enumCategories(String fieldPath) {
        JsonNode enumeration = requireEnum(fieldPath);
        enumProjectionProbe.accept(EnumProjectionStep.CATEGORY_MATERIALIZATION);
        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (JsonNode category : enumeration.path("categories")) {
            categories.put(category.path(DmKeys.NAME).asText(),
                    distinctInEncounterOrder(category.path("values"), JsonNode::asText));
        }
        return categories;
    }

    static <S, T> List<T> distinctInEncounterOrder(
            Iterable<? extends S> sources, Function<? super S, ? extends T> valueOf) {
        var distinct = new LinkedHashSet<T>();
        for (S source : sources) {
            distinct.add(valueOf.apply(source));
        }
        return new ArrayList<>(distinct);
    }

    /** Whether the group at {@code groupPath} can repeat (repeatability other than exactly 1). */
    public boolean isRepeating(String groupPath) {
        return maxRepetitions(groupPath) != 1;
    }

    /**
     * The model's authored computations, in model order — each with its {@code computedFieldRelPath}
     * <b>resolved to the absolute target field path</b> (the rel path is relative to the computation
     * <em>element</em>: a sibling field in the group is {@code "../Field"}). Kernel-free, so a consumer
     * can reason about computation targets (e.g. reject a repeatable-scoped target) without the kernel.
     */
    public List<ComputationInfo> computations() {
        return reader.computations();
    }

    /**
     * The model's authored rules, in model order, each with its content as written and its error entity
     * resolvable through {@link RuleInfo#errorEntityPath()}. A rule mounted from an included model is listed
     * at its mounted path with its condition as the include expansion rewrote it.
     */
    public List<RuleInfo> rules() {
        return reader.rules();
    }

    /** The group's maximum repetitions — the repeatability count ({@code 1} = non-repeatable). */
    public int maxRepetitions(String groupPath) {
        return groupNode(groupPath).path(ElementType.GROUP.wireName()).path(DmKeys.REPEATABILITY).asInt(1);
    }

    /**
     * Whether the group at {@code groupPath} declares an INDEX FIELD ({@code indexFieldName}).
     *
     * <p>Read beyond semantic-index resolution because it is half of an iteration-admission rule: a bare
     * negative over a repeatable group is admitted only when that group declares an index AND its maximum
     * repeatability is two — neither alone (KERNEL-FINDINGS §9).
     */
    public boolean declaresIndexField(String groupPath) {
        return !groupNode(groupPath).path(ElementType.GROUP.wireName())
                .path(DmKeys.INDEX_FIELD_NAME).asText("").isBlank();
    }

    // --- navigation (Level 1, study finding 9): streams of facts in model order -----------------

    /** Paths of all fields, in model order. */
    public Stream<String> fieldPaths() {
        return reader.paths().stream().filter(this::isField);
    }

    /** Paths of all groups and fields in their mixed document order. */
    public Stream<String> elementPaths() {
        return reader.paths().stream();
    }

    /** Paths of all groups (root groups included), in model order. */
    public Stream<String> groupPaths() {
        return reader.paths().stream().filter(this::isGroup);
    }

    /** Paths of the fields directly contained in the group at {@code groupPath}, in model order. */
    public Stream<String> fieldPaths(String groupPath) {
        List<String> paths = new ArrayList<>();
        for (JsonNode child : groupNode(groupPath).path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS)) {
            if (ElementType.FIELD.matches(child)) {
                paths.add(groupPath + "/" + child.path(DmKeys.NAME).asText());
            }
        }
        return paths.stream();
    }

    /** Whether a field exists at {@code path} (the non-throwing peek behind {@code fieldAt}). */
    public boolean isField(String path) {
        return ElementType.FIELD.matches(nodeOrMissing(path));
    }

    /** Whether a group exists at {@code path} (the non-throwing peek behind {@code groupAt}). */
    public boolean isGroup(String path) {
        return ElementType.GROUP.matches(nodeOrMissing(path));
    }

    /** The element's short name (last path segment, as modeled). */
    public String nameOf(String path) {
        return require(path).path(DmKeys.NAME).asText();
    }

    /**
     * The element-level {@link Annotation annotations} of the field at {@code fieldPath} — the slot the
     * silent-ignore deserializer would otherwise hide. Backs rulekit's annotation read-back.
     */
    public List<Annotation> annotationsOf(String fieldPath) {
        return annotations(fieldNode(fieldPath));
    }

    /** The element-level {@link Annotation annotations} of the group at {@code groupPath}. */
    public List<Annotation> annotationsOfGroup(String groupPath) {
        return annotations(groupNode(groupPath));
    }

    /**
     * The group template marker at {@code groupPath}, such as {@code attachment} or {@code multi-select}.
     * This is observable model structure: include expansion replaces a {@code SINGLE_RG} mount's slot with the
     * discarded provider root's final slot (including absence), and attachment semantics depend on it.
     */
    public Optional<String> usageTypeOfGroup(String groupPath) {
        JsonNode usageType = groupNode(groupPath).path(ElementType.GROUP.wireName()).path(DmKeys.USAGE_TYPE);
        return usageType.isTextual() ? Optional.of(usageType.asText()) : Optional.empty();
    }

    /**
     * The id of the included model mounted at this group, when the group is an include boundary. Included
     * descendants remain ordinary inline nodes; nested mounts carry their own origin.
     */
    public Optional<String> originOf(String path) {
        require(path);
        return Optional.ofNullable(reader.origin(path));
    }

    /** The field's authored named type before effective-type inlining, when it used a type definition. */
    public Optional<String> typeReferenceOf(String path) {
        fieldNode(path);
        return Optional.ofNullable(reader.typeRef(path));
    }

    // --- node access + config readers (over the reader's expanded, typedef-inlined nodes) --------

    private static List<Annotation> annotations(JsonNode element) {
        List<Annotation> out = new ArrayList<>();
        for (JsonNode a : element.path(DmKeys.ANNOTATIONS)) {
            out.add(new Annotation(a.path(DmKeys.NAME).asText(), a.path(DmKeys.VALUE).asText()));
        }
        return out;
    }

    /** The (effective, typedef-inlined) {@code Field.fieldType} node of the field at {@code fieldPath}. */
    private JsonNode fieldTypeNode(String fieldPath) {
        return fieldNode(fieldPath).path(ElementType.FIELD.wireName()).path(DmKeys.FIELD_TYPE);
    }

    /** The {@code <Kind>Type} config block of a field whose effective type matches {@code discriminator}, else null. */
    private JsonNode typeBlockOf(String fieldPath, String discriminator) {
        JsonNode fieldType = fieldTypeNode(fieldPath);
        return discriminator.equals(fieldType.path(DmKeys.TYPE).asText()) ? fieldType.path(discriminator) : null;
    }

    private JsonNode numberTypeOf(String fieldPath) {
        return typeBlockOf(fieldPath, "NumberType");
    }

    private JsonNode stringTypeOf(String fieldPath) {
        return typeBlockOf(fieldPath, "StringType");
    }

    private JsonNode requireEnum(String fieldPath) {
        JsonNode enumeration = typeBlockOf(fieldPath, "EnumerationType");
        if (enumeration == null) {
            throw new IllegalArgumentException(
                    fieldPath + " is not an enumeration field (kind=" + kindOf(fieldPath) + ")");
        }
        return enumeration;
    }

    private Optional<BigDecimal> numberBound(String fieldPath, String key) {
        JsonNode number = numberTypeOf(fieldPath);
        if (number == null) {
            return Optional.empty();
        }
        JsonNode bound = number.path(key);
        if (bound.isMissingNode() || bound.isNull()) {
            return Optional.empty();
        }
        // The kernel returns the bound at the AUTHORED literal's exact scale (it keeps the literal verbatim, not
        // the field's maxFractionalDigits): `10` reads back `10`, `0.00` reads back `0.00`. The reader's mapper
        // preserves trailing zeros, so decimalValue() matches — no rescaling.
        return Optional.of(bound.isTextual() ? new BigDecimal(bound.asText()) : bound.decimalValue());
    }

    private OptionalInt stringInt(String fieldPath, String key) {
        JsonNode string = stringTypeOf(fieldPath);
        if (string == null) {
            return OptionalInt.empty();
        }
        JsonNode value = string.path(key);
        return value.isInt() || value.isIntegralNumber() ? OptionalInt.of(value.asInt()) : OptionalInt.empty();
    }

    // --- element resolution (name-path keyed; fail-fast with a hint) ------------------------------

    /** The field element node at {@code fieldPath}. */
    private JsonNode fieldNode(String fieldPath) {
        JsonNode node = require(fieldPath);
        if (!ElementType.FIELD.matches(node)) {
            throw new IllegalArgumentException("not a field: " + fieldPath);
        }
        return node;
    }

    /** The group element node at {@code groupPath}. */
    private JsonNode groupNode(String groupPath) {
        JsonNode node = require(groupPath);
        if (!ElementType.GROUP.matches(node)) {
            throw new IllegalArgumentException("not a group: " + groupPath);
        }
        return node;
    }

    private JsonNode require(String path) {
        JsonNode element = reader.element(path);
        if (element == null) {
            throw new IllegalArgumentException("no element at path: " + path
                    + Suggest.hint(path, reader.paths()));
        }
        return element;
    }

    private JsonNode nodeOrMissing(String path) {
        JsonNode element = reader.element(path);
        return element == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : element;
    }
}
