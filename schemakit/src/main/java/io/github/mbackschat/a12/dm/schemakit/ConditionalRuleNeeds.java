package io.github.mbackschat.a12.dm.schemakit;

import static io.github.mbackschat.a12.dm.schemakit.CollectionFields.hasOnlyAssertion;
import static io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.ANNOTATION_KEYWORDS;
import static io.github.mbackschat.a12.dm.schemakit.UnionGroups.singleValueOf;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter.Ctx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The object-level assertions {@link JsonSchemaImporter} carries as Tier-A+ rule needs rather than structure:
 * {@code not}, {@code if}/{@code then}/{@code else} with its conditional {@code required} and value constraints.
 */
final class ConditionalRuleNeeds {

    private ConditionalRuleNeeds() {
    }

    /** Object-level {@code not {required:[…]}} → a rule firing when the listed fields are ALL present (they must
     *  not be together). A nested/compound object {@code not} is reported, not silently dropped (SCHEMAKIT-SPEC §4f). */
    static void collectObjectNot(JsonNode objectSchema, String groupPath, Ctx ctx) {
        JsonNode not = objectSchema.path("not");
        if (not.isMissingNode()) {
            return;
        }
        JsonNode required = not.path("required");
        if (hasOnlyAssertion(not, "required") && required.isArray() && !required.isEmpty()) {
            List<String> fields = new ArrayList<>();
            required.forEach(r -> fields.add(r.asText()));
            ctx.ruleNeeds().add(new RuleSynthesis.ForbiddenAllPresent(groupPath, fields));
        } else {
            ctx.report().diagnostic("object 'not' at '" + groupPath + "' isn't a simple 'not {required:[…]}' "
                    + "(e.g. a nested or compound 'not') — not mapped; add it by hand");
        }
    }

    /**
     * {@code if}/{@code then} (SCHEMAKIT-SPEC §4): the tractable shape — {@code if} pins a single property to a
     * value and {@code then} requires fields — maps to a conditional-requiredness rule per dependent (the same
     * synthesis as a union discriminator). Anything else (arbitrary {@code if}/{@code then} schemas, {@code
     * then} value constraints, {@code else}) is reported, not silently dropped.
     */
    static void collectIfThen(JsonNode objectSchema, String groupPath, Ctx ctx) {
        if (!objectSchema.has("if")) {
            return;
        }
        List<RuleSynthesis.FieldValue> conditions = ifConditions(objectSchema.path("if").path("properties"));
        if (conditions.isEmpty()) {
            ctx.report().diagnostic("if/then at '" + groupPath + "' is not a simple 'if <field>=<value> [and …]' "
                    + "shape (each if-property pinned to a value) — not mapped; add the conditional rule by hand");
            return;
        }
        int before = ctx.ruleNeeds().size();
        if (conditions.size() == 1) {   // single-property if: then + else required both map
            RuleSynthesis.FieldValue cond = conditions.get(0);
            addConditionalRequired(objectSchema.path("then").path("required"), groupPath, cond.field(), cond.value(), true, ctx);
            addConditionalRequired(objectSchema.path("else").path("required"), groupPath, cond.field(), cond.value(), false, ctx);
        } else {   // multi-property if: then required is a conjunction; else (a disjunction) stays reported
            addMultiConditionalRequired(objectSchema.path("then").path("required"), groupPath, conditions, ctx);
            if (objectSchema.path("else").has("required")) {
                ctx.report().diagnostic("multi-property if/else at '" + groupPath + "': else required[] is NOT mapped "
                        + "— the negation of a conjunction is a disjunction; add the else rule by hand");
            }
        }
        collectThenValueConstraints(objectSchema.path("then"), conditions, groupPath, ctx);
        collectElseValueConstraints(objectSchema.path("else"), conditions, groupPath, ctx);
        if (ctx.ruleNeeds().size() == before) {
            ctx.report().diagnostic("if/then at '" + groupPath + "' — nothing mappable (no then/else required[] or "
                    + "simple value-constraints); add the conditional rule by hand");
        }
    }

