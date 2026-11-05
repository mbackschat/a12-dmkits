package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.collectNot;
import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.enumArrayValues;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import io.github.mbackschat.a12.dm.modelkit.StringFieldBuilder;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.Ctx;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * How {@link JsonSchemaImporter} maps a scalar property onto a field: enum, string (lengths, pattern, formats),
 * and number, together with the bound and dependency assertions it carries as rule needs.
 */
final class ScalarFields {

    private ScalarFields() {
    }

    static void addScalar(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                  boolean isRequired, String path, Ctx ctx) {
        Requiredness req = isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL;
        collectNot(name, schema, path, ctx);   // a 'not' const/enum → a forbidding rule (the synthesis resolves the kind)
        if (schema.has("enum") && schema.get("enum").isArray() && !schema.get("enum").isEmpty()) {
            addEnumField(g, name, schema, isRequired, ctx);
            ctx.report().structure(path, "ENUMERATION");
            return;
        }
        switch (schema.path("type").asText("")) {
            case "string" -> {
                if (schema.path("format").isTextual()) {
                    addFormattedString(g, name, schema, isRequired, path, ctx);
                } else {
                    addStringField(g, name, schema, isRequired, path, ctx);
                    ctx.report().structure(path, "STRING");
                }
                if (schema.has("const")) {   // applies to BOTH plain and formatted strings — the format path dropped it
                    ctx.ruleNeeds().add(new RuleSynthesis.ConstValue(path, schema.get("const").asText()));
                }
            }
            case "boolean" -> {
                FieldKind kind = ctx.profile().booleanKind() == MappingProfile.BooleanKind.CONFIRM
                        ? FieldKind.CONFIRM : FieldKind.BOOLEAN;
                g.field(name, kind, req);
                ctx.report().structure(path, kind.name());
            }
            case "integer" -> {
                addNumericField(g, name, schema, isRequired, 0, ctx);
                ctx.report().structure(path, "NUMBER(integer, scale 0)");
                collectNumericRules(schema, path, ctx);
            }
            // JSON `number` has no faithful a12 target without a scale signal: an a12 number always carries a
            // bounded max scale (modelkit defaults it to 0 = integer). The MappingProfile decides: OMIT (the
            // default — never silently turn a decimal into an integer), INTEGER (scale 0), or a DEFAULT_SCALE.
            case "number" -> addNumber(g, name, schema, isRequired, path, ctx);
            default -> ctx.report().omit(path, "property '" + name + "' has type '"
                    + (schema.path("type").asText("").isEmpty() ? "<none>" : schema.path("type").asText(""))
                    + "' — omitted (not yet mapped). TO DO: add this field by hand");
        }
    }

