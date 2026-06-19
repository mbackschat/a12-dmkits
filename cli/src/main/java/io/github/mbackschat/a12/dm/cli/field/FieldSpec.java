package io.github.mbackschat.a12.dm.cli.field;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.NumberTrait;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

/**
 * Parses the rich {@code field add <spec.json>} input — an agent-friendly JSON describing a field fully
 * (kind + per-kind config + field-level metadata) — into the modelkit {@link ElementDraft.Field} the tool
 * adds. This is the CLI surface for FEATURE-GAPS §3.4 (field-config) and §3.5 (metadata): everything the
 * typed builders express, reachable from the bare CLI. The contract is published as {@code field-add.schema.json}.
 */
public final class FieldSpec {

    private FieldSpec() {
    }

    /** The parse result: the group to add into, and the fully-built field. */
    public record Parsed(String group, ElementDraft.Field field) {
    }

    /** The locator keys every field spec carries — where the field lives. */
    public static final Set<String> LOCATOR_KEYS = Set.of("group", "name");

    /**
     * The type-selection keys: a {@code typedef} reference, or a {@code kind} plus its per-kind config
     * block ({@code string}/{@code number}/{@code enum}/{@code custom} and the date/time families).
     */
    public static final Set<String> TYPE_KEYS = Set.of("kind", "typedef",
            "string", "number", "enum", "date", "time", "dateTime", "dateRange", "dateFragment", "custom");

    /** The field-level metadata keys {@link #parse} reads (requiredness, labels, descriptions, flags). */
    public static final Set<String> METADATA_KEYS = Set.of("required", "label", "annotations",
            "global", "transient", "internalDescription", "externalDescription", "helperText");

    /** Every key a rich {@code field add} spec may carry (locator + type + metadata) — the apply FIELD_ADD op surface. */
    public static final Set<String> ADD_KEYS = union(LOCATOR_KEYS, TYPE_KEYS, METADATA_KEYS);

