package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.profile.ModelProfiles;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/** The current-only {@code profile synthesize} artifact-producing CLI boundary. */
class ProfileSynthesizeVerbTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void synthesizesADeterministicKernelValidModelArtifact(@TempDir Path dir) throws Exception {
        Path profile = profile(dir);

        CliTestSupport.Result first = CliTestSupport.run(
                "profile", "synthesize", "--input", profile.toString(), "--seed", "42");
        CliTestSupport.Result second = CliTestSupport.run(
                "profile", "synthesize", "--input", profile.toString(), "--seed", "42");

        assertEquals(0, first.code(), first::err);
        assertEquals(first.out(), second.out(), "the same current profile and seed reproduce byte-identically");
        assertFalse(JSON.readTree(first.out()).path("header").path("id").asText().isBlank());
        BoundModel.ofJson(first.out());
    }

    @Test
    void outputWritesTheModelAndReturnsAnAppliedReceipt(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("ProfileCalibration.dm.json");

        CliTestSupport.Result result = CliTestSupport.run(
                "profile", "synthesize",
                "--input", profile(dir).toString(),
                "--seed", "7",
                "-o", output.toString());

        assertEquals(0, result.code(), result::err);
        BoundModel.ofJson(Files.readString(output));
        JsonNode receipt = JSON.readTree(result.out());
        assertEquals("profile", receipt.path("target").asText());
        assertEquals("synthesize", receipt.path("op").asText());
        assertEquals("applied", receipt.path("outcome").asText());
        assertEquals(output.toString(), receipt.path("output").asText());
    }

    @Test
    void rejectsANestedPublishedSchemaViolationBeforeSynthesis(@TempDir Path dir) throws Exception {
        Path invalid = profile(dir);
        ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(invalid));
        ObjectNode byDepth = (ObjectNode) root.at("/structure/repeatability/chainProductByDepth");
        ObjectNode distribution = (ObjectNode) byDepth.elements().next();
        distribution.remove("buckets");
        Files.writeString(invalid, root.toPrettyString());
        Path output = dir.resolve("must-not-exist.dm.json");

        CliTestSupport.Result result = CliTestSupport.run("profile", "synthesize", "--input",
                invalid.toString(), "-o", output.toString());

        assertEquals(1, result.code(), result::err);
        JsonNode envelope = JSON.readTree(result.out());
        assertEquals("rejected", envelope.path("outcome").asText(), result::out);
        assertEquals("RK_INVALID_VALUE", envelope.path("diagnostics").get(0).path("code").asText(), result::out);
        assertTrue(envelope.path("diagnostics").get(0).path("where").path("arg").asText().endsWith("/buckets"),
                result::out);
        assertFalse(Files.exists(output));
    }

    @Test
    void exposesTheCurrentInputAndArtifactContract() throws Exception {
        CliTestSupport.Result help = CliTestSupport.run("profile", "synthesize", "--help");
        assertEquals(0, help.code(), help::err);
        assertTrue(help.out().contains("--input"));
        assertTrue(help.out().contains("--seed"));
        assertTrue(help.out().contains("unbounded"));
        assertFalse(help.out().contains("profile-version"));

        JsonNode manifest = JSON.readTree(CliTestSupport.run("manifest").out());
        JsonNode verb = findVerb(manifest, "profile synthesize");
        assertEquals("profile synthesize", verb.path("schema").asText());

        CliTestSupport.Result schema = CliTestSupport.run("schema", "profile", "synthesize");
        assertEquals(0, schema.code(), schema::err);
        JsonNode contract = JSON.readTree(schema.out());
        assertEquals("ModelProfile", contract.path("input").path("title").asText());
        assertEquals("artifact", contract.path("returns").asText());
        assertEquals("dm-json-model", contract.path("artifact").path("kind").asText());
        assertEquals("stdout", contract.path("artifact").path("defaultDestination").asText());
        assertEquals("-o", contract.path("artifact").path("outputOption").asText());
        assertEquals("result", contract.path("artifact").path("receiptSchema").asText());
        assertFalse(SchemaCommand.CONTRACT_EXEMPT.containsKey("profile synthesize"));

        assertEquals(2, CliTestSupport.run("model", "synthesize", "--input", "unused").code());
        assertEquals(2, CliTestSupport.run(
                "profile", "synthesize", "--input", "unused", "--profile-version", "1").code());
    }

    private static Path profile(Path dir) throws Exception {
        String model = DocumentModelDraft.named("CalibrationSource").locale("en_US")
                .rootGroup("Portfolio", root -> root
                        .field("Name", FieldKind.STRING)
                        .group("Positions", positions -> positions
                                .repeatable(25)
                                .field("Amount", FieldKind.NUMBER)))
                .build()
                .toJson();
        Path profile = dir.resolve("target.profile.json");
        Files.writeString(profile, ModelProfiles.extract(model));
        return profile;
    }

    private static JsonNode findVerb(JsonNode manifest, String name) {
        for (JsonNode verb : manifest.path("verbs")) {
            if (name.equals(verb.path("verb").asText())) {
                return verb;
            }
        }
        throw new AssertionError("manifest is missing " + name);
    }
}
