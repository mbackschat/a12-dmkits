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

class ProfileCompareVerbTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsBareAndEnvelopeProfilesAndReportsAMatch(@TempDir Path dir) throws Exception {
        JsonNode extraction = extractProfile(dir);
        Path target = dir.resolve("target-envelope.json");
        Path actual = dir.resolve("actual-profile.json");
        Files.writeString(target, extraction.toPrettyString());
        Files.writeString(actual, extraction.path("data").toPrettyString());

        CliTestSupport.Result result = CliTestSupport.run(
                "profile", "compare", "--target", target.toString(), "--actual", actual.toString());

        assertEquals(0, result.code(), result::err);
        JsonNode envelope = JSON.readTree(result.out());
        assertEquals("profile", envelope.path("target").asText());
        assertEquals("compare", envelope.path("op").asText());
        assertEquals("read", envelope.path("outcome").asText());
        // Reclassified 2026-07-31: profile equality is not model validity, and no consistency gate runs on this
        // path — so the top-level `valid` is gone while the published `data.matches` and the exit codes are
        // unchanged (see ComparisonOutcome).
        assertFalse(envelope.has("valid"), () -> "a comparison is not a validity verdict: " + result.out());
        assertTrue(envelope.path("data").path("matches").asBoolean());
        assertTrue(envelope.path("data").path("axes").size() > 1, "every classified axis is visible");
    }

    @Test
    void aFailedAxisIsVisibleAndUsesTheReadValidityExitConvention(@TempDir Path dir) throws Exception {
        JsonNode extraction = extractProfile(dir);
        Path target = dir.resolve("target-envelope.json");
        Path actual = dir.resolve("actual-profile.json");
        Files.writeString(target, extraction.toPrettyString());
        ObjectNode actualProfile = extraction.path("data").deepCopy();
        ObjectNode structure = (ObjectNode) actualProfile.path("structure");
        structure.put("maxNestingDepth", structure.path("maxNestingDepth").asLong() + 1);
        Files.writeString(actual, actualProfile.toPrettyString());

        CliTestSupport.Result result = CliTestSupport.run(
                "profile", "compare", "--target", target.toString(), "--actual", actual.toString());

        // exit 1 on `differs` is PRESERVED by the reclassification — CI branches on it. What changed is only
        // that the outcome is a typed comparison rather than a mislabelled validity verdict.
        assertEquals(1, result.code(), result::err);
        JsonNode envelope = JSON.readTree(result.out());
        assertEquals("read", envelope.path("outcome").asText());
        assertTrue(envelope.path("ok").asBoolean(), "the comparison itself executed as asked");
        assertFalse(envelope.has("valid"), () -> "profile equality is not model validity: " + result.out());
        assertFalse(envelope.path("data").path("matches").asBoolean());
        JsonNode failed = findAxis(envelope.path("data"), "structure.maxNestingDepth");
        assertEquals("EXACT", failed.path("policy").asText());
        assertEquals("FAIL", failed.path("verdict").asText());
        assertEquals("actual must equal target", failed.path("rule").asText());
    }

    @Test
    void exposesRequiredInputsAndTheComparisonDataContract() throws Exception {
        CliTestSupport.Result help = CliTestSupport.run("profile", "compare", "--help");
        assertEquals(0, help.code(), help::err);
        assertTrue(help.out().contains("--target"));
        assertTrue(help.out().contains("--actual"));

        JsonNode manifest = JSON.readTree(CliTestSupport.run("manifest").out());
        assertTrue(manifest.path("verbs").findValuesAsText("verb").contains("profile compare"));

        JsonNode contract = JSON.readTree(CliTestSupport.run("schema", "profile", "compare").out());
        assertEquals("result", contract.path("returns").asText());
        assertEquals("ProfileComparison", contract.path("dataSchema").path("title").asText());
    }

    private static JsonNode extractProfile(Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        CliTestSupport.Result extraction =
                CliTestSupport.run("-m", model.toString(), "profile", "extract");
        assertEquals(0, extraction.code(), extraction::err);
        return JSON.readTree(extraction.out());
    }

    private static JsonNode findAxis(JsonNode comparison, String path) {
        for (JsonNode axis : comparison.path("axes")) {
            if (path.equals(axis.path("path").asText())) {
                return axis;
            }
        }
        throw new AssertionError("missing comparison axis " + path);
    }
}
