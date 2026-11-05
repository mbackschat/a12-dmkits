package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.addProperty;
import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.defName;
import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.populate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.Ctx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How {@link JsonSchemaImporter} maps schema composition onto groups: an {@code allOf} of objects merges into one
 * group, and a {@code oneOf}/{@code anyOf} of objects, which a12 cannot express as a sum type, becomes a superset
 * group with a discriminator and its rules.
 */
final class UnionGroups {

    private UnionGroups() {
    }

    // --- allOf of object subschemas (SCHEMAKIT-SPEC §4b): an intersection → merge into one group ---

    /** The object subschemas of {@code allOf}, with each {@code $ref} resolved to its {@code $def} (pure). */
    static List<JsonNode> allOfObjectSubschemas(JsonNode schema, Ctx ctx) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode sub : schema.path("allOf")) {
            JsonNode resolved = sub.has("$ref") ? ctx.defs().path(defName(sub)) : sub;
            if (resolved.path("properties").isObject() || resolved.path("required").isArray()) {
                result.add(resolved);
            }
        }
        return result;
    }

    /** A bare {@code allOf} property (no {@code type}) → a group built from the merged subschemas. */
    static void addAllOfGroup(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                      String path, Ctx ctx) {
        boolean ownProps = schema.path("properties").isObject() && !schema.get("properties").isEmpty();
        if (!ownProps && allOfObjectSubschemas(schema, ctx).stream().noneMatch(sub -> sub.path("properties").isObject())) {
            ctx.report().omit(path, "property '" + name + "' is an allOf with no object subschema carrying "
                    + "properties — omitted (an empty group is invalid). TO DO: it may be a scalar-constraint "
                    + "allOf; add the field by hand");
            return;
        }
        g.group(name, sub -> populate(sub, schema, path, ctx));
        ctx.report().structure(path, "group (allOf merge)");
    }

    // --- oneOf/anyOf of object variants (SCHEMAKIT-SPEC §4b): a12 has no sum type → superset + discriminator ---

    private static JsonNode unionNode(JsonNode schema) {
        JsonNode oneOf = schema.path("oneOf");
        return oneOf.isArray() ? oneOf : schema.path("anyOf");
    }

    /** Any {@code oneOf}/{@code anyOf} present (object or not) — used to flag a cross-kind union after the
     *  all-object case ({@link #isObjectUnion}) has been handled. */
    static boolean hasUnionNode(JsonNode schema) {
        return (schema.path("oneOf").isArray() && !schema.path("oneOf").isEmpty())
                || (schema.path("anyOf").isArray() && !schema.path("anyOf").isEmpty());
    }

    /**
     * A {@code type} array (SCHEMAKIT-SPEC §4c). A <em>nullable</em> {@code [X, "null"]} DOES map → an
     * optional {@code X} field (the value may be absent). A cross-kind {@code [X, Y, …]} has no a12 form (a
     * field has one kind) → omitted with a corrective to-do. Omit, never block.
     */
    static void addTypeUnion(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                     boolean isRequired, String groupPath, Ctx ctx) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode t : schema.get("type")) {
            if (!"null".equals(t.asText())) {
                kinds.add(t.asText());
            }
        }
        String path = groupPath + "/" + name;
        if (kinds.size() == 1) {   // nullable X → optional X (the §4c exception that DOES map)
            ObjectNode single = (ObjectNode) schema.deepCopy();
            single.put("type", kinds.get(0));
            addProperty(g, name, single, false, groupPath, ctx);   // nullable ⇒ optional, regardless of `required`
            return;
        }
        ctx.report().omit(path, "property '" + name + "' has a cross-kind type union " + kinds + " — omitted: an "
                + "a12 field has exactly one kind (SCHEMAKIT-SPEC §4c). TO DO: split it into separate fields, or "
                + "pick the primary kind by hand");
    }

    /** A {@code oneOf}/{@code anyOf} whose variants are ALL objects — the only union shape a12 can superset. */
    static boolean isObjectUnion(JsonNode schema) {
        JsonNode variants = unionNode(schema);
        if (!variants.isArray() || variants.isEmpty()) {
            return false;
        }
        for (JsonNode v : variants) {
            if (!"object".equals(v.path("type").asText("")) && !v.path("properties").isObject()) {
                return false;
            }
        }
        return true;
    }

    static List<JsonNode> unionVariants(JsonNode schema) {
        List<JsonNode> variants = new ArrayList<>();
        unionNode(schema).forEach(variants::add);
        return variants;
    }

    static void addUnion(DocumentModelDraft.GroupBuilder g, String name, List<JsonNode> variants,
                                 String path, Ctx ctx, String explicitDiscriminator) {
        switch (ctx.profile().unionStrategy()) {
            case OMIT -> ctx.report().omit(path, "property '" + name + "' is a oneOf/anyOf union of object "
                    + "variants — omitted. TO DO: model the variants as a superset group by hand, or set the "
                    + "union strategy in the MappingProfile (SUPERSET_RULE / FIRST_VARIANT)");
            case FIRST_VARIANT -> {
                g.group(name, sub -> populate(sub, variants.get(0), path, ctx));
                ctx.report().structure(path, "group (first oneOf/anyOf variant only)");
            }
            case SUPERSET_RULE -> buildSuperset(g, name, variants, path, ctx, explicitDiscriminator);
        }
    }

    /** The OpenAPI {@code discriminator.propertyName} on a union schema (OpenAPI dialects only), else null. */
    static String explicitDiscriminator(JsonNode schema, Ctx ctx) {
        return ctx.dialect().isOpenApi() ? schema.path("discriminator").path("propertyName").asText(null) : null;
    }

    /** The superset (property-level): a child group unioning every variant's fields (all optional), with a
     *  discriminator lifted to an enum + rules. */
    private static void buildSuperset(DocumentModelDraft.GroupBuilder g, String name, List<JsonNode> variants,
                                      String path, Ctx ctx, String explicitDiscriminator) {
        Optional<String> discriminator = findDiscriminator(variants, explicitDiscriminator);
        g.group(name, sub -> fillSuperset(sub, variants, discriminator, path, ctx));
        ctx.report().structure(path, "oneOf/anyOf superset group (" + variants.size() + " variants)");
        finishSuperset(name, variants, discriminator, path, ctx);
    }

    /** The superset at the MODEL ROOT: fill the root group itself with the union (no extra nesting). A
     *  {@code components/schemas} entry that is itself a {@code oneOf}/{@code anyOf} lands here (SCHEMAKIT-SPEC §4g). */
    static void buildRootSuperset(DocumentModelDraft.GroupBuilder rootGroup, List<JsonNode> variants,
                                          String path, Ctx ctx, String explicitDiscriminator) {
        Optional<String> discriminator = findDiscriminator(variants, explicitDiscriminator);
        fillSuperset(rootGroup, variants, discriminator, path, ctx);
        ctx.report().structure(path, "oneOf/anyOf superset at the model root (" + variants.size() + " variants)");
        finishSuperset(path.substring(path.lastIndexOf('/') + 1), variants, discriminator, path, ctx);
    }

    /** Fill {@code target} with a discriminator enum (if any) + every variant's fields, deduped, all OPTIONAL. */
    private static void fillSuperset(DocumentModelDraft.GroupBuilder target, List<JsonNode> variants,
                                     Optional<String> discriminator, String path, Ctx ctx) {
        Set<String> seen = new LinkedHashSet<>();
        discriminator.ifPresent(disc -> {
            seen.add(disc);
            target.enumField(disc, e -> EnumLabelMapping.addValues(e, discriminatorValues(variants, disc), MissingNode.getInstance(),
                    disc, ctx.profile().enumLabels(), ctx.report()));
            ctx.report().structure(path + "/" + disc, "discriminator ENUMERATION");
        });
        for (JsonNode variant : variants) {
            for (Iterator<Map.Entry<String, JsonNode>> it = variant.path("properties").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (seen.add(e.getKey())) {   // first occurrence only; superset fields are all OPTIONAL
                    addProperty(target, e.getKey(), e.getValue(), false, path, ctx);
                }
            }
        }
    }

    /** Post-fill: synthesize the discriminator's per-variant requiredness rules, or report missing exclusivity. */
    private static void finishSuperset(String name, List<JsonNode> variants, Optional<String> discriminator,
                                       String path, Ctx ctx) {
        if (discriminator.isPresent()) {
            collectDiscriminatorRules(variants, discriminator.get(), path, ctx);
        } else {
            ctx.report().diagnostic("oneOf/anyOf at '" + name + "' merged into a superset of optional fields; "
                    + "cross-variant exclusivity has no discriminator to key on and is NOT structurally enforced. "
                    + "TO DO: add a discriminator (a const field in each variant), or enforce exclusivity by hand");
        }
    }

    /** A discriminator: a property present in every variant that each variant pins to a single value. An explicit
     *  OpenAPI {@code discriminator.propertyName} ({@code explicit}) is preferred when each variant pins it. */
    private static Optional<String> findDiscriminator(List<JsonNode> variants, String explicit) {
        if (explicit != null && !explicit.isBlank() && pinnedInEvery(variants, explicit)) {
            return Optional.of(explicit);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = variants.get(0).path("properties").fields(); it.hasNext(); ) {
            String candidate = it.next().getKey();
            if (pinnedInEvery(variants, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean pinnedInEvery(List<JsonNode> variants, String property) {
        return variants.stream().allMatch(v -> singleValueOf(v.path("properties").path(property)).isPresent());
    }

    /** The single value a variant pins a property to: {@code const}, or a one-element {@code enum}. */
    static Optional<String> singleValueOf(JsonNode propSchema) {
        if (propSchema.has("const")) {
            return Optional.of(propSchema.get("const").asText());
        }
        JsonNode en = propSchema.path("enum");
        return en.isArray() && en.size() == 1 ? Optional.of(en.get(0).asText()) : Optional.empty();
    }

    private static List<String> discriminatorValues(List<JsonNode> variants, String disc) {
        List<String> values = new ArrayList<>();
        for (JsonNode variant : variants) {
            singleValueOf(variant.path("properties").path(disc))
                    .filter(v -> !values.contains(v))
                    .ifPresent(values::add);
        }
        return values;
    }

    private static void collectDiscriminatorRules(List<JsonNode> variants, String disc, String groupPath, Ctx ctx) {
        for (JsonNode variant : variants) {
            Optional<String> value = singleValueOf(variant.path("properties").path(disc));
            if (value.isEmpty()) {
                continue;
            }
            JsonNode props = variant.path("properties");
            for (JsonNode reqNode : variant.path("required")) {
                String req = reqNode.asText();
                if (req.equals(disc)) {
                    continue;   // the discriminator itself is the enum, not a dependent
                }
                if (!ruleEligibleScalar(props.path(req))) {
                    ctx.report().diagnostic("discriminator rule for '" + req + "' (required when " + disc + "="
                            + value.get() + ") not synthesized — only scalar string/integer/boolean/enum required "
                            + "fields are supported; enforce it by hand");
                    continue;
                }
                ctx.ruleNeeds().add(new RuleSynthesis.DiscriminatedRequired(groupPath, disc, value.get(), req, true));
            }
        }
    }

    /** A required field a discriminator rule can reference: a reliably-imported scalar (a {@code FieldRef}). */
    private static boolean ruleEligibleScalar(JsonNode propSchema) {
        if (propSchema.has("enum")) {
            return true;
        }
        return switch (propSchema.path("type").asText("")) {
            case "string", "integer", "boolean" -> true;   // 'number' import is profile-dependent → excluded
            default -> false;
        };
    }
}
