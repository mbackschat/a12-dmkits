package io.github.mbackschat.a12.dm.schemakit;

import org.junit.jupiter.api.Test;

import static io.github.mbackschat.a12.dm.schemakit.SchemaTestSupport.assertKernelAccepts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code const} never aborts the import (SCHEMAKIT-SPEC §4a, never-refuses). A {@code const} on a field kind
 * whose a12 handle cannot express equality — a temporal (date/time/date-time) or custom-typed field — is
 * <em>skipped-and-reported</em>, exactly as {@code not{const}} ({@code ForbiddenConst}) already behaves, never
 * thrown. Found 2026-07-10 (six-component review): {@code RuleSynthesis}'s {@code ConstValue} case was the only
 * kind-blind sibling — it trusted a {@code numeric} flag and called {@code m.str(...)} for every non-numeric
 * {@code const}, so a {@code format:date}/custom {@code const} threw in {@code BoundModel} and failed the ENTIRE
 * import. This locks the never-refuses contract across the whole const class.
 */
class ConstKindGuardTest {

    private static long rules(JsonSchemaImporter.ImportResult r) {
        return r.report().dispositions().stream().filter(d -> d.tier() == TranscodeReport.Tier.TIER_A_PLUS).count();
    }

    private static boolean reportsSkippedConst(JsonSchemaImporter.ImportResult r) {
        return r.report().diagnostics().stream().anyMatch(d -> d.contains("const") && d.contains("skipped"));
    }

    private static JsonSchemaImporter.ImportResult importTemporalConst(String format, String constValue) {
        return new JsonSchemaImporter().importSchema(
                "{ \"type\":\"object\", \"properties\":{ \"f\":{ \"type\":\"string\", \"format\":\"" + format
                        + "\", \"const\":\"" + constValue + "\" } } }");
    }

    @Test
    void constOnADateFieldIsSkippedNotThrown() {
        JsonSchemaImporter.ImportResult r = importTemporalConst("date", "2026-01-01");
        assertKernelAccepts(r.dmJson());
        assertEquals(0, rules(r), () -> "a date const has no a12 rule form — must be skipped: " + r.report().dispositions());
        assertTrue(reportsSkippedConst(r), () -> "the skipped date const must be reported: " + r.report().diagnostics());
    }

    @Test
    void constOnADateTimeFieldIsSkippedNotThrown() {
        JsonSchemaImporter.ImportResult r = importTemporalConst("date-time", "2026-01-01T00:00:00Z");
        assertKernelAccepts(r.dmJson());
        assertEquals(0, rules(r), () -> "a date-time const has no a12 rule form — must be skipped: " + r.report().dispositions());
        assertTrue(reportsSkippedConst(r), () -> "the skipped date-time const must be reported: " + r.report().diagnostics());
    }

    @Test
    void constOnATimeFieldIsSkippedNotThrown() {
        JsonSchemaImporter.ImportResult r = importTemporalConst("time", "12:00:00");
        assertKernelAccepts(r.dmJson());
        assertEquals(0, rules(r), () -> "a time const has no a12 rule form — must be skipped: " + r.report().dispositions());
        assertTrue(reportsSkippedConst(r), () -> "the skipped time const must be reported: " + r.report().diagnostics());
    }

    @Test
    void constOnACustomTypedFieldIsSkippedNotThrown() {
        // CUSTOM_TYPE profile maps a semantic format (no standard regex) to a custom field — a kind str() can't carry.
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(
                "{ \"type\":\"object\", \"properties\":{ \"iban\":{ \"type\":\"string\", \"format\":\"iban\", "
                        + "\"const\":\"DE00 0000 0000 0000 0000 00\" } } }",
                MappingProfile.defaults().withFormat(MappingProfile.FormatStrategy.CUSTOM_TYPE));
        assertKernelAccepts(r.dmJson());
        assertEquals(0, rules(r), () -> "a custom-typed const has no a12 rule form — must be skipped: " + r.report().dispositions());
        assertTrue(reportsSkippedConst(r), () -> "the skipped custom const must be reported: " + r.report().diagnostics());
    }

    @Test
    void constOnAStringFieldStillSynthesizesARule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(
                "{ \"type\":\"object\", \"properties\":{ \"tier\":{ \"type\":\"string\", \"const\":\"PREMIUM\" } } }");
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "a string const must still synthesize a rule: " + r.report().dispositions());
    }

    @Test
    void constOnANumberFieldStillSynthesizesARule() {
        JsonSchemaImporter.ImportResult r = new JsonSchemaImporter().importSchema(
                "{ \"type\":\"object\", \"properties\":{ \"deductible\":{ \"type\":\"integer\", \"const\":500 } } }");
        assertKernelAccepts(r.dmJson());
        assertEquals(1, rules(r), () -> "a number const must still synthesize a rule: " + r.report().dispositions());
    }
}
