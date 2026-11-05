package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.ConditionalRuleNeeds.valueConstraintOf;
import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.ANNOTATION_KEYWORDS;
import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.MAP_KEY_FIELD;
import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.ROW_FIELD;
import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.populate;
import static io.github.mbackschat.a12.dm.schemakit.ScalarFields.addScalar;
import static io.github.mbackschat.a12.dm.schemakit.ScalarFields.configureStringField;
import static io.github.mbackschat.a12.dm.schemakit.ScalarFields.saturatedInt;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.Ctx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * How {@link JsonSchemaImporter} maps arrays and maps: a scalar array to a multi-select, a value list or a
 * repeatable single-field group, a map to a repeatable {@code {key, value}} group, and the item-count and
 * {@code contains}/{@code not} assertions on them to rule needs.
 */
final class CollectionFields {

    private CollectionFields() {
    }

    /** {@code contains}/{@code minContains}/{@code maxContains} → a filtered-count rule when the {@code contains}
     *  predicate is simple ({@code {const}} on a scalar array, or {@code {properties:{f:{const}}}} on an object
     *  array); a non-simple predicate is reported, not silently dropped (SCHEMAKIT-SPEC §4f). */
    static void collectContains(JsonNode arraySchema, String groupPath, boolean required, Ctx ctx) {
        if (!arraySchema.has("contains")) {
            return;
        }
        boolean objectArray = "object".equals(arraySchema.path("items").path("type").asText(""));
        Optional<List<RuleSynthesis.Clause>> clauses = containsClauses(arraySchema.get("contains"), objectArray);
        if (clauses.isEmpty()) {
            ctx.report().omit(groupPath + "/contains", "array '" + groupPath + "' has a 'contains' with a non-mappable predicate — "
                    + "omitted (each clause must be a single const/enum/min/max/pattern on a scalar array or an "
                    + "object array's property, without other assertions); retain source-schema validation");
            return;
        }
        int minContains = arraySchema.path("minContains").isIntegralNumber() ? saturatedInt(arraySchema.get("minContains")) : 1;
        if (minContains >= 1 && canEnforceArrayMinimum(groupPath, "minContains", required, ctx)) {
            ctx.ruleNeeds().add(new RuleSynthesis.ContainsCount(groupPath, clauses.get(), objectArray
                    ? RuleSynthesis.ContainsSubject.OBJECT_PROPERTIES : RuleSynthesis.ContainsSubject.SCALAR_VALUE, true, minContains));
        }
        if (arraySchema.path("maxContains").isIntegralNumber()) {
            ctx.ruleNeeds().add(new RuleSynthesis.ContainsCount(groupPath, clauses.get(), objectArray
                    ? RuleSynthesis.ContainsSubject.OBJECT_PROPERTIES : RuleSynthesis.ContainsSubject.SCALAR_VALUE, false,
                    saturatedInt(arraySchema.get("maxContains"))));
        }
    }

    /** The {@code contains} predicate as a conjunction of clauses (each a row field + a simple value constraint —
     *  const/enum/min/max/pattern), or empty when a clause isn't mappable. A scalar array constrains the row
     *  {@code value} field; an object array constrains one or more properties — descending into nested objects
     *  (a non-repeatable subgroup of the row) so {@code payment.method} becomes the clause field
     *  {@code payment/method} (ALL clauses must be mappable). */
    private static Optional<List<RuleSynthesis.Clause>> containsClauses(JsonNode contains, boolean objectArray) {
        if (objectArray) {
            JsonNode props = contains.path("properties");
            if (!hasOnlyAssertion(contains, "properties") || !props.isObject() || props.isEmpty()) {
                return Optional.empty();
            }
            return objectClauses(props, "");
        }
        return valueConstraintOf(contains).map(vc -> List.of(new RuleSynthesis.Clause(ROW_FIELD, vc)));
    }

