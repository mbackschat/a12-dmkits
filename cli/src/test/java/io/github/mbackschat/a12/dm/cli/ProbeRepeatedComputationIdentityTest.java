package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Executable gold for probe task 14: a repeated computation result must identify VALUE, CLEARED, and ERROR
 * rows independently. The probe remains a discovery instrument; this deterministic guard prevents its known
 * answer key and the live CLI projection from rotting after the session has run.
 *
 * <p>Both repo files read here are declared inputs of {@code :cli:docGuardTest}.
 */
class ProbeRepeatedComputationIdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path TASK = Path.of("../probe/tasks/14-repeated-computation-outcomes.md");
    private static final Path FIXTURE = Path.of(
            "../interpreter/src/commonTest/resources/workspace-preparation/self-contained/permit-basic.dm.json");

    @Test
    void taskGoldMatchesTheLiveRepeatedComputationProjection(@TempDir Path dir) throws Exception {
        String task = Files.readString(TASK);
        assertTrue(task.contains("fixture `interpreter/src/commonTest/resources/workspace-preparation/self-contained/permit-basic.dm.json`"),
                "the probe must seed the typed-dogfood permit fixture exercised by this oracle");
        for (String outcome : new String[] { "VALUE", "CLEARED", "ERROR" }) {
            assertTrue(task.contains(outcome), () -> "the task gold must name the " + outcome + " outcome");
        }

        Path model = dir.resolve("permit-basic.dm.json");
        Files.copy(FIXTURE, model);
        Path fieldSpec = dir.resolve("survey-charge.json");
        Files.writeString(fieldSpec, """
                {"group":"/Permit/SiteSections","name":"SurveyCharge","kind":"NUMBER",
                 "number":{"maxFractionalDigits":0,"maxIntegerDigits":4}}
                """);
        var modified = CliTestSupport.run("-m", model.toString(), "field", "modify", fieldSpec.toString());
        assertEquals(0, modified.code(), modified::out);

        Path document = dir.resolve("permit.json");
        Files.writeString(document, """
                {"Permit":{"SiteSections":[
                  {"Area":75},
                  {"SurveyCharge":50},
                  {"Area":700}
                ]}}
                """);
        var computed = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", document.toString());
        assertEquals(0, computed.code(), computed::out);

        assertSurveyChargeRows(MAPPER.readTree(computed.out()).path("data").path("computed"));
    }

    @Test
    void theOracleRejectsRowsWhoseIdentityCollapsed() throws Exception {
        JsonNode collapsed = MAPPER.readTree("""
                [
                  {"field":"Permit/SiteSections/SurveyCharge","declaration":"Permit/SiteSections/SurveyCharge","outcome":"value","value":"1875"},
                  {"field":"Permit/SiteSections/SurveyCharge","declaration":"Permit/SiteSections/SurveyCharge","outcome":"cleared"},
                  {"field":"Permit/SiteSections/SurveyCharge","declaration":"Permit/SiteSections/SurveyCharge","outcome":"error","value":"17500"}
                ]
                """);

        assertThrows(AssertionError.class, () -> assertSurveyChargeRows(collapsed),
                "positive control: the oracle must fail when repeated-row identities collapse");
    }

    private static void assertSurveyChargeRows(JsonNode computed) {
        Map<String, String> actual = new LinkedHashMap<>();
        for (JsonNode row : computed) {
            if (!"Permit/SiteSections/SurveyCharge".equals(row.path("declaration").asText())) {
                continue;
            }
            String prior = actual.put(row.path("field").asText(), row.path("outcome").asText()
                    + (row.has("value") ? ":" + row.path("value").asText() : ""));
            assertEquals(null, prior, () -> "duplicate computed field identity: " + row.path("field").asText());
        }

        assertEquals(Map.of(
                "Permit/SiteSections/SurveyCharge", "value:1875",
                "Permit/SiteSections[2]/SurveyCharge", "cleared",
                "Permit/SiteSections[3]/SurveyCharge", "error:17500"), actual,
                "each repeated row must keep its canonical identity and distinct outcome");
    }
}
