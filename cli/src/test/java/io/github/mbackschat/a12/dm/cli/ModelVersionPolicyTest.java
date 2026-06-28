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

/**
 * The document-model-version load policy (the user's tolerance ask): the kernel is the authority on
 * compatibility, and {@code model check} <b>classifies</b> its verdict — a stable, machine-detectable
 * {@code RK_MODEL_VERSION_INCOMPATIBLE} on the fail-fast case and a tolerant INFO {@code RK_MODEL_VERSION_SKEW}
 * when an older same-major model is accepted. The kernel's compatibility rule is probed + locked in
 * {@code KERNEL-FINDINGS}; here we lock rulekit's classification of it.
 */
class ModelVersionPolicyTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Write the order-ruled fixture with its {@code header.modelVersion} overridden to {@code version}. */
    private static Path withModelVersion(Path dir, String version) throws Exception {
        ObjectNode model = (ObjectNode) M.readTree(CliTestSupport.orderRuledJson());
        ((ObjectNode) model.path("header")).put("modelVersion", version);
        Path f = dir.resolve("v-" + version + ".dm.json");
        Files.writeString(f, M.writeValueAsString(model));
        return f;
    }

    private static boolean hasCode(JsonNode envelope, String code) {
        for (JsonNode d : envelope.path("diagnostics")) {
            if (code.equals(d.path("code").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void referenceVersionRaisesNoVersionDiagnostic(@TempDir Path dir) throws Exception {
        Path model = withModelVersion(dir, "28.4.0"); // the build's reference
        var res = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("valid").asBoolean());
        assertFalse(hasCode(j, "RK_MODEL_VERSION_SKEW"), () -> "no skew at the reference: " + res.out());
        assertFalse(hasCode(j, "RK_MODEL_VERSION_INCOMPATIBLE"), res::out);
    }

    @Test
    void olderSameMajorIsToleratedWithAnExplicitInfo(@TempDir Path dir) throws Exception {
        Path model = withModelVersion(dir, "28.0.0"); // older, same major → kernel accepts
        var res = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, res.code(), res.err()); // tolerant: still valid, exit 0
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("valid").asBoolean(), () -> "older same-major model stays valid: " + res.out());
        assertTrue(hasCode(j, "RK_MODEL_VERSION_SKEW"), () -> "but the skew is made explicit: " + res.out());
    }

    @Test
    void newerOrDifferentMajorFailsFastWithAStableCode(@TempDir Path dir) throws Exception {
        Path model = withModelVersion(dir, "99.0.0"); // newer → kernel rejects (Version mismatch)
        var res = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(1, res.code(), () -> "fail-fast: invalid, exit 1: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("valid").asBoolean());
        assertTrue(hasCode(j, "RK_MODEL_VERSION_INCOMPATIBLE"),
                () -> "the kernel's version-mismatch is coded for machine detection: " + res.out());
    }

    @Test
    void writeBackDoesNotBumpModelVersion(@TempDir Path dir) throws Exception {
        // A loaded model's version MUST survive a write-back unchanged — no implicit bump to the reference.
        Path model = withModelVersion(dir, "28.0.0"); // a non-reference, kernel-accepted version
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--decimal-separator", ",");
        assertEquals(0, res.code(), res.err());
        JsonNode written = M.readTree(Files.readString(model));
        assertEquals("28.0.0", written.path("header").path("modelVersion").asText(),
                "write-back must preserve header.modelVersion (no implicit bump)");
    }
}
