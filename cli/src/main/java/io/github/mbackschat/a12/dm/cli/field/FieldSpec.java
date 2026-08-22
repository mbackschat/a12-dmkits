package io.github.mbackschat.a12.dm.cli.field;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldMeta;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.NumberTrait;
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

/**
 * Parses the rich {@code field add <spec.json>} input — an agent-friendly JSON describing a field fully
 * (kind + per-kind config + field-level metadata) — into the modelkit {@link ElementDraft.Field} the tool
 * adds. This is the CLI surface for FEATURE-GAPS (field-config) and metadata: everything the
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

    /**
     * The per-kind config-block keys — {@link #TYPE_KEYS} without the two selectors ({@code kind}/{@code typedef}).
     * A block only takes effect once a {@code kind} selects it (so {@code "string": {…}} alone can't re-type a
     * field); {@link #configBlockWithoutKind} uses this to give that mistake a precise corrective.
     */
    public static final Set<String> CONFIG_BLOCK_KEYS =
            TYPE_KEYS.stream().filter(k -> !k.equals("kind") && !k.equals("typedef"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Common element metadata accepted only at creation and by the post-creation {@code meta} route (G45). */
    public static final Set<String> COMMON_METADATA_KEYS =
            Set.of("label", "annotations", "internalDescription", "externalDescription");

    /** Field-specific properties that remain on {@code field modify}. */
    public static final Set<String> FIELD_PROPERTY_KEYS = Set.of(
            "required", "global", "transient", "helperText", "requiredMessages");

    /** All field metadata/properties accepted by {@link #parse} at creation. */
    public static final Set<String> METADATA_KEYS = union(COMMON_METADATA_KEYS, FIELD_PROPERTY_KEYS);

    /** Every key a rich {@code field add} spec may carry (locator + type + metadata) — the apply FIELD_ADD op surface. */
    public static final Set<String> ADD_KEYS = union(LOCATOR_KEYS, TYPE_KEYS, METADATA_KEYS);

    /**
     * The semantic keys a {@code field modify} reads. Common labels/descriptions/annotations deliberately
     * live only on {@code meta <ref>} after creation (G45).
     */
    public static final Set<String> MODIFY_KEYS = union(LOCATOR_KEYS, TYPE_KEYS, FIELD_PROPERTY_KEYS);

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
        ElementDraft.Field field = new ElementDraft.Field(name, type, requiredness(spec), FieldMeta.builder()
                .labels(textMap(spec.get("label"))).annotations(annotations(spec.get("annotations")))
                .global(spec.path("global").asBoolean(false)).notPersisted(spec.path("transient").asBoolean(false))
                .internalDescription(textMap(spec.get("internalDescription")))
                .externalDescription(textMap(spec.get("externalDescription")))
                .helperText(textMap(spec.get("helperText"))).build());
        return new Parsed(group, field);
    }

    /**
     * The per-locale custom <b>required</b>-field error message a spec carries (G23), or an empty map. The
     * field-add path applies it as a post-step (after the field — with its requiredness — is created), since
     * the message attaches to the field's {@code requirednessConfig}.
     */
    public static Map<String, String> requiredMessages(JsonNode spec) {
        return textMap(spec.get("requiredMessages"));
    }

    /** The parse result for a MODIFY: the located field, and the partial edit to apply. */
    public record ModificationSpec(String group, String name, FieldModification modification) {
    }

    /**
     * Parse a {@code field modify} spec as a <b>partial</b> edit: only the aspects the spec actually carries
     * become present in the returned {@link FieldModification} (a {@code kind}/{@code typedef} ⇒ a re-type;
     * any field-specific property key ⇒ that property change), so an absent aspect is left untouched and a
     * present one is never silently dropped. Unlike {@link #parse}, a {@code kind} is NOT required.
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
        if (spec.hasNonNull("helperText")) {
            mod.helperText(textMap(spec.get("helperText")));
        }
        if (spec.hasNonNull("requiredMessages")) {
            mod.requiredMessages(textMap(spec.get("requiredMessages")));
        }
        if (spec.hasNonNull("global")) {
            mod.global(spec.get("global").asBoolean(false));
        }
        if (spec.hasNonNull("transient")) {
            mod.isTransient(spec.get("transient").asBoolean(false));
        }
        return new ModificationSpec(group, name, mod.build());
    }

    /**
     * The per-kind config-block key a spec carries WITHOUT a {@code kind}/{@code typedef} selector (e.g. a lone
     * {@code "string": {…}}), or {@code null}. Such a spec can't re-type a field — a re-type needs the
     * {@code kind} that selects the block — so {@code parseModification} treats it as carrying no type change;
     * the caller uses this to turn the otherwise-confusing "nothing to modify" into a precise corrective (add
     * the {@code kind} that selects this block). Returns the FIRST such key in the spec's own order (one block
     * is the realistic case).
     */
    public static String configBlockWithoutKind(JsonNode spec) {
        if (spec.hasNonNull("kind") || spec.hasNonNull("typedef")) {
            return null;
        }
        for (java.util.Iterator<String> it = spec.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (CONFIG_BLOCK_KEYS.contains(key)) {
                return key;
            }
        }
        return null;
    }

    /**
     * The corrective for an empty {@code field modify} (a spec that would change nothing) — context-aware: if
     * it carries a per-kind config block without a {@code kind} (the common slip, e.g. {@code "string": {…}}
     * to add a {@code patternMessage}), it names the {@code kind} to add and warns that a modify REPLACES the
     * field's type (so the full block must be supplied); otherwise the generic "give a kind/typedef or a
     * metadata key". Single-sourced so the standalone {@code field modify} and the {@code apply} FIELD_MODIFY
     * op give identical guidance.
     */
    public static String emptyModificationCorrective(JsonNode spec) {
        String block = configBlockWithoutKind(spec);
        if (block != null) {
            String kind = block.toUpperCase(java.util.Locale.ROOT);
            return "a \"" + block + "\" config block alone can't re-type a field — add \"kind\": \"" + kind
                    + "\" so the block takes effect. NOTE: modify REPLACES the field's type, so include the FULL \""
                    + block + "\" block (every key to keep), not only the changed one.";
        }
        return "nothing to modify — give a \"kind\"/\"typedef\" to re-type, or a field property "
                + "(required/helperText/requiredMessages/global/transient); use `dmtool meta <ref>` for "
                + "labels, descriptions, and annotations";
    }

    /**
     * Parse just the type config ({@code kind} + the per-kind block) into a {@link FieldTypeSpec} — the
     * {@code typedef add <spec.json>} path (G25), which reuses the field-add per-kind shape but carries no
     * locator/metadata. {@code label} names the thing being typed (for error messages).
     */
    public static FieldTypeSpec parseType(JsonNode spec, String label) {
        return type(spec, label);
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
                    textOr(spec.path("date"), "precision", "FULL"),
                    spec.path("date").path("rejectBefore1900").asBoolean(false));
            case "TIME" -> new FieldTypeSpec.TimeSpec(text(spec.path("time"), "format"));
            case "DATETIME", "DATE_TIME" -> new FieldTypeSpec.DateTimeSpec(text(spec.path("dateTime"), "format"));
            case "DATERANGE", "DATE_RANGE" -> new FieldTypeSpec.DateRangeSpec(
                    req(spec.path("dateRange"), "format"), req(spec.path("dateRange"), "separator"),
                    // absent key = STANDARD (the default has no DM-JSON spelling); an unknown token throws a
                    // corrective naming it, which the caller surfaces as an invalid-spec refusal.
                    RangeYearInterpretation.fromJson(text(spec.path("dateRange"), "interpretationOfYear")));
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
                s.path("lineBreaks").asBoolean(false), s.path("alphabeticalSorting").asBoolean(false), suggestions,
                s.path("noValueValidation").asBoolean(false),   // skip per-value validation (requires lineBreaks)
                textMap(s.get("patternMessages")));  // G23: per-locale pattern-error text (wins over the single)
    }

    private static FieldTypeSpec number(JsonNode n) {
        NumberTrait trait = n.hasNonNull("unit")
                ? NumberTrait.valueOf(n.get("unit").asText().toUpperCase(java.util.Locale.ROOT)) : null;
        return FieldTypeSpec.NumberSpec.builder()
                .minFractionalDigits(intOrNull(n, "minFractionalDigits"))
                .maxFractionalDigits(intOrNull(n, "maxFractionalDigits"))
                .minValue(decimalOrNull(n, "min"))
                .maxValue(decimalOrNull(n, "max"))
                .zeroNotAllowed(n.path("zeroNotAllowed").asBoolean(false))
                .leadingZerosAllowed(n.path("leadingZerosAllowed").asBoolean(false))
                .trait(trait)
                .minLength(intOrNull(n, "minLength"))
                .maxLength(intOrNull(n, "maxLength"))
                .maxIntegerDigits(intOrNull(n, "maxIntegerDigits"))
                .build();
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
            categories.add(new FieldTypeSpec.EnumCategory(req(c, "name"), stringList(c.get("values")),
                    text(c, "description")));
        }
        return new FieldTypeSpec.EnumSpec(values, categories,
                e.path("alphabeticalSorting").asBoolean(false), text(e, "invalidValueMessage"),
                textMap(e.get("errorMessages")),   // G23: per-locale invalid-value text (wins over the single shorthand)
                text(e, "sValue"));                // the index-default stored token (SPEC-2026-07-23-14)
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
