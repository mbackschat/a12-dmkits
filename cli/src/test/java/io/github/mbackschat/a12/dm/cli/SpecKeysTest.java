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
}