    /** A JSON Schema {@code enum} → an a12 ENUMERATION, values in order, labelled by {@link EnumLabelMapping}. */
    private static void addEnumField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                     boolean isRequired, Ctx ctx) {
        g.enumField(name, e -> {
            EnumLabelMapping.addValues(e, enumArrayValues(schema), schema, name, ctx.profile().enumLabels(), ctx.report());
            if (isRequired) {
                e.required();
            }
        });
    }

    /** A string field; {@code minLength}/{@code maxLength}/{@code pattern} import to string field config. */
    private static void addStringField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                       boolean isRequired, String path, Ctx ctx) {
        boolean bounded = schema.has("minLength") || schema.has("maxLength");
        String pattern = schema.path("pattern").isTextual() ? schema.get("pattern").asText() : null;
        if (!bounded && pattern == null) {
            g.field(name, FieldKind.STRING, isRequired ? Requiredness.REQUIRED : Requiredness.OPTIONAL);
            return;
        }
        g.stringField(name, s -> configureStringField(s, schema, isRequired, path, ctx));
    }

    static void configureStringField(StringFieldBuilder s, JsonNode schema, boolean isRequired,
                                             String path, Ctx ctx) {
        declareLengths(s, schema, path, ctx);
        if (schema.path("pattern").isTextual()) {
            String pattern = schema.get("pattern").asText();
            s.pattern(pattern, pattern.isEmpty() ? null : "must match the specified pattern"); // Empty declarations are inactive (SPEC-2026-10-10-13).
        }
        if (isRequired) s.required();
    }

    /** A12's technical limit on a checked String input, and the largest length bound it admits as a declaration. */
    private static final int TECHNICAL_LENGTH_LIMIT = 99_999;

    /**
     * A schema's integer keyword as an {@code int}, saturating past the 32-bit range.
     *
     * <p>Jackson's {@code asInt()} keeps only the low 32 bits of a larger integer, so a {@code maxLength} of
     * 4,294,967,301 read as 5, a bound far narrower than the schema's, with no report. Every keyword read through
     * here is a bound or a count, and saturating keeps it on the same side of each comparison the importer makes.
     */
    static int saturatedInt(JsonNode keyword) {
        if (!keyword.isIntegralNumber() || keyword.canConvertToInt()) return keyword.asInt();
        return keyword.bigIntegerValue().signum() < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    }

    /**
     * Declares the schema's {@code minLength}/{@code maxLength} on [s], each only within the 1 to 99,999 A12 admits.
     *
     * <p>A12 refuses a declared bound above its technical limit, and its input guard refuses any longer stored value
     * anyway (KERNEL-FINDINGS KF547, {@code validate.laws.StringLengthDeclarationBoundLawsTest}). So a larger maximum
     * is left undeclared with the narrower effective bound reported, and a larger minimum, which no stored value can
     * reach, is reported rather than turned into a model A12 cannot load. A12 refuses a zero length too (KF556): a
     * zero minimum constrains nothing and is dropped silently, while a zero maximum, which admits only the empty
     * string, has no A12 spelling and is reported.
     */
    private static void declareLengths(StringFieldBuilder s, JsonNode schema, String path, Ctx ctx) {
        if (schema.has("minLength")) {
            int min = saturatedInt(schema.get("minLength"));
            if (min > TECHNICAL_LENGTH_LIMIT) ctx.report().diagnostic("minLength " + schema.get("minLength").asText() + " at '" + path
                    + "' exceeds A12's " + TECHNICAL_LENGTH_LIMIT + "-unit limit on a String value, so no value can satisfy it — not declared; constrain it by hand");
            else if (min > 0) s.minLength(min);
        }
        if (schema.has("maxLength")) {
            int max = saturatedInt(schema.get("maxLength"));
            if (max > TECHNICAL_LENGTH_LIMIT) ctx.report().diagnostic("maxLength " + schema.get("maxLength").asText() + " at '" + path
                    + "' exceeds A12's " + TECHNICAL_LENGTH_LIMIT + "-unit limit on a String value — not declared; the effective maximum is " + TECHNICAL_LENGTH_LIMIT);
            else if (max > 0) s.maxLength(max);
            else ctx.report().diagnostic("maxLength " + schema.get("maxLength").asText() + " at '" + path + "' admits only the empty string, which A12 "
                    + "cannot declare (a length is at least 1) — not declared; constrain it by hand");
        }
    }

    /**
     * A string {@code format} (SCHEMAKIT-SPEC §4b). Temporal formats map to a12's native temporal field
     * KINDS (always — they are first-class a12 types, strictly better than a string); the semantic string
     * formats (email/uuid/…) follow the {@link MappingProfile#format()} strategy.
     */
    private static void addFormattedString(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                           boolean isRequired, String path, Ctx ctx) {
        String format = schema.get("format").asText();
        switch (format) {
            case "date" -> {
                g.dateField(name, d -> { if (isRequired) { d.required(); } });
                ctx.report().structure(path, "DATE (format date)");
            }
            case "date-time" -> {
                g.dateTimeField(name, d -> { if (isRequired) { d.required(); } });
                ctx.report().structure(path, "DATE_TIME (format date-time)");
            }
            case "time" -> {
                g.timeField(name, t -> { if (isRequired) { t.required(); } });
                ctx.report().structure(path, "TIME (format time)");
            }
            default -> addSemanticFormat(g, name, schema, format, isRequired, path, ctx);
        }
    }

    /** A non-temporal {@code format} (email/uuid/…) — governed by the profile's {@link MappingProfile#format()}. */
    private static void addSemanticFormat(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                          String format, boolean isRequired, String path, Ctx ctx) {
        switch (ctx.profile().format()) {
            case PATTERN -> standardRegex(format).ifPresentOrElse(
                    regex -> {
                        g.stringField(name, s -> {
                            declareLengths(s, schema, path, ctx);
                            s.pattern(regex, name + " must be a valid " + format);
                            if (isRequired) {
                                s.required();
                            }
                        });
                        ctx.report().structure(path, "STRING + pattern (format " + format + ")");
                    },
                    () -> {
                        addStringField(g, name, schema, isRequired, path, ctx);
                        ctx.report().structure(path, "STRING");
                        ctx.report().diagnostic("format '" + format + "' at '" + name + "' has no standard regex "
                                + "— imported as a plain string. TO DO: add a pattern by hand, or set the format "
                                + "strategy to CUSTOM_TYPE");
                    });
            case CUSTOM_TYPE -> {
                g.customField(name, c -> {
                    c.type(format);
                    if (isRequired) {
                        c.required();
                    }
                });
                ctx.report().structure(path, "CUSTOM type '" + format + "' (format " + format + ")");
                ctx.report().diagnostic("format '" + format + "' at '" + name + "' mapped to a custom field type '"
                        + format + "' — register a validator for it at runtime");
            }
            case IGNORE -> {
                addStringField(g, name, schema, isRequired, path, ctx);
                ctx.report().structure(path, "STRING (format '" + format + "' ignored)");
            }
        }
    }

    /** A standard regex for a well-known JSON Schema string {@code format}, where one exists. */
    private static Optional<String> standardRegex(String format) {
        return switch (format) {
            case "email" -> Optional.of("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}");
            case "uuid" -> Optional.of("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
            default -> Optional.empty();
        };
    }

    /** Strict bounds have no inclusive field-config home → collect them as Tier-A+ synthesized rules. */
    private static void collectStrictBounds(JsonNode schema, String path, Ctx ctx) {
        exclusiveBound(schema, true, ctx)
                .ifPresent(v -> ctx.ruleNeeds().add(new RuleSynthesis.ExclusiveBound(path, true, v)));
        exclusiveBound(schema, false, ctx)
                .ifPresent(v -> ctx.ruleNeeds().add(new RuleSynthesis.ExclusiveBound(path, false, v)));
    }

    /** The strict-bound value, honoring the dialect's encoding: OpenAPI 3.0 uses a <em>boolean</em>
     *  {@code exclusiveMinimum} flag that modifies {@code minimum}; JSON Schema / OpenAPI 3.1 use a
     *  <em>numeric</em> {@code exclusiveMinimum}. Empty when there is no strict bound. */
    private static Optional<String> exclusiveBound(JsonNode schema, boolean min, Ctx ctx) {
        JsonNode ex = schema.path(min ? "exclusiveMinimum" : "exclusiveMaximum");
        if (ctx.dialect().isOpenApi30()) {
            String inc = min ? "minimum" : "maximum";
            return ex.asBoolean(false) && schema.has(inc) ? Optional.of(schema.get(inc).asText()) : Optional.empty();
        }
        return ex.isNumber() ? Optional.of(ex.asText()) : Optional.empty();
    }

    /** The inclusive {@code minimum}/{@code maximum} value, suppressed when OpenAPI 3.0 marks it exclusive (then it
     *  rides a strict-bound rule instead — {@link #exclusiveBound}). Empty when absent. */
    private static Optional<String> inclusiveBound(JsonNode schema, boolean min, Ctx ctx) {
        String inc = min ? "minimum" : "maximum";
        if (!schema.has(inc)) {
            return Optional.empty();
        }
        if (ctx.dialect().isOpenApi30() && schema.path(min ? "exclusiveMinimum" : "exclusiveMaximum").asBoolean(false)) {
            return Optional.empty();
        }
        return Optional.of(schema.get(inc).asText());
    }

    /** {@code dependentRequired} on an object → a conditional-requiredness rule per (trigger → dependent). */
    static void collectDependentRequired(JsonNode objectSchema, String groupPath, Ctx ctx) {
        JsonNode dependentRequired = objectSchema.path("dependentRequired");
        if (!dependentRequired.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = dependentRequired.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isArray()) {
                continue;
            }
            for (JsonNode dependent : e.getValue()) {
                ctx.ruleNeeds().add(new RuleSynthesis.DependentRequired(groupPath, e.getKey(), dependent.asText()));
            }
        }
    }

    /** A JSON {@code number} (decimal) under the scale strategy. OMIT drops it (no field, no rules — never a
     *  silent integer coercion); INTEGER / DEFAULT_SCALE create the field at the chosen scale, then collect the
     *  numeric rules. The rule collection runs ONLY when the field exists — omitting it would leave a dangling
     *  rule referencing a missing field. */
    private static void addNumber(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                  boolean isRequired, String path, Ctx ctx) {
        switch (ctx.profile().numberScale()) {
            case OMIT -> ctx.report().omit(path, "property '" + name + "' is type 'number' (unbounded decimal) "
                    + "— omitted: an a12 number is always scale-bounded. TO DO: add the field by hand with a "
                    + "chosen scale, or set the MappingProfile numberScale to DEFAULT_SCALE / INTEGER");
            case INTEGER -> {
                addNumericField(g, name, schema, isRequired, 0, ctx);
                ctx.report().structure(path, "NUMBER(integer, scale 0)");
                ctx.report().diagnostic("number '" + name + "' imported as a scale-0 integer (--number-scale INTEGER) "
                        + "— any decimal places are DROPPED; set --number-scale DEFAULT_SCALE to keep them");
                collectNumericRules(schema, path, ctx);
            }
            case DEFAULT_SCALE -> {
                int scale = ctx.profile().defaultScale();
                addNumericField(g, name, schema, isRequired, scale, ctx);
                ctx.report().structure(path, "NUMBER(scale " + scale + ")");
                ctx.report().diagnostic("number '" + name + "' has no scale in the schema — imported at the "
                        + "default scale " + scale + " (a guess; set --default-scale, --number-scale, or the "
                        + "field's scale by hand if a different precision is needed)");
                collectNumericRules(schema, path, ctx);
            }
        }
    }

    /** Numeric constraints with no inclusive field-config home → Tier-A+ rules: strict (exclusive) bounds,
     *  {@code const}, {@code multipleOf}. Applied to EVERY created numeric field — integer AND number. The
     *  earlier divergence (the `number` path collected none of these) was a class bug (omit-never-silent, §4a). */
    private static void collectNumericRules(JsonNode schema, String path, Ctx ctx) {
        collectStrictBounds(schema, path, ctx);
        if (schema.has("const")) {
            ctx.ruleNeeds().add(new RuleSynthesis.ConstValue(path, schema.get("const").asText()));
        }
        if (schema.has("multipleOf")) {
            ctx.ruleNeeds().add(new RuleSynthesis.MultipleOf(path, schema.get("multipleOf").asText()));
        }
    }

    /** Create a numeric field at a fixed {@code scale}, applying inclusive {@code minimum}/{@code maximum} bounds
     *  and requiredness (an OpenAPI-3.0 exclusive bound is suppressed here and rides a strict-bound rule instead —
     *  {@link #inclusiveBound}). Shared by integer (scale 0) and number (the scale strategy), so both kinds honor
     *  the same field-config constraints. */
    private static void addNumericField(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                        boolean isRequired, int scale, Ctx ctx) {
        Optional<String> minimum = inclusiveBound(schema, true, ctx);
        Optional<String> maximum = inclusiveBound(schema, false, ctx);
        g.numberField(name, n -> {
            n.fractionDigits(scale);
            minimum.ifPresent(n::min);
            maximum.ifPresent(n::max);
            if (isRequired) {
                n.required();
            }
        });
    }
}
