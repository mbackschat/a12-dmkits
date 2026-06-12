package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The version surface (CLI-SPEC §1b): {@code --version} (human) and {@code manifest.version} (machine-readable)
 * report the same five axes — the rulekit release, the kernel built-against/runtime, the catalog floor, and the
 * document-model reference version — so an agent (or human) can see what this build is and what it targets.
 */
class VersionVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void versionFlagReportsRulekitKernelAndModel() {
        var res = CliTestSupport.run("--version");
        assertEquals(0, res.code(), res.err());
        String out = res.out();
        assertTrue(out.contains("a12-dmkits"), () -> "names the tool + release: " + out);
        assertTrue(out.contains("kernel:") && out.contains("(built)") && out.contains("(runtime)"),
                () -> "reports built-against + runtime kernel: " + out);
        assertTrue(out.contains("model version"), () -> "reports the model reference version: " + out);
        assertFalse(out.contains("(unknown)"), () -> "the build baked real versions (not (unknown)): " + out);
    }

    @Test
    void manifestCarriesMachineReadableVersion() throws Exception {
        var res = CliTestSupport.run("manifest");
        assertEquals(0, res.code(), res.err());
        JsonNode v = M.readTree(res.out()).path("version");
        assertTrue(v.isObject(), () -> "manifest.version is a machine-readable object: " + res.out());
        assertTrue(v.path("rulekit").isTextual() && !v.path("rulekit").asText().isBlank());
        assertTrue(v.path("kernel").path("builtAgainst").isTextual());
        assertTrue(v.path("kernel").path("runtime").isTextual());
        assertTrue(v.path("kernel").path("skewed").isBoolean());
        assertTrue(v.path("catalogVerifiedAgainst").isTextual());
        assertEquals("28.4.0", v.path("modelVersion").asText(), "the document-model reference version");
    }
}