    /**
     * The keys a {@code field modify} op reads. It is the <b>full</b> set: a modify is a partial, in-place
     * edit of an existing field's type <em>and/or</em> its metadata — so it accepts every key {@code add}
     * does (only the locator is required), and any present aspect is applied (never silently dropped).
     */
    public static final Set<String> MODIFY_KEYS = ADD_KEYS;

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> out = new java.util.HashSet<>();
        for (Set<String> s : sets) {
            out.addAll(s);
        }
        return Set.copyOf(out);
    }

    public static Parsed parse(JsonNode spec) {
        String group = text(spec, "group");
        String name = text(spec, "name");
        if (group == null || name == null) {
            throw new IllegalArgumentException("a field spec needs \"group\" (a group path) and \"name\"");
        }
        FieldTypeSpec type = type(spec, name);
        ElementDraft.Field field = new ElementDraft.Field(name, type, requiredness(spec),
                textMap(spec.get("label")), annotations(spec.get("annotations")),
                spec.path("global").asBoolean(false), spec.path("transient").asBoolean(false),
                textMap(spec.get("internalDescription")), textMap(spec.get("externalDescription")),
                textMap(spec.get("helperText")));
        return new Parsed(group, field);
    }

    /** The parse result for a MODIFY: the located field, and the partial edit to apply. */
    public record ModificationSpec(String group, String name, FieldModification modification) {
    }

    /**
     * Parse a {@code field modify} spec as a <b>partial</b> edit: only the aspects the spec actually carries
     * become present in the returned {@link FieldModification} (a {@code kind}/{@code typedef} ⇒ a re-type;
     * any metadata key ⇒ that metadata change), so an absent aspect is left untouched and a present one is
     * never silently dropped. Unlike {@link #parse}, a {@code kind} is NOT required — a metadata-only edit
     * (e.g. just a {@code label}) is valid.
     */
    public static ModificationSpec parseModification(JsonNode spec) {
        String group = text(spec, "group");
        String name = text(spec, "name");
        if (group == null || name == null) {
            throw new IllegalArgumentException("a field spec needs \"group\" (a group path) and \"name\"");
        }
        FieldModification.Builder mod = FieldModification.builder();
        if (spec.hasNonNull("typedef") || spec.hasNonNull("kind")) {
            mod.type(type(spec, name));
        }
        if (spec.hasNonNull("required")) {
            mod.requiredness(requiredness(spec));
        }
        if (spec.hasNonNull("label")) {
            mod.label(textMap(spec.get("label")));
        }
        if (spec.hasNonNull("helperText")) {
            mod.helperText(textMap(spec.get("helperText")));
        }
        if (spec.hasNonNull("internalDescription")) {
            mod.internalDescription(textMap(spec.get("internalDescription")));
        }
        if (spec.hasNonNull("externalDescription")) {
            mod.externalDescription(textMap(spec.get("externalDescription")));
        }
        if (spec.hasNonNull("annotations")) {
            mod.annotations(annotations(spec.get("annotations")));
        }
        if (spec.hasNonNull("global")) {
            mod.global(spec.get("global").asBoolean(false));
        }
        if (spec.hasNonNull("transient")) {
            mod.isTransient(spec.get("transient").asBoolean(false));
        }
        return new ModificationSpec(group, name, mod.build());
    }

    /** The typed config: a {@code typedef} ref wins; otherwise {@code kind} selects the per-kind config block. */
    private static FieldTypeSpec type(JsonNode spec, String name) {
        if (spec.hasNonNull("typedef")) {
            return new FieldTypeSpec.TypeDefRef(spec.get("typedef").asText());
        }
        String kindText = text(spec, "kind");
        if (kindText == null) {
            throw new IllegalArgumentException("field '" + name + "' needs a \"kind\" (or a \"typedef\")");
        }
        return switch (kindText.toUpperCase(java.util.Locale.ROOT)) {
            case "STRING" -> string(spec.path("string"));
            case "NUMBER" -> number(spec.path("number"));
            case "ENUM", "ENUMERATION" -> enumeration(spec.path("enum"));
            case "CUSTOM" -> custom(spec.path("custom"));
            case "DATE" -> new FieldTypeSpec.DateSpec(text(spec.path("date"), "format"),
                    textOr(spec.path("date"), "precision", "FULL"));
            case "TIME" -> new FieldTypeSpec.TimeSpec(text(spec.path("time"), "format"));
            case "DATETIME", "DATE_TIME" -> new FieldTypeSpec.DateTimeSpec(text(spec.path("dateTime"), "format"));
            case "DATERANGE", "DATE_RANGE" -> new FieldTypeSpec.DateRangeSpec(
                    req(spec.path("dateRange"), "format"), req(spec.path("dateRange"), "separator"));
            case "DATEFRAGMENT", "DATE_FRAGMENT" -> new FieldTypeSpec.DateFragmentSpec(
                    req(spec.path("dateFragment"), "format"));
            case "BOOLEAN" -> new FieldTypeSpec.BooleanSpec();
            case "CONFIRM" -> new FieldTypeSpec.ConfirmSpec();
            // a kind with no config block falls through its case above with empty config (= a bare spec);
            // anything here is genuinely unknown
            default -> throw new IllegalArgumentException("unknown field kind: '" + kindText
                    + "' (STRING | NUMBER | DATE | TIME | DATETIME | DATERANGE | DATEFRAGMENT | "
                    + "BOOLEAN | CONFIRM | ENUM | CUSTOM, or a \"typedef\")");
        };
    }

    private static FieldTypeSpec string(JsonNode s) {
        Map<String, List<String>> suggestions = new LinkedHashMap<>();
        JsonNode sug = s.path("suggestions");
        sug.fieldNames().forEachRemaining(loc -> suggestions.put(loc, stringList(sug.get(loc))));
        return new FieldTypeSpec.StringSpec(intOrNull(s, "minLength"), intOrNull(s, "maxLength"),
                text(s, "pattern"), text(s, "patternMessage"),
                s.path("lineBreaks").asBoolean(false), s.path("alphabeticalSorting").asBoolean(false), suggestions);
    }

    private static FieldTypeSpec number(JsonNode n) {
        NumberTrait trait = n.hasNonNull("unit")
                ? NumberTrait.valueOf(n.get("unit").asText().toUpperCase(java.util.Locale.ROOT)) : null;
        return new FieldTypeSpec.NumberSpec(intOrNull(n, "minFractionalDigits"), intOrNull(n, "maxFractionalDigits"),
                decimalOrNull(n, "min"), decimalOrNull(n, "max"),
                n.path("zeroNotAllowed").asBoolean(false), n.path("leadingZerosAllowed").asBoolean(false), trait);
    }

    private static FieldTypeSpec enumeration(JsonNode e) {
        List<FieldTypeSpec.EnumValue> values = new ArrayList<>();
        for (JsonNode v : e.path("values")) {
            values.add(new FieldTypeSpec.EnumValue(req(v, "value"), textMap(v.get("labels"))));
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("an enum field needs \"enum.values\" (≥1 {value, labels?})");
        }
        List<FieldTypeSpec.EnumCategory> categories = new ArrayList<>();
        for (JsonNode c : e.path("categories")) {
            categories.add(new FieldTypeSpec.EnumCategory(req(c, "name"), stringList(c.get("values"))));
        }
        return new FieldTypeSpec.EnumSpec(values, categories,
                e.path("alphabeticalSorting").asBoolean(false), text(e, "invalidValueMessage"));
    }

    private static FieldTypeSpec custom(JsonNode c) {
        return new FieldTypeSpec.CustomSpec(req(c, "typeName"), intOrNull(c, "minLength"), intOrNull(c, "maxLength"));
    }

    private static Requiredness requiredness(JsonNode spec) {
        JsonNode r = spec.path("required");
        if (r.isMissingNode() || r.isNull()) {
            return Requiredness.OPTIONAL;   // absent → the default (the add path calls this unconditionally)
        }
        if (r.isBoolean()) {
            return r.asBoolean() ? Requiredness.REQUIRED : Requiredness.OPTIONAL;
        }
        if (r.isTextual() && "ifParentPresent".equalsIgnoreCase(r.asText())) {
            return Requiredness.REQUIRED_IF_PARENT_PRESENT;
        }
        // reject a recognized-key/wrong-shaped value rather than silently coercing it to OPTIONAL and falsely
        // reporting `required` applied — e.g. "MANDATORY"/"REQUIRED" (the kernel's own terms) used to fall here.
        throw new IllegalArgumentException("\"required\" must be true, false, or \"ifParentPresent\" (got: "
                + r.toString() + ")");
    }

    private static List<ElementDraft.Annotation> annotations(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        // reject a wrong shape (e.g. the array form [{name,value}]) rather than silently returning an empty
        // list and falsely reporting `annotations` applied — the schema declares an object { name: value }.
        if (!node.isObject()) {
            throw new IllegalArgumentException("\"annotations\" must be an object { \"<name>\": \"<value>\" } (got "
                    + node.getNodeType().toString().toLowerCase(java.util.Locale.ROOT) + ")");
        }
        List<ElementDraft.Annotation> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(name -> out.add(new ElementDraft.Annotation(name, node.get(name).asText())));
        return out;
    }

    private static Map<String, String> textMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        // reject a wrong shape (e.g. a bare string) rather than silently returning an empty map and falsely
        // reporting the localized-text aspect (label/helperText/…/description) applied — it is { locale: text }.
        if (!node.isObject()) {
            throw new IllegalArgumentException("a localized-text field must be an object { \"<locale>\": \"<text>\" } (got "
                    + node.getNodeType().toString().toLowerCase(java.util.Locale.ROOT) + ")");
        }
        Map<String, String> out = new LinkedHashMap<>();
        node.fieldNames().forEachRemaining(key -> out.put(key, node.get(key).asText()));
        return out;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static String text(JsonNode node, String key) {
        return node != null && node.hasNonNull(key) ? node.get(key).asText() : null;
    }

    private static String textOr(JsonNode node, String key, String fallback) {
        String t = text(node, key);
        return t != null ? t : fallback;
    }

    private static String req(JsonNode node, String key) {
        String t = text(node, key);
        if (t == null) {
            throw new IllegalArgumentException("missing required spec field: \"" + key + "\"");
        }
        return t;
    }

    private static Integer intOrNull(JsonNode node, String key) {
        return node != null && node.hasNonNull(key) ? node.get(key).asInt() : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String key) {
        return node != null && node.hasNonNull(key) ? new BigDecimal(node.get(key).asText()) : null;
    }
}
