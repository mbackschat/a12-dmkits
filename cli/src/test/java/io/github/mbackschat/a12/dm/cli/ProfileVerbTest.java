package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The top-level {@code profile extract} target: the normal form returns a profile in {@code data}; {@code
 * -o} writes exactly that bare artifact and returns an applied envelope.
 */
class ProfileVerbTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void extractsTheCurrentProfileIntoTheReadEnvelope(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        CliTestSupport.Result result =
                CliTestSupport.run("-m", model.toString(), "profile", "extract");

        assertEquals(0, result.code(), result::err);
        JsonNode envelope = JSON.readTree(result.out());
        assertEquals("profile", envelope.path("target").asText());
        assertEquals("extract", envelope.path("op").asText());
        assertEquals("read", envelope.path("outcome").asText());
        JsonNode profile = envelope.path("data");
        assertTrue(profile.has("structure"));
        assertTrue(profile.has("rules"));
        assertTrue(profile.has("computations"));
        assertTrue(profile.has("wiring"));
        assertFalse(profile.has("profileVersion"), "there is one current profile format, not a version switch");
    }

    @Test
    void outputWritesTheBareArtifactAndReturnsAnAppliedEnvelope(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path output = dir.resolve("order.profile.json");

        CliTestSupport.Result result =
                CliTestSupport.run("-m", model.toString(), "profile", "extract", "-o", output.toString());

        assertEquals(0, result.code(), result::err);
        JsonNode envelope = JSON.readTree(result.out());
        assertEquals("applied", envelope.path("outcome").asText());
        assertTrue(envelope.path("written").asBoolean());
        assertEquals(output.toString(), envelope.path("output").asText());

        JsonNode artifact = JSON.readTree(Files.readString(output));
        assertTrue(artifact.has("structure"));
        assertFalse(artifact.has("outcome"), "the file is the bare profile, not a nested result envelope");
    }

    @Test
    void exposesHelpManifestAndTheProfileDataSchema() throws Exception {
        CliTestSupport.Result help = CliTestSupport.run("profile", "extract", "--help");
        assertEquals(0, help.code(), help::err);
        assertTrue(help.out().contains("-o"));
        assertTrue(help.out().contains("identifier-free"));

        JsonNode manifest = JSON.readTree(CliTestSupport.run("manifest").out());
        assertTrue(manifest.path("verbs").findValuesAsText("verb").contains("profile extract"),
                "profile extract is in the live manifest");

        JsonNode contract = JSON.readTree(CliTestSupport.run("schema", "profile", "extract").out());
        assertEquals("result", contract.path("returns").asText());
        assertEquals("ModelPerformanceProfile", contract.path("dataSchema").path("title").asText());
    }

    @Test
    void neverResurrectsModelProfileOrAProfileVersionFlag(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        assertEquals(2, CliTestSupport.run("-m", model.toString(), "model", "profile").code());
        assertEquals(2, CliTestSupport.run(
                "-m", model.toString(), "profile", "extract", "--profile-version", "1").code());
    }
}
