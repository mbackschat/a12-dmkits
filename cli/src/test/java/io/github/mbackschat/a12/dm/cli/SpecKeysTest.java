package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.cli.field.FieldSpec;
import io.github.mbackschat.a12.dm.cli.computation.ComputationSpec;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * {@link SpecKeys} — strict, schema-sourced key validation. The headline is the anti-silent-drop guarantee:
 * a typo'd or out-of-scope key (top-level OR nested) is reported as {@code RK_UNKNOWN_ARG}, not parsed-and-
 * ignored. Plus the no-drift lock: the field-add schema (the enforcement source) must list exactly the keys
 * {@link FieldSpec} accepts, or valid input would be rejected / a typo would slip through.
 */
class SpecKeysTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static List<Diagnostic> validateFieldAdd(String json) throws Exception {
        return SpecKeys.forSchema("field-add").validate(M.readTree(json));
    }

    @Test
    void aCleanSpecHasNoFindings() throws Exception {
        assertTrue(validateFieldAdd("""
                {"group":"/G","name":"F","kind":"STRING","string":{"pattern":"x","patternMessage":"m"},"label":{"en_US":"L"}}""")
                .isEmpty(), "a spec using only declared keys is clean");
    }

    @Test
    void flagsMissingAndNullRequiredPropertiesFromThePublishedSchema() throws Exception {
        for (String json : List.of(
                "{\"name\":\"F\"}",
                "{\"group\":null,\"name\":\"F\"}")) {
            List<Diagnostic> found = validateFieldAdd(json);
            assertEquals(1, found.size(), () -> "one missing required property: " + found);
            assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
            assertEquals("group", found.get(0).where().arg().orElse(null),
                    () -> "the required-property locator comes from the schema: " + found.get(0));
        }
    }

    @Test
    void anOptionalPropertyMayBeAbsentButMayNotViolateItsDeclaredType() throws Exception {
        assertTrue(validateFieldAdd("{\"group\":\"/G\",\"name\":\"F\"}").isEmpty(),
                "optional means absent is legal");

        List<Diagnostic> found = validateFieldAdd("{\"group\":\"/G\",\"name\":\"F\",\"global\":null}");

        assertEquals(1, found.size(), () -> "present null does not satisfy the published boolean type: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
        assertEquals("global", found.get(0).where().arg().orElse(null));
    }

    @Test
    void optionalBatchArgsMustBeAnArrayWhenPresent() throws Exception {
        List<Diagnostic> found = SpecKeys.forSchema("batch").validate(M.readTree(
                "[{\"verb\":\"manifest\",\"args\":null}]"));

        assertEquals(1, found.size(), () -> "present batch args must conform instead of defaulting: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
        assertEquals("[]/args", found.get(0).where().arg().orElse(null));
    }

    @Test
    void flagsARequiredPropertyInsideEveryArrayItem() throws Exception {
        List<Diagnostic> found = SpecKeys.forSchema("batch").validate(M.readTree("[{\"verb\":\"manifest\"},{}]"));

        assertEquals(1, found.size(), () -> "the malformed second item is rejected before dispatch: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
        assertEquals("[]/verb", found.get(0).where().arg().orElse(null),
                () -> "the item requirement is attributed to verb: " + found.get(0));
    }

    @Test
    void validatesDynamicObjectValuesAgainstSchemaValuedAdditionalProperties() throws Exception {
        List<Diagnostic> found = SpecKeys.forSchema("profile#/$defs/distributionMap").validate(M.readTree("""
                {"1":{"n":1,"min":1,"max":1,"mean":1,"median":1,"p90":1}}
                """));

        assertEquals(1, found.size(), () -> "the dynamic map value must satisfy the distribution schema: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
        assertEquals("1/buckets", found.get(0).where().arg().orElse(null));
    }

    @Test
    void flagsAnUnknownTopLevelKeyWithADidYouMean() throws Exception {
        List<Diagnostic> found = validateFieldAdd("""
                {"group":"/G","name":"F","requried":true}""");                       // typo: requried
        assertEquals(1, found.size(), () -> "exactly one unknown key: " + found);
        assertEquals("RK_UNKNOWN_ARG", found.get(0).code().orElse(null));
        assertTrue(found.get(0).fix().orElse("").contains("required"), "did-you-mean points at 'required'");
    }

    @Test
    void flagsAnUnknownNestedConfigKey() throws Exception {
        List<Diagnostic> found = validateFieldAdd("""
                {"group":"/G","name":"F","kind":"STRING","string":{"maxLenght":5}}""");   // typo inside string
        assertEquals(1, found.size(), () -> "the nested typo is flagged: " + found);
        assertEquals("RK_UNKNOWN_ARG", found.get(0).code().orElse(null));
    }

    @Test
    void leavesOpenMapsUnrestricted() throws Exception {
        // label is a locale→text map and annotations a name→value map — their arbitrary keys must NOT be flagged
        assertTrue(validateFieldAdd("""
                {"group":"/G","name":"F","label":{"en_US":"a","de_DE":"b"},"annotations":{"ui:widget":"dropdown"}}""")
                .isEmpty(), "open maps (label/annotations) carry no declared properties → keys unrestricted");
    }

    // --- value-shape validation: a recognized key carrying a wrong-typed value (the sibling of the
    //     unknown-key gate above). SpecKeys validates key NAMES and now their VALUE SHAPES, schema-driven,
    //     for every served spec — so a wrong shape is a structured RK_INVALID_VALUE, not a plain `fail`. ----

    @Test
    void requiredWithAKernelTermIsRejectedAsInvalidValue() throws Exception {
        // "MANDATORY" is the kernel's own term — exactly what an agent reaches for — but `required` only
        // accepts true/false/"ifParentPresent". The tightened enum schema makes that machine-checkable.
        List<Diagnostic> found = validateFieldAdd("""
                {"group":"/G","name":"F","required":"MANDATORY"}""");
        assertEquals(1, found.size(), () -> "exactly one invalid value: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
        assertEquals("required", found.get(0).where().arg().orElse(null),
                () -> "the where points at 'required': " + found.get(0));
    }

    @Test
    void everyValidRequirednessFormIsClean() throws Exception {
        for (String form : List.of("true", "false", "\"ifParentPresent\"")) {
            assertTrue(validateFieldAdd("{\"group\":\"/G\",\"name\":\"F\",\"required\":" + form + "}").isEmpty(),
                    () -> "required:" + form + " is a valid form and must be clean");
        }
    }

    @Test
    void annotationsAsAnArrayIsRejectedAsInvalidValue() throws Exception {
        // the schema declares annotations as an object { name: value }; the array form used to coerce to empty
        List<Diagnostic> found = validateFieldAdd("""
                {"group":"/G","name":"F","annotations":[{"name":"a","value":"v"}]}""");
        assertEquals(1, found.size(), () -> "exactly one invalid value: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
    }

    @Test
    void aLocalizedTextFieldAsABareStringIsRejected() throws Exception {
        // label is an OPEN object (a locale→text map): its VALUE must still be an object (a bare string used to
        // coerce to an empty map → silent drop), while its KEYS stay unrestricted (leavesOpenMapsUnrestricted).
        List<Diagnostic> found = validateFieldAdd("""
                {"group":"/G","name":"F","label":"plain"}""");
        assertEquals(1, found.size(), () -> "exactly one invalid value: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
    }

    @Test
    void aNestedScalarOfTheWrongTypeIsRejected() throws Exception {
        // string.minLength is declared integer; a string slips past the key-name check but not the value check
        List<Diagnostic> found = validateFieldAdd("""
                {"group":"/G","name":"F","kind":"STRING","string":{"minLength":"five"}}""");
        assertEquals(1, found.size(), () -> "the nested wrong-typed value is flagged: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
        assertEquals("string/minLength", found.get(0).where().arg().orElse(null),
                () -> "the where points at the nested path 'string/minLength': " + found.get(0));
    }

    @Test
    void theGateCoversEveryServedSpecNotJustFields() throws Exception {
        // the rule-add schema declares `messages` as an array — a string is rejected uniformly, proving the
        // value-shape gate is schema-driven across specs, not a field-only special case.
        List<Diagnostic> found = SpecKeys.forSchema("rule-add").validate(M.readTree("""
                {"field":"/O/Q","condition":"x","code":"C","messages":"oops"}"""));
        assertEquals(1, found.size(), () -> "rule `messages` as a string is rejected: " + found);
        assertEquals("RK_INVALID_VALUE", found.get(0).code().orElse(null));
    }

    @Test
    void targetSpecificSchemasRejectKeysOwnedByTheOtherSpec() throws Exception {
        List<Diagnostic> computation = SpecKeys.forSchema("computation-add").validate(M.readTree("""
                {"computedField":"/O/Total","alternatives":[{"operation":"1"}],"messages":[],
                 "allowDifferingDecimals":true}"""));
        assertEquals("RK_UNKNOWN_ARG", computation.get(0).code().orElse(null));

        List<Diagnostic> rule = SpecKeys.forSchema("rule-add").validate(M.readTree("""
                {"field":"/O/Q","condition":"x","code":"C","messages":[],
                 "errorCodesToSuppress":["MVK_INVALID_COMPARE_DEC_PLACES"]}"""));
        assertEquals("RK_UNKNOWN_ARG", rule.get(0).code().orElse(null));
    }

    @Test
    void fieldAddSchemaRootDeclaresExactlyFieldSpecAddKeys() throws Exception {
        // The schema IS the enforcement source. It must list exactly the keys FieldSpec accepts: a MISSING key
        // would reject valid input, an EXTRA key would let a typo slip through. This lock keeps the two in step.
        JsonNode schema = M.readTree(SpecKeysTest.class.getResourceAsStream("/schema/field-add.schema.json"));
        Set<String> schemaKeys = new TreeSet<>();
        schema.path("properties").fieldNames().forEachRemaining(schemaKeys::add);
        assertEquals(new TreeSet<>(FieldSpec.ADD_KEYS), schemaKeys,
                "field-add.schema root `properties` must equal FieldSpec.ADD_KEYS (the schema is what SpecKeys enforces)");
    }

    @Test
    void computationSchemaMatchesItsParserAndTheMeasuredSuppressionDomain() throws Exception {
        JsonNode schema = M.readTree(SpecKeysTest.class.getResourceAsStream(
                "/schema/computation-add.schema.json"));
        Set<String> schemaKeys = new TreeSet<>();
        schema.path("properties").fieldNames().forEachRemaining(schemaKeys::add);
        assertEquals(new TreeSet<>(ComputationSpec.KEYS), schemaKeys,
                "computation-add.schema keys must equal the parser/apply keys");

        Set<String> schemaSuppressions = new TreeSet<>();
        schema.path("properties").path("errorCodesToSuppress").path("items").path("enum")
                .forEach(value -> schemaSuppressions.add(value.asText()));
        assertEquals(new TreeSet<>(Computation.supportedErrorCodesToSuppress()), schemaSuppressions,
                "the CLI schema must not drift from rulekit's kernel-measured suppression domain");
    }
}
