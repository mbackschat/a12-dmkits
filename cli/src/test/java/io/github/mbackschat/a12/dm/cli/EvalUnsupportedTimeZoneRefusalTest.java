package io.github.mbackschat.a12.dm.cli;

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
 * IG83 scope-limit guard: a model whose {@code modelConfig.timeZone} is a kernel-LEGAL zone the interpreter
 * does not model (e.g. {@code America/New_York}) is <b>eval-refused</b> with a structured, corrective envelope
 * ({@code RK_INTERPRETER_TIMEZONE_UNSUPPORTED} + a fix), never a bare {@code fail} line or an
 * {@code RK_INTERNAL_ERROR}. The kernel accepts the wider zone-id domain, so the same model is
 * {@code model check}-valid — the documented "check-valid but not eval-able" split (the interpreter supports
 * only {@code UTC}/{@code GMT}/{@code Europe/Berlin}). All three interpreter-backed verbs refuse identically.
 */
class EvalUnsupportedTimeZoneRefusalTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A valid order model retargeted to a kernel-legal but interpreter-unsupported zone. */
    private static Path unsupportedZoneModel(Path dir) throws IOException {
        String json = new String(EvalUnsupportedTimeZoneRefusalTest.class
                .getResourceAsStream("/models/order.dm.json").readAllBytes());
        json = json.replace("\"Europe/Berlin\"", "\"America/New_York\"");
        Path f = dir.resolve("order-ny.dm.json");
        Files.writeString(f, json);
        return f;
    }

    private static Path emptyDoc(Path dir) throws IOException {
        Path f = dir.resolve("doc.json");
        Files.writeString(f, "{ \"fields\": {} }");
        return f;
    }

    private static void assertZoneRefused(CliTestSupport.Result res) throws Exception {
        assertTrue(res.code() != 0, () -> "an unsupported zone exits non-zero: " + res.out() + res.err());
        JsonNode j = M.readTree(res.out());   // MUST parse — a structured envelope, not a bare stderr line
        assertEquals("refused", j.path("outcome").asText(),
                () -> "an unsupported zone is a structured refusal: " + res.out());
        JsonNode diag = j.path("diagnostics").path(0);
        assertEquals("RK_INTERPRETER_TIMEZONE_UNSUPPORTED", diag.path("code").asText(),
                () -> "carries RK_INTERPRETER_TIMEZONE_UNSUPPORTED: " + res.out());
        assertTrue(!diag.path("fix").asText().isBlank(), () -> "carries a corrective fix: " + res.out());
    }

    @Test
    void allInterpreterVerbsRefuseAnUnsupportedZone(@TempDir Path dir) throws Exception {
        Path model = unsupportedZoneModel(dir);
        Path doc = emptyDoc(dir);
        assertZoneRefused(CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", doc.toString()));
        assertZoneRefused(CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", doc.toString()));
    }

    @Test
    void theSameModelIsKernelCheckValid_theDocumentedSplit(@TempDir Path dir) throws Exception {
        // The kernel accepts America/New_York (a valid IANA id), so `model check` does NOT reject on the zone —
        // proving eval's refusal is a deliberate interpreter scope limit, not model invalidity.
        Path model = unsupportedZoneModel(dir);
        CliTestSupport.Result check = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertNotEquals("RK_INTERPRETER_TIMEZONE_UNSUPPORTED",
                M.readTree(check.out()).path("diagnostics").path(0).path("code").asText(),
                () -> "model check must not raise the interpreter zone refusal: " + check.out());
        assertTrue(!check.out().contains("MVK_INVALID_TIME_ZONE"),
                () -> "the kernel accepts America/New_York, so no MVK_INVALID_TIME_ZONE: " + check.out());
    }
}