    /** Flatten a {@code contains}-predicate {@code properties} object into clauses, descending one or more levels
     *  into nested objects (each a non-repeatable subgroup of the row) so the clause field carries the relative
     *  path ({@code origin/address/country}). A property that is neither a simple value constraint nor a nested
     *  object — a nested <em>array</em> (a deeper repetition) or an arbitrary subschema — makes the whole
     *  predicate non-mappable (returns empty), so the {@code contains} is reported rather than half-synthesized
     *  ([`SCHEMAKIT-GAPS.md`] §3: nested aggregation stays declined). */
    private static Optional<List<RuleSynthesis.Clause>> objectClauses(JsonNode props, String prefix) {
        List<RuleSynthesis.Clause> clauses = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String field = prefix + e.getKey();
            Optional<RuleSynthesis.ValueConstraint> vc = valueConstraintOf(e.getValue());
            if (vc.isPresent()) {
                clauses.add(new RuleSynthesis.Clause(field, vc.get()));
            } else if (hasOnlyAssertion(e.getValue(), "properties")
                    && e.getValue().path("properties").isObject() && !e.getValue().path("properties").isEmpty()) {
                Optional<List<RuleSynthesis.Clause>> nested = objectClauses(e.getValue().get("properties"), field + "/");
                if (nested.isEmpty()) {
                    return Optional.empty();
                }
                clauses.addAll(nested.get());
            } else {
                return Optional.empty();   // nested array / arbitrary subschema → non-mappable predicate
            }
        }
        return clauses.isEmpty() ? Optional.empty() : Optional.of(clauses);
    }

    /**
     * The repeatability cap for a container: the schema's bound ({@code maxItems}/{@code maxProperties}) when
     * ≥ 2, else the profile's {@code defaultRepeatCap} (best-effort — so an unbounded array/map still imports
     * and the importer can reach into its nested structure), else {@code -1} = no cap (the caller omits). A
     * fallback cap is flagged in the report (it is a chosen bound, not from the schema).
     */
    static int resolvedCap(JsonNode bound, String name, String boundKey, Ctx ctx) {
        if (bound != null && saturatedInt(bound) >= 2) {
            return saturatedInt(bound);
        }
        int fallback = ctx.profile().defaultRepeatCap();
        if (fallback >= 2) {
            ctx.report().diagnostic("'" + name + "' has no " + boundKey + " — capped at the default " + fallback
                    + " rows (a chosen bound, not from the schema); set " + boundKey + ", --default-repeat-cap, or --strict");
            return fallback;
        }
        return -1;
    }

    /**
     * {@code not: {const}} / {@code not: {enum}} → a forbidding rule (SCHEMAKIT-SPEC §4); any other {@code not}
     * shape is reported, not silently dropped. The synthesis resolves the field's kind post-build (and reports
     * if the kind can't carry the constraint), so this just records the request.
     */
    static void collectNot(String name, JsonNode schema, String path, Ctx ctx) {
        JsonNode not = schema.path("not");
        if (not.isMissingNode()) {
            return;
        }
        if (hasOnlyAssertion(not, "const")) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenConst(path, not.get("const").asText()));
        } else if (hasOnlyAssertion(not, "enum") && not.get("enum").isArray() && !not.get("enum").isEmpty()) {
            List<String> values = new ArrayList<>();
            not.get("enum").forEach(v -> values.add(v.asText()));
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenEnum(path, values));
        } else if (hasOnlyAssertion(not, "pattern") && not.hasNonNull("pattern")) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenPattern(path, not.get("pattern").asText()));
        } else if (hasOnlyAssertion(not, "minimum") && not.get("minimum").isNumber()) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenBound(path, true, not.get("minimum").asText()));
        } else if (hasOnlyAssertion(not, "maximum") && not.get("maximum").isNumber()) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenBound(path, false, not.get("maximum").asText()));
        } else if (hasOnlyAssertion(not, "minLength") && not.get("minLength").isNumber()) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenLength(path, true, saturatedInt(not.get("minLength"))));
        } else if (hasOnlyAssertion(not, "maxLength") && not.get("maxLength").isNumber()) {
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenLength(path, false, saturatedInt(not.get("maxLength"))));
        } else {
            ctx.report().diagnostic("property '" + name + "' uses a nested or compound 'not', or one that isn't a simple "
                    + "const/enum/pattern/minimum/maximum/minLength/maxLength — not mapped; add the constraint by hand");
        }
    }

    static boolean hasOnlyAssertion(JsonNode node, String keyword) {
        if (!node.isObject() || !node.has(keyword)) {
            return false;
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!name.equals(keyword) && !ANNOTATION_KEYWORDS.contains(name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A12 readers erase absent-versus-empty arrays (SchemaEmptyPatternRuntimeTest); only a required root
     * property has an unconditional applicability witness in this synthesis pass.
     */
    private static boolean canEnforceArrayMinimum(String path, String keyword, boolean required, Ctx ctx) {
        if (required) return true;
        ctx.report().omit(path + "/" + keyword, keyword + " on '" + path + "' omitted — the array or an ancestor "
                + "does not have unconditional root-property applicability. A12 Documents normalize an absent array and an empty array to the same "
                + "state; an unconditional minimum could reject permitted absence. Keep this constraint in JSON Schema validation.");
        return false;
    }

    /** {@code minItems} → a synthesized {@code NumberOfFilledGroups(G*) < N} rule (SCHEMAKIT-SPEC §4f). The Need
     *  is collected here; {@link RuleSynthesis} resolves the actual row anchor post-build and skips gracefully if
     *  the array did not import as a repeatable group (e.g. no cap). {@code minItems <= 0} is a no-op (always met). */
    static void collectMinItems(JsonNode schema, String path, boolean required, Ctx ctx) {
        if (schema.has("minItems") && saturatedInt(schema.get("minItems")) >= 1
                && canEnforceArrayMinimum(path, "minItems", required, ctx)) {
            ctx.ruleNeeds().add(new RuleSynthesis.MinItemsCount(path, saturatedInt(schema.get("minItems"))));
        }
    }

    /**
     * An {@code array} of <em>scalar</em> items — no single a12 home, so the {@link MappingProfile} picks a
     * target (SCHEMAKIT-SPEC §4b). All three non-{@code OMIT} strategies need a bounded cap ({@code maxItems
     * ≥ 2}; a12 repeatability is a bounded max ≥ 2) — {@link #withCap} omits when absent. Each row holds the
     * one value field named {@value #ROW_FIELD}. Omit, never block: an unmappable combination is skipped + a to-do.
     */
    static void addScalarArray(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                       JsonNode items, String path, Ctx ctx) {
        switch (ctx.profile().arrayOfScalar()) {
            case OMIT -> ctx.report().omit(path, "array '" + name + "' has scalar items — omitted. TO DO: add it "
                    + "by hand as a multi-select group or a value-list field (proposal §9), or set the "
                    + "array-of-scalar strategy in the MappingProfile (MULTI_SELECT / VALUE_LIST / REPEATABLE_SINGLE)");
            case MULTI_SELECT -> withCap(schema, name, path, ctx, max -> asMultiSelect(g, name, items, max, path, ctx));
            case VALUE_LIST -> withCap(schema, name, path, ctx, max -> asValueList(g, name, items, max, path, ctx));
            case REPEATABLE_SINGLE -> withCap(schema, name, path, ctx, max -> asRepeatableSingle(g, name, items, max, path, ctx));
        }
    }

    /** Run {@code build} with the array's repeatability cap ({@link #resolvedCap}), or omit when there is none. */
    private static void withCap(JsonNode schema, String name, String path, Ctx ctx, IntConsumer build) {
        int max = resolvedCap(schema.get("maxItems"), name, "maxItems", ctx);
        if (max < 0) {
            ctx.report().omit(path, "array '" + name + "' of scalars has no maxItems and no default cap — "
                    + "omitted: an a12 repeatable group needs a bounded max ≥ 2. TO DO: set maxItems, raise "
                    + "--default-repeat-cap, or add the group by hand");
            return;
        }
        build.accept(max);
    }

    private enum EmptyStringAdmission { ALLOWED, EXCLUDED, UNRESOLVED }

    private static EmptyStringAdmission emptyStringAdmission(JsonNode items) {
        String type = items.path("type").asText("");
        if (!"string".equals(type) && !(type.isEmpty() && hasEnum(items))) return EmptyStringAdmission.EXCLUDED;
        if (saturatedInt(items.path("minLength")) >= 1
                || (hasEnum(items) && enumArrayValues(items).stream().noneMatch(String::isEmpty))
                || (items.has("const") && !items.path("const").asText().isEmpty())) return EmptyStringAdmission.EXCLUDED;
        if ((items.has("pattern") && !items.path("pattern").asText().isEmpty()) || items.has("format")) {
            return EmptyStringAdmission.UNRESOLVED;
        }
        return EmptyStringAdmission.ALLOWED;
    }

    /** MULTI_SELECT → an a12 multi-select group of closed enum choices; needs {@code items.enum}. */
    private static void asMultiSelect(DocumentModelDraft.GroupBuilder g, String name, JsonNode items,
                                      int max, String path, Ctx ctx) {
        if (!hasEnum(items)) {
            ctx.report().omit(path, "array '" + name + "' under MULTI_SELECT needs a closed item set (items.enum) "
                    + "— omitted. TO DO: add items.enum, or choose the VALUE_LIST / REPEATABLE_SINGLE strategy");
            return;
        }
        if (emptyStringAdmission(items) != EmptyStringAdmission.EXCLUDED) {
            ctx.report().omit(path, "MULTI_SELECT cannot preserve or establish empty string item admission with its required value field — choose REPEATABLE_SINGLE for explicit empty-admitting items or retain source-schema validation");
            return;
        }
        g.multiSelectGroup(name, m -> m.enumField(ROW_FIELD, e -> EnumLabelMapping.addValues(e, enumArrayValues(items),
                items, name, ctx.profile().enumLabels(), ctx.report())).maxRows(max));
        ctx.report().structure(path, "multi-select group (enum, max " + max + ")");
    }

    /** VALUE_LIST → a multi-select group with a free-text string field; needs string items. */
    private static void asValueList(DocumentModelDraft.GroupBuilder g, String name, JsonNode items,
                                    int max, String path, Ctx ctx) {
        if (!"string".equals(items.path("type").asText(""))) {
            ctx.report().omit(path, "array '" + name + "' under VALUE_LIST needs string items — omitted. "
                    + "TO DO: choose the REPEATABLE_SINGLE strategy for non-string scalars");
            return;
        }
        if (emptyStringAdmission(items) != EmptyStringAdmission.EXCLUDED) {
            ctx.report().omit(path, "VALUE_LIST cannot preserve or establish empty string item admission with its required value field — choose REPEATABLE_SINGLE for explicit empty-admitting items or retain source-schema validation");
            return;
        }
        String unsupported = unsupportedValueListAssertion(items);
        if (unsupported != null) {
            ctx.report().omit(path, "VALUE_LIST does not map item assertion '" + unsupported
                    + "' onto its STRING target — choose MULTI_SELECT for a closed enum or REPEATABLE_SINGLE, or retain source-schema validation");
            return;
        }
        g.multiSelectGroup(name, m -> m.stringField(ROW_FIELD, s -> configureStringField(s, items, true, path, ctx)).maxRows(max));
        ctx.report().structure(path, "value-list (string multi-select, max " + max + ")");
    }

    private static String unsupportedValueListAssertion(JsonNode items) {
        for (Iterator<String> names = items.fieldNames(); names.hasNext();) {
            String name = names.next();
            switch (name) {
                case "type", "minLength", "maxLength", "pattern" -> { }
                default -> { if (!ANNOTATION_KEYWORDS.contains(name)) return name; }
            }
        }
        return null;
    }

    /** REPEATABLE_SINGLE → a plain repeatable group with one field of the item's kind (the general form). */
    private static void asRepeatableSingle(DocumentModelDraft.GroupBuilder g, String name, JsonNode items,
                                           int max, String path, Ctx ctx) {
        if (scalarWouldOmit(items, ctx)) {
            ctx.report().omit(path, "array '" + name + "' has items of type '"
                    + (items.path("type").asText("").isEmpty() ? "<none>" : items.path("type").asText(""))
                    + "' not importable under the current profile — omitted (an empty repeatable group is "
                    + "invalid). TO DO: set the numberScale strategy for number items, or add the field by hand");
            return;
        }
        EmptyStringAdmission empty = emptyStringAdmission(items);
        if (empty == EmptyStringAdmission.UNRESOLVED) {
            ctx.report().omit(path, "scalar string item empty admission is not established for its pattern/format — retain source-schema validation");
            return;
        }
        g.group(name, sub -> {
            sub.repeatable(max);
            addScalar(sub, ROW_FIELD, items, empty == EmptyStringAdmission.EXCLUDED, path + "/" + ROW_FIELD, ctx);
        });
        ctx.report().structure(path, "repeatable single-field group (max " + max + ")");
    }

    /** Would {@link ScalarFields#addScalar} omit this item kind under the profile? (Used to avoid an empty repeatable group.) */
    private static boolean scalarWouldOmit(JsonNode items, Ctx ctx) {
        if (hasEnum(items)) {
            return false;
        }
        return switch (items.path("type").asText("")) {
            case "string", "boolean", "integer" -> false;
            case "number" -> ctx.profile().numberScale() == MappingProfile.NumberScale.OMIT;
            default -> true;   // unknown / unsupported scalar kind
        };
    }

    private static boolean hasEnum(JsonNode items) {
        return items.has("enum") && items.get("enum").isArray() && !items.get("enum").isEmpty();
    }

    static List<String> enumArrayValues(JsonNode items) {
        List<String> values = new ArrayList<>();
        items.get("enum").forEach(value -> values.add(value.asText()));
        return values;
    }

    /** A JSON object used as a MAP: no fixed property set, just a value schema for arbitrary keys. */
    static boolean isMap(JsonNode schema) {
        boolean hasProperties = schema.path("properties").isObject() && schema.get("properties").size() > 0;
        return !hasProperties && hasMapExtras(schema);
    }

    /** Does the object carry dynamic-key extras ({@code additionalProperties} schema / {@code patternProperties})? */
    static boolean hasMapExtras(JsonNode schema) {
        return schema.path("additionalProperties").isObject() || hasPatternProperties(schema);
    }

    private static boolean hasPatternProperties(JsonNode schema) {
        return schema.path("patternProperties").isObject() && !schema.get("patternProperties").isEmpty();
    }

    /** The value schema of a map: {@code additionalProperties} if a schema, else the first {@code patternProperties}. */
    private static JsonNode mapValueSchema(JsonNode schema) {
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isObject()) {
            return additional;
        }
        return schema.path("patternProperties").fields().next().getValue();   // hasPatternProperties guarantees one
    }

    /**
     * A map → a repeatable {@code {key, value}} group (SCHEMAKIT-SPEC §4b): each row is one entry, {@value
     * #MAP_KEY_FIELD} holds the key and {@value #ROW_FIELD} the scalar value (an object value's fields are
     * inlined alongside {@value #MAP_KEY_FIELD}). The cap comes from {@code maxProperties} (≥ 2; a12
     * repeatability is a bounded max ≥ 2) — omitted when absent. Omit, never block.
     */
    static void addMap(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                               String path, Ctx ctx) {
        switch (ctx.profile().mapStrategy()) {
            case OMIT -> ctx.report().omit(path, "object '" + name + "' is a map (additionalProperties/"
                    + "patternProperties) — omitted. TO DO: add a repeatable {key, value} group by hand, or set "
                    + "the map strategy in the MappingProfile (REPEATABLE_KEY_VALUE)");
            case IGNORE -> ctx.report().diagnostic("object '" + name + "' is a map — ignored per the IGNORE map "
                    + "strategy; no structure emitted for its dynamic keys");
            case REPEATABLE_KEY_VALUE -> withMapCap(schema, name, path, ctx,
                    max -> buildKeyValueGroup(g, name, schema, max, path, ctx));
        }
    }

    /** Run {@code build} with the map's repeatability cap ({@link #resolvedCap}), or omit when there is none. */
    private static void withMapCap(JsonNode schema, String name, String path, Ctx ctx, IntConsumer build) {
        int max = resolvedCap(schema.get("maxProperties"), name, "maxProperties", ctx);
        if (max < 0) {
            ctx.report().omit(path, "map '" + name + "' has no maxProperties and no default cap — omitted: an a12 "
                    + "repeatable group needs a bounded max ≥ 2. TO DO: set maxProperties, raise --default-repeat-cap, or add by hand");
            return;
        }
        build.accept(max);
    }

    private static void buildKeyValueGroup(DocumentModelDraft.GroupBuilder g, String name, JsonNode schema,
                                           int max, String path, Ctx ctx) {
        JsonNode value = mapValueSchema(schema);
        g.group(name, sub -> {
            sub.repeatable(max);
            sub.field(MAP_KEY_FIELD, FieldKind.STRING, Requiredness.REQUIRED);   // the entry key
            if ("object".equals(value.path("type").asText(""))) {
                populate(sub, value, path, ctx);   // inline the value object's fields alongside the key
            } else {
                addScalar(sub, ROW_FIELD, value, true, path + "/" + ROW_FIELD, ctx);
            }
        });
        ctx.report().structure(path, "map → repeatable {key, value} group (max " + max + ")");
    }
}
