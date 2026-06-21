package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command boundary lets NO raw throwable escape (CLI-CONFORMANCE). The reported native crash was a
 * GraalVM {@code MissingReflectionRegistrationError} (an {@code Error}, so it slipped past the
 * {@code Exception}-only execution-exception handler) that surfaced as a raw stack trace with no result
 * envelope. This locks the seam's contract: any unexpected throwable becomes a structured {@code error}
 * envelope (outcome {@code error}, an {@code RK_INTERNAL_ERROR} diagnostic, exit 70) — distinct from a
 * kernel {@code rejected} (exit 1). The native gap itself is closed by the reflection-metadata re-capture
 * (and guarded at runtime by the native gate over an annotated model); this guards the JVM-testable seam.
 */
class InternalErrorBoundaryTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void internalErrorEnvelopeIsAStructuredErrorNotARejection() {
        ResultEnvelope env = ResultEnvelope.internalError("group", "extract", "boom", java.util.List.of());
        assertEquals(70, env.exitCode(), "an internal tool error exits 70 (EX_SOFTWARE), not 1/2");
        JsonNode json = env.toJson();
        assertEquals("error", json.get("outcome").asText());
        assertEquals(false, json.get("ok").asBoolean(), "an internal error did not execute as asked");
        assertEquals(false, json.get("written").asBoolean(), "nothing is written on an internal error");
    }

    @Test
    void internalErrorEnvelopeFromAThrowableCarriesTheCatalogedCode() {
        Throwable t = new RuntimeException("the real cause\nsecond line should be dropped");
        ResultEnvelope env = BaseCommand.internalErrorEnvelope("group", "extract", t);
        JsonNode d = env.toJson().get("diagnostics").get(0);
        assertEquals("RK_INTERNAL_ERROR", d.get("code").asText(), "explorable via `dmtool diagnostics RK_INTERNAL_ERROR`");
        assertEquals("INTERNAL", d.get("source").asText());
        assertEquals("ERROR", d.get("severity").asText());
        assertTrue(d.get("summary").asText().contains("group extract"), "names the failing verb");
        assertTrue(d.get("summary").asText().contains("the real cause"), "carries the real cause");
        assertTrue(!d.get("summary").asText().contains("second line"), "only the first line of the message");
        assertTrue(d.has("fix"), "carries recovery guidance");
    }

    @Test
    void anUnresolvableReferenceIsAStructuredRejectionNotAnInternalError() {
        var e = new io.github.mbackschat.a12.dm.core.UnresolvedReferenceException(
                "pricelist", "no document model with id \"pricelist\" found in 1 indexed models under [/apps]");
        ResultEnvelope env = BaseCommand.unresolvedReferenceEnvelope("model", "validate", e);
        assertEquals(1, env.exitCode(), "an input problem (unresolvable include) is a rejection, exit 1 — not internal error 70");
        JsonNode json = env.toJson();
        assertEquals("rejected", json.get("outcome").asText());
        JsonNode d = json.get("diagnostics").get(0);
        assertEquals("RK_UNRESOLVED_REFERENCE", d.get("code").asText(), "explorable + corrective");
        assertEquals("pricelist", d.get("where").get("arg").asText(), "names the unresolved reference");
        assertTrue(d.get("fix").asText().contains("-w"), "the fix points at the workspace flag");
    }

    @Test
    void aThrowableEscapingAVerbBecomesAStructuredErrorEnvelope(@TempDir Path dir) throws IOException {
        // `group extract` parses the model via ModelDocument.read OUTSIDE any catch — malformed JSON there
        // throws an uncaught (non-ModelConsistencyException) RuntimeException that reaches the boundary.
        // Before the seam this was a raw stack trace; now it is the structured `error` envelope.
        Path bad = dir.resolve("broken.json");
        Files.writeString(bad, "{ \"this is\": not valid json ");
        CliTestSupport.Result res = CliTestSupport.run(
                "-m", bad.toString(), "group", "extract", "/R/G", "--reference", "sub");

        JsonNode env = assertDoesNotThrow(() -> M.readTree(res.out()),
                () -> "stdout must be a structured envelope, not a raw trace:\n" + res.out());
        assertEquals("error", env.get("outcome").asText(),
                () -> "an unexpected throwable maps to outcome `error`, not a raw trace: " + res.out());
        assertEquals(70, res.code(), "outcome `error` exits 70");
        assertEquals("RK_INTERNAL_ERROR", env.get("diagnostics").get(0).get("code").asText());
        assertTrue(res.err().contains("RuntimeException") || !res.err().isBlank(),
                "the full trace is preserved on stderr");
    }
}
