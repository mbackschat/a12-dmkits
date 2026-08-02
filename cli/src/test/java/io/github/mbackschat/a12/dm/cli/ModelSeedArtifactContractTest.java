package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The directional artifact contract of {@code model seed}, derived from the canonical Document schema. */
class ModelSeedArtifactContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void publishesTheSameDocumentShapeTheRuntimeReads(@TempDir Path dir) throws Exception {
        JsonNode manifest = JSON.readTree(CliTestSupport.run("manifest").out());
        assertEquals("model seed", manifestVerb(manifest, "model seed").path("schema").asText());

        JsonNode seedContract = contract("model", "seed");
        assertEquals("artifact", seedContract.path("returns").asText());
        assertEquals("a12-document-json", seedContract.path("artifact").path("kind").asText());
        assertEquals("stdout", seedContract.path("artifact").path("defaultDestination").asText());
        assertFalse(seedContract.path("artifact").has("outputOption"),
                "model seed has no file-writing receipt branch");
        assertEquals(contract("model", "eval").path("docInput"),
                seedContract.path("artifact").path("schema"),
                "the generated artifact and runtime input have one schema owner");
        assertFalse(SchemaCommand.CONTRACT_EXEMPT.containsKey("model seed"));

        Path model = CliTestSupport.orderRuled(dir);
        CliTestSupport.Result seed = CliTestSupport.run("-m", model.toString(), "model", "seed", "--seed", "7");
        assertEquals(0, seed.code(), seed::err);
        Path document = dir.resolve("seed.json");
        Files.writeString(document, seed.out());
        CliTestSupport.Result evaluated = CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--instance", document.toString());
        assertEquals(0, evaluated.code(), evaluated::err);
    }

    private static JsonNode contract(String target, String op) throws Exception {
        CliTestSupport.Result result = CliTestSupport.run("schema", target, op);
        assertEquals(0, result.code(), result::err);
        return JSON.readTree(result.out());
    }

    private static JsonNode manifestVerb(JsonNode manifest, String name) {
        for (JsonNode verb : manifest.path("verbs")) {
            if (name.equals(verb.path("verb").asText())) {
                return verb;
            }
        }
        throw new AssertionError("manifest is missing " + name);
    }
}
