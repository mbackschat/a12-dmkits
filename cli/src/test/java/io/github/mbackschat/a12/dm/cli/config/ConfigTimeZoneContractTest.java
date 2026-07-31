package io.github.mbackschat.a12.dm.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Authoring lock for the four-domain {@code modelConfig.timeZone} contract: invalid explicit values get
 * corrective kernel diagnostics, while a kernel-valid interpreter-unsupported zone remains authorable and
 * carries a nonblocking evaluator-scope warning on both mutation surfaces.
 */
class ConfigTimeZoneContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void standaloneInvalidValuesCarryTheCorrectiveKernelDiagnostic(@TempDir Path dir) throws Exception {
        for (String zone : java.util.List.of("", "   ", "Europe/Berln")) {
            Path model = CliTestSupport.orderRuled(dir);
            var result = CliTestSupport.run("-m", model.toString(), "config", "modify", "--time-zone", zone);
            assertCorrectiveInvalid(result);
        }
    }

    @Test
    void applyAndModelCheckEnrichInvalidTimeZones(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ { "target": "config", "op": "modify", "time-zone": "Europe/Berln" } ]
                """);
        var apply = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, apply.code(), apply::err);
        JsonNode applyJson = MAPPER.readTree(apply.out());
        assertTrue(!applyJson.path("data").path("committed").asBoolean(), apply::out);
        assertCorrectiveInvalid(applyJson.path("data").path("results").get(applyJson.path("data").path("results").size() - 1));

        String invalid = Files.readString(model).replace("\"Europe/Berlin\"", "\"Europe/Berln\"");
        Files.writeString(model, invalid);
        var check = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(1, check.code(), check::err);
        assertCorrectiveInvalid(MAPPER.readTree(check.out()));
    }

    @Test
    void legalOutsideSetMutationSucceedsWithEvaluatorScopeWarning(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var result = CliTestSupport.run("-m", model.toString(), "config", "modify",
                "--time-zone", "America/New_York");
        assertEquals(0, result.code(), result::err);
        JsonNode json = MAPPER.readTree(result.out());
        assertEquals("applied", json.path("outcome").asText(), result::out);
        assertScopeWarning(json.path("diagnostics").path(0));

        var check = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, check.code(), check::err);
        assertTrue(MAPPER.readTree(check.out()).path("valid").asBoolean(), check::out);
    }

    @Test
    void applyCarriesTheSameNonblockingScopeWarning(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ { "target": "config", "op": "modify", "time-zone": "America/New_York" } ]
                """);
        var result = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, result.code(), result::err);
        JsonNode json = MAPPER.readTree(result.out());
        assertTrue(json.path("data").path("committed").asBoolean(), result::out);
        assertScopeWarning(json.path("data").path("results").path(0).path("diagnostics").path(0));
    }

    @Test
    void helpAndDerivedSchemaExplainSupportedVersusKernelLegal(@TempDir Path dir) throws Exception {
        var help = CliTestSupport.run("config", "modify", "--help");
        assertEquals(0, help.code(), help::err);
        assertTrue(help.out().contains("UTC, GMT, Europe/Berlin"), help::out);
        assertTrue(help.out().contains("kernel-valid"), help::out);

        var schema = CliTestSupport.run("schema", "config", "modify");
        assertEquals(0, schema.code(), schema::err);
        String description = MAPPER.readTree(schema.out()).path("notes").asText();
        assertTrue(description.contains("UTC, GMT, Europe/Berlin"), schema::out);
        assertTrue(description.contains("kernel-valid"), schema::out);
    }

    private static void assertCorrectiveInvalid(CliTestSupport.Result result) throws Exception {
        assertEquals(1, result.code(), result::err);
        assertCorrectiveInvalid(MAPPER.readTree(result.out()));
    }

    private static void assertCorrectiveInvalid(JsonNode envelope) {
        JsonNode diagnostics = envelope.path("diagnostics");
        JsonNode diagnostic = null;
        for (JsonNode candidate : diagnostics) {
            if ("MVK_INVALID_TIME_ZONE".equals(candidate.path("code").asText())) {
                diagnostic = candidate;
                break;
            }
        }
        assertTrue(diagnostic != null, () -> "missing MVK_INVALID_TIME_ZONE: " + envelope);
        assertTrue(!diagnostic.path("fix").asText().isBlank(), () -> "missing corrective fix: " + envelope);
        assertTrue(!diagnostic.path("explain").asText().isBlank(), () -> "missing explanation: " + envelope);
    }

    private static void assertScopeWarning(JsonNode diagnostic) {
        assertEquals("RK_INTERPRETER_TIMEZONE_UNSUPPORTED", diagnostic.path("code").asText(),
                diagnostic::toPrettyString);
        assertEquals("WARNING", diagnostic.path("severity").asText(), diagnostic::toPrettyString);
        assertTrue(diagnostic.path("summary").asText().contains("America/New_York"), diagnostic::toPrettyString);
        assertTrue(!diagnostic.path("fix").asText().isBlank(), diagnostic::toPrettyString);
        assertTrue(!diagnostic.path("explain").asText().isBlank(), diagnostic::toPrettyString);
    }
}