    /** The {@code if}-conditions: each {@code if.properties} entry pinned to a single value, else empty (the
     *  shape isn't a simple {@code <field>=<value>} conjunction). */
    private static List<RuleSynthesis.FieldValue> ifConditions(JsonNode ifProps) {
        if (!ifProps.isObject() || ifProps.isEmpty()) {
            return List.of();
        }
        List<RuleSynthesis.FieldValue> conds = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = ifProps.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            Optional<String> v = singleValueOf(e.getValue());
            if (v.isEmpty()) {
                return List.of();   // a non-pinned condition → not a simple if
            }
            conds.add(new RuleSynthesis.FieldValue(e.getKey(), v.get()));
        }
        return conds;
    }

    /** Each single-property {@code required} entry (other than the condition field) → a conditional-requiredness
     *  rule fired on the condition ({@code whenEquals=true} = then) or its negation ({@code false} = else). */
    private static void addConditionalRequired(JsonNode required, String groupPath, String condField,
                                               String value, boolean whenEquals, Ctx ctx) {
        if (!required.isArray()) {
            return;
        }
        for (JsonNode dependent : required) {
            String reqField = dependent.asText();
            if (!reqField.equals(condField)) {
                ctx.ruleNeeds().add(new RuleSynthesis.DiscriminatedRequired(groupPath, condField, value, reqField, whenEquals));
            }
        }
    }

    /** Each {@code then.required} entry (other than a condition field) → a conjoined conditional-requiredness rule. */
    private static void addMultiConditionalRequired(JsonNode required, String groupPath,
                                                    List<RuleSynthesis.FieldValue> conditions, Ctx ctx) {
        if (!required.isArray()) {
            return;
        }
        for (JsonNode dependent : required) {
            String reqField = dependent.asText();
            if (conditions.stream().noneMatch(c -> c.field().equals(reqField))) {
                ctx.ruleNeeds().add(new RuleSynthesis.MultiConditionalRequired(groupPath, conditions, reqField));
            }
        }
    }

    /** {@code then.properties} value-constraints (SCHEMAKIT-SPEC §4f): each target field with a simple
     *  {@code const}/{@code enum}/numeric-bound constraint → a rule that fires when the conditions hold and the
     *  field violates it. A non-tractable then-constraint (e.g. {@code pattern}) is reported, not silently dropped. */
    private static void collectThenValueConstraints(JsonNode thenNode, List<RuleSynthesis.FieldValue> conditions,
                                                    String groupPath, Ctx ctx) {
        JsonNode props = thenNode.path("properties");
        if (!props.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            Optional<RuleSynthesis.ValueConstraint> vc = valueConstraintOf(e.getValue());
            if (vc.isPresent()) {
                ctx.ruleNeeds().add(new RuleSynthesis.ThenConstrained(groupPath, conditions, e.getKey(), vc.get()));
            } else {
                ctx.report().omit(groupPath + "/then/" + e.getKey(), "if/then at '" + groupPath + "': then constraint on '" + e.getKey()
                        + "' is not a single const/enum/min/max/pattern assertion — omitted; retain source-schema validation");
            }
        }
    }

    /** {@code else.properties} value-constraints (SCHEMAKIT-SPEC §4f): each target field with a simple constraint →
     *  a rule firing when the {@code if} did NOT hold (the OR of the per-condition negations) and the field
     *  violates the constraint. Works for single- and multi-property {@code if}. */
    private static void collectElseValueConstraints(JsonNode elseNode, List<RuleSynthesis.FieldValue> conditions,
                                                    String groupPath, Ctx ctx) {
        JsonNode props = elseNode.path("properties");
        if (!props.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = props.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            Optional<RuleSynthesis.ValueConstraint> vc = valueConstraintOf(e.getValue());
            if (vc.isPresent()) {
                ctx.ruleNeeds().add(new RuleSynthesis.ElseConstrained(groupPath, conditions, e.getKey(), vc.get()));
            } else {
                ctx.report().omit(groupPath + "/else/" + e.getKey(), "if/else at '" + groupPath + "': else constraint on '" + e.getKey()
                        + "' is not a single const/enum/min/max/pattern assertion — omitted; retain source-schema validation");
            }
        }
    }

    /** A simple {@code then}/{@code else} value constraint, or empty (no direct violation condition — reported). */
    static Optional<RuleSynthesis.ValueConstraint> valueConstraintOf(JsonNode schema) {
        int assertions = schema.size();
        for (String annotation : ANNOTATION_KEYWORDS) {
            if (schema.has(annotation)) assertions--;
        }
        if (assertions != 1) return Optional.empty();
        if (schema.has("const")) {
            return Optional.of(RuleSynthesis.ValueConstraint.scalar(
                    RuleSynthesis.ConstraintKind.CONST, schema.get("const").asText()));
        }
        if (schema.path("enum").isArray() && !schema.get("enum").isEmpty()) {
            List<String> values = new ArrayList<>();
            schema.get("enum").forEach(v -> values.add(v.asText()));
            return Optional.of(RuleSynthesis.ValueConstraint.ofEnum(values));
        }
        if (schema.path("exclusiveMinimum").isNumber()) {
            return Optional.of(RuleSynthesis.ValueConstraint.scalar(
                    RuleSynthesis.ConstraintKind.EXCLUSIVE_MIN, schema.get("exclusiveMinimum").asText()));
        }
        if (schema.path("exclusiveMaximum").isNumber()) {
            return Optional.of(RuleSynthesis.ValueConstraint.scalar(
                    RuleSynthesis.ConstraintKind.EXCLUSIVE_MAX, schema.get("exclusiveMaximum").asText()));
        }
        if (schema.path("minimum").isNumber()) {
            return Optional.of(RuleSynthesis.ValueConstraint.scalar(
                    RuleSynthesis.ConstraintKind.MINIMUM, schema.get("minimum").asText()));
        }
        if (schema.path("maximum").isNumber()) {
            return Optional.of(RuleSynthesis.ValueConstraint.scalar(
                    RuleSynthesis.ConstraintKind.MAXIMUM, schema.get("maximum").asText()));
        }
        if (schema.hasNonNull("pattern")) {   // then {g:{pattern:P}} → g must match P (violation = PatternViolated)
            return Optional.of(RuleSynthesis.ValueConstraint.scalar(
                    RuleSynthesis.ConstraintKind.PATTERN, schema.get("pattern").asText()));
        }
        return Optional.empty();
    }
}
