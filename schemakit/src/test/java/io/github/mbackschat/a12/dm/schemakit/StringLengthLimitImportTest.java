package io.github.mbackschat.a12.dm.schemakit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A string length bound above A12's 99,999-unit technical limit is not declared, since A12 refuses such a
 * declaration and caps every stored value at that limit anyway (KERNEL-FINDINGS KF547,
 * `validate.laws.StringLengthDeclarationBoundLawsTest`); the import reports the narrower effective bound instead.
 * A bound at the limit is declared as usual.
 *
 * <p>The other end of the same range: A12 admits a length from 1 (KERNEL-FINDINGS KF556,
 * `adapter.laws.CheckerLengthLimitAgreementTest`), so a JSON Schema {@code minLength} of 0, which constrains
 * nothing, is not declared, and a {@code maxLength} of 0, which A12 cannot express, is reported.
 */
class StringLengthLimitImportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "notes":   { "type": "string", "maxLength": 100000 },
                "essay":   { "type": "string", "minLength": 100000 },
                "summary": { "type": "string", "maxLength": 99999 },
                "contact": { "type": "string", "format": "email", "maxLength": 250000 },
                "tags":    { "type": "array", "maxItems": 3, "items": { "type": "string", "maxLength": 100000 } }
              }
            }
            """;

    @Test
    void aBoundAboveTheTechnicalLimitIsReportedRatherThanDeclared() throws Exception {
        var imported = new JsonSchemaImporter().importSchema(SCHEMA);
        SchemaTestSupport.assertKernelAccepts(imported.dmJson());

        JsonNode model = MAPPER.readTree(imported.dmJson());
        assertEquals(java.util.List.of(99_999), model.findValues("maxLength").stream().map(JsonNode::asInt).toList(),
                "only the bound at the limit is declared");
        assertTrue(model.findValues("minLength").isEmpty(), "no minimum is declared");
        for (String field : java.util.List.of("notes", "essay", "contact", "tags")) {
            assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains(field)
                    && d.contains("99999")), () -> field + " is reported: " + imported.report().diagnostics());
        }
    }

    @Test
    void aZeroBoundIsNeverDeclared() throws Exception {
        var imported = new JsonSchemaImporter().importSchema("""
                {
                  "type": "object",
                  "properties": {
                    "nickname": { "type": "string", "minLength": 0, "maxLength": 40 },
                    "blank":    { "type": "string", "maxLength": 0 }
                  }
                }
                """);
        SchemaTestSupport.assertKernelAccepts(imported.dmJson());

        JsonNode model = MAPPER.readTree(imported.dmJson());
        assertEquals(java.util.List.of(40), model.findValues("maxLength").stream().map(JsonNode::asInt).toList());
        assertTrue(model.findValues("minLength").isEmpty(), "a zero minimum constrains nothing");
        assertTrue(imported.report().diagnostics().stream().anyMatch(d -> d.contains("blank") && d.contains("maxLength 0")),
                () -> "the zero maximum is reported: " + imported.report().diagnostics());
        assertTrue(imported.report().diagnostics().stream().noneMatch(d -> d.contains("nickname")),
                () -> "a zero minimum needs no report: " + imported.report().diagnostics());
    }
}
