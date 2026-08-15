package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * The version surface (CLI-SPEC §2a): {@code --version} (human) and {@code manifest.version} (machine-readable)
 * report the same seven axes — the rulekit release, source revision/tree state, kernel built-against/runtime,
 * A12 Tools distribution label, catalog floor, and document-model reference version — so an agent (or human)
 * can identify a clean build or see that a dirty build diverged, and know what it targets.
 */
class VersionVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void versionFlagReportsRulekitKernelAndModel() {
        var res = CliTestSupport.run("--version");
        assertEquals(0, res.code(), res.err());
        String out = res.out();
        assertTrue(out.contains("a12-dmkits"), () -> "names the tool + release: " + out);
        assertTrue(out.matches("(?s).*source: ([0-9a-f]{40} \\((clean|dirty)\\)|\\(unknown\\) \\(unknown\\)).*"),
                () -> "reports the source revision and tree state without inventing missing Git data: " + out);
        assertTrue(out.contains("kernel:") && out.contains("(built)") && out.contains("(runtime)"),
                () -> "reports built-against + runtime kernel: " + out);
        assertTrue(out.contains("A12 Tools distribution:"),
                () -> "reports the A12 Tools distribution label: " + out);
        assertTrue(out.contains("model version"), () -> "reports the model reference version: " + out);
        assertFalse(out.contains("kernel: (unknown)"), () -> "the build baked the kernel version: " + out);
        assertFalse(out.contains("A12 Tools distribution: (unknown)"),
                () -> "the build baked the distribution version: " + out);
    }

    @Test
    void manifestCarriesMachineReadableVersion() throws Exception {
        var res = CliTestSupport.run("manifest");
        assertEquals(0, res.code(), res.err());
        JsonNode v = M.readTree(res.out()).path("version");
        assertTrue(v.isObject(), () -> "manifest.version is a machine-readable object: " + res.out());
        assertTrue(v.path("rulekit").isTextual() && !v.path("rulekit").asText().isBlank());
        String sourceRevision = v.path("source").path("revision").asText();
        String sourceState = v.path("source").path("state").asText();
        assertTrue(sourceState.matches("CLEAN|DIRTY|UNKNOWN"));
        switch (sourceState) {
            case "CLEAN", "DIRTY" -> assertTrue(sourceRevision.matches("[0-9a-f]{40}"));
            case "UNKNOWN" -> assertEquals("(unknown)", sourceRevision);
            default -> throw new AssertionError("unreachable source state " + sourceState);
        }
        assertTrue(v.path("kernel").path("builtAgainst").isTextual());
        assertTrue(v.path("kernel").path("a12Distribution").isTextual()
                && !v.path("kernel").path("a12Distribution").asText().isBlank());
        assertTrue(v.path("kernel").path("runtime").isTextual());
        assertTrue(v.path("kernel").path("skewed").isBoolean());
        assertTrue(v.path("catalogVerifiedAgainst").isTextual());
        assertEquals("28.4.0", v.path("modelVersion").asText(), "the document-model reference version");
    }

    @Test
    void absentGitMetadataIsAnHonestUnknownPair() {
        VersionInfo version = VersionInfo.from(new Properties());

        assertEquals("  source: (unknown) (unknown)", version.lines()[1]);
        JsonNode source = version.toJson(M).path("source");
        assertEquals("(unknown)", source.path("revision").asText());
        assertEquals("UNKNOWN", source.path("state").asText());
    }
}
