package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A compound {@code not} is declined as a whole: never enforce one keyword while silently dropping another. */
class CompoundNotReportingTest {

    @Test
    void scalarCompoundNotIsReportedWithoutPartialSynthesis() {
        var result = new JsonSchemaImporter().importSchema("""
                { "title": "Dispatch", "type": "object",
                  "properties": {
                    "reference": { "type": "string", "not": { "const": "TEMP", "pattern": "^TMP-" } }
                  } }
                """);

        assertKernelAccepts(result.dmJson());
        assertEquals(0, synthesized(result), "a compound not must not enforce only its first recognized keyword");
        assertTrue(result.report().diagnostics().stream().anyMatch(d -> d.contains("compound 'not'")),
                () -> "the whole compound not must be reported: " + result.report().diagnostics());
    }

    @Test
    void objectCompoundNotIsReportedWithoutPartialSynthesis() {
        var result = new JsonSchemaImporter().importSchema("""
                { "title": "Dispatch", "type": "object",
                  "properties": { "route": { "type": "string" } },
                  "not": {
                    "required": ["route"],
                    "properties": { "route": { "const": "CLOSED" } }
                  } }
                """);

        assertKernelAccepts(result.dmJson());
        assertEquals(0, synthesized(result), "an object compound not must not enforce only required[]");
        assertTrue(result.report().diagnostics().stream().anyMatch(d -> d.contains("compound 'not'")),
                () -> "the whole compound object not must be reported: " + result.report().diagnostics());
    }

    @Test
    void annotationBesideTheAssertionDoesNotMakeNotCompound() {
        var result = new JsonSchemaImporter().importSchema("""
                { "title": "Dispatch", "type": "object",
                  "properties": {
                    "reference": { "type": "string",
                      "not": { "const": "TEMP", "description": "temporary references are reserved" } }
                  } }
                """);

        assertKernelAccepts(result.dmJson());
        assertEquals(1, synthesized(result), "an annotation does not add a second validation constraint");
    }

    private static long synthesized(JsonSchemaImporter.ImportResult result) {
        return result.report().dispositions().stream()
                .filter(disposition -> disposition.tier() == TranscodeReport.Tier.TIER_A_PLUS)
                .count();
    }
}
