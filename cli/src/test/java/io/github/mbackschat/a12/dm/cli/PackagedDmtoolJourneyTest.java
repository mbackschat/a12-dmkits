package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The documented cold-start authoring journey, executed only through the packaged process boundary. */
class PackagedDmtoolJourneyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aColdUserCanCreateAuthorPreviewPersistReadCheckAndCompute(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = dir.resolve("order.dm.json");
        assertOutcome(dmtool.run("model", "new", "--id", "order", "--locale", "en_US", "--root", "Order",
                "-o", model.toString()), "applied", "model new");

        Path setup = dir.resolve("setup.json");
        Files.writeString(setup, """
                [
                  {"target":"field","op":"add","group":"/Order","name":"BaseFee","kind":"NUMBER"},
                  {"target":"field","op":"add","group":"/Order","name":"EffectiveFee","kind":"NUMBER"}
                ]
                """);
        JsonNode applied = assertOutcome(dmtool.run("-m", model.toString(), "apply", setup.toString()),
                "applied", "apply setup");
        assertTrue(applied.path("data").path("committed").asBoolean(), applied::toString);

        Path computation = dir.resolve("computation.json");
        Files.writeString(computation, """
                {
                  "computedField":"/Order/EffectiveFee",
                  "alternatives":[{"operation":"[/Order/BaseFee] * 2"}],
                  "messages":[{"locale":"en_US","text":"Effective fee computed."}]
                }
                """);
        String beforePreview = Files.readString(model);
        assertOutcome(dmtool.run("-m", model.toString(), "computation", "add", computation.toString(),
                "--dry-run"), "preview", "computation add --dry-run");
        assertEquals(beforePreview, Files.readString(model), "the documented dry-run wrote the model");

        assertOutcome(dmtool.run("-m", model.toString(), "computation", "add", computation.toString()),
                "applied", "computation add");
        JsonNode read = assertOutcome(dmtool.run("-m", model.toString(), "computation", "read",
                "/Order/EffectiveFeeComp"), "read", "computation read");
        assertEquals("/Order/EffectiveFee", read.path("data").path("calculatedField").asText());

        JsonNode checked = assertOutcome(dmtool.run("-m", model.toString(), "model", "check"),
                "read", "model check");
        assertTrue(checked.path("valid").asBoolean(), checked::toString);

        Path instance = dir.resolve("instance.json");
        Files.writeString(instance, "{\"Order\":{\"BaseFee\":10}}");
        JsonNode computed = assertOutcome(dmtool.run("-m", model.toString(), "model", "compute", "--instance",
                instance.toString()), "read", "model compute");
        assertEquals("DM_INTERPRETER", computed.path("engine").asText());
        assertEquals("Order/EffectiveFee", computed.path("data").path("computed").get(0).path("field").asText());
        assertEquals("20", computed.path("data").path("computed").get(0).path("value").asText());
    }

    private static JsonNode assertOutcome(PackagedDmtool.Result result, String expected, String context)
            throws Exception {
        assertEquals(0, result.code(), () -> context + ": " + result.err() + result.out());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals(expected, envelope.path("outcome").asText(), () -> context + ": " + result.out());
        ResultEnvelopeAssertions.assertConforms(envelope, context);
        return envelope;
    }
}
