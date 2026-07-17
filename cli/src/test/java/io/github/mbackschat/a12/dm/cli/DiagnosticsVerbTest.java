package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The {@code diagnostics} catalog verb (CLI-CONFORMANCE-SPEC) — the `operators` twin for diagnostic codes.
 * Lists every RK_* code (filterable) and explains one, so an agent that meets a `code` can look it up.
 * No model needed (it's a catalog, like `operators`).
 */
class DiagnosticsVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void listsEveryCodeWithSeveritySourceAndMeaning() throws Exception {
        var res = CliTestSupport.run("diagnostics");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("count").asInt() > 0);
        boolean sawNoSuchField = false;
        for (JsonNode d : j.path("diagnostics")) {
            assertTrue(d.path("code").asText().startsWith("RK_"));
            assertFalse(d.path("severity").asText().isBlank());
            assertFalse(d.path("source").asText().isBlank());
            assertFalse(d.path("meaning").asText().isBlank());
            if ("RK_NO_SUCH_FIELD".equals(d.path("code").asText())) {
                sawNoSuchField = true;
            }
        }
        assertTrue(sawNoSuchField, "the catalog lists RK_NO_SUCH_FIELD");
    }

    @Test
    void explainsOneCodeWithItsFix() throws Exception {
        var res = CliTestSupport.run("diagnostics", "RK_NO_SUCH_FIELD");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertEquals("RK_NO_SUCH_FIELD", j.path("code").asText());
        assertEquals("ERROR", j.path("severity").asText());
        assertEquals("PRECHECK", j.path("source").asText());
        assertFalse(j.path("meaning").asText().isBlank());
        assertFalse(j.path("fix").asText().isBlank(), "an explained code carries its canonical fix");
    }

    @Test
    void acceptsACodeWithoutTheRkPrefix() throws Exception {
        var res = CliTestSupport.run("diagnostics", "MOVE_INTO_SELF");
        assertEquals(0, res.code(), res::err);
        assertEquals("RK_MOVE_INTO_SELF", M.readTree(res.out()).path("code").asText());
    }

    @Test
    void explainsAStructuralKernelCode() throws Exception {
        // P-mini-1 (probe-observed): an agent that meets a structural kernel code — one no operator
        // documents, so `operators`/`explain` can't help — naturally tries `diagnostics <code>`. It must
        // RESOLVE (meaning + fix), not dead-end. Covers the placement + iteration-scope codes, and the
        // shared MVK_NO_WILDCARD (F06-2: it must give the generic flatten/--group guidance here too), and the
        // cross-context MVK_NO_GROUPS_ALLOWED (Set CA: aggregate/Having over a group wildcard, not FieldFilled).
        for (String code : java.util.List.of("MVK_ERROR_FIELD_NOT_IN_RULEGROUP", "MVK_NEG_CONDITION_IN_ITERATION",
                "MVK_NO_WILDCARD", "MVK_UNEXPECTED_TOKEN", "MVK_NO_GROUPS_ALLOWED")) {
            var res = CliTestSupport.run("diagnostics", code);
            assertEquals(0, res.code(), () -> code + ": " + res.err());
            JsonNode j = M.readTree(res.out());
            assertEquals(code, j.path("code").asText());
            assertEquals("KERNEL", j.path("source").asText());
            assertFalse(j.path("meaning").asText().isBlank(), () -> code + " carries a meaning");
            assertFalse(j.path("fix").asText().isBlank(), () -> code + " carries a fix");
        }
    }

    @Test
    void filtersBySeverity() throws Exception {
        var res = CliTestSupport.run("diagnostics", "--severity", "INFO");
        assertEquals(0, res.code(), res::err);
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("count").asInt() > 0);
        for (JsonNode d : j.path("diagnostics")) {
            assertEquals("INFO", d.path("severity").asText());
        }
    }

    @Test
    void failsClearlyOnAnUnknownCode() throws Exception {
        var res = CliTestSupport.run("diagnostics", "RK_TOTALLY_MADE_UP");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("RK_TOTALLY_MADE_UP"), () -> res.err());
        // the dead-end routes all three code classes: this RK_* catalog, MVK_* kernel codes (operators/explain),
        // and a verb's data.findings code (the third class — e.g. workspace roles' UNDEFINED_ROLE) → its `schema`
        assertTrue(res.err().contains("operators") && res.err().contains("schema"),
                () -> "the not-found message routes MVK_* and data.findings codes, not just RK_*: " + res.err());
    }
}
