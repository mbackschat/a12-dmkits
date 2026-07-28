package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** A valid ruled order model retargeted to the requested explicit zone. */
    private static Path zoneModel(Path dir, String zone) throws IOException {
        String json = new String(EvalUnsupportedTimeZoneRefusalTest.class
                .getResourceAsStream("/models/order-ruled.dm.json").readAllBytes());
        json = json.replace("\"Europe/Berlin\"", "\"" + zone + "\"");
        Path f = dir.resolve("order-zone.dm.json");
        Files.writeString(f, json);
        return f;
    }

    private static Path emptyDoc(Path dir) throws IOException {
        Path f = dir.resolve("doc.json");
        Files.writeString(f, "{}");
        return f;
    }

    private static void assertZoneRefused(CliTestSupport.Result res, String zone) throws Exception {
        assertTrue(res.code() != 0, () -> "an unsupported zone exits non-zero: " + res.out() + res.err());
        JsonNode j = M.readTree(res.out());   // MUST parse — a structured envelope, not a bare stderr line
        assertEquals("refused", j.path("outcome").asText(),
                () -> "an unsupported zone is a structured refusal: " + res.out());
        JsonNode diag = j.path("diagnostics").path(0);
        assertEquals("RK_INTERPRETER_TIMEZONE_UNSUPPORTED", diag.path("code").asText(),
                () -> "carries RK_INTERPRETER_TIMEZONE_UNSUPPORTED: " + res.out());
        assertTrue(diag.path("summary").asText().contains(zone),
                () -> "the refusal names the offending zone: " + res.out());
        assertTrue(!diag.path("fix").asText().isBlank(), () -> "carries a corrective fix: " + res.out());
        assertTrue(!diag.path("explain").asText().isBlank(), () -> "carries the scope explanation: " + res.out());
    }

    private static void assertZoneInvalid(CliTestSupport.Result res, String zone) throws Exception {
        assertTrue(res.code() != 0, () -> "an invalid zone exits non-zero: " + res.out() + res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.path("outcome").asText(), res::out);
        JsonNode diag = j.path("diagnostics").path(0);
        assertEquals("RK_INVALID_CONFIG_VALUE", diag.path("code").asText(), res::out);
        assertTrue(diag.path("summary").asText().contains(zone), res::out);
        assertTrue(!diag.path("fix").asText().isBlank(), res::out);
        assertTrue(!res.out().contains("RK_INTERPRETER_TIMEZONE_UNSUPPORTED"),
                () -> "invalid input is not mislabeled as a legal scope limit: " + res.out());
    }

    private static java.util.List<CliTestSupport.Result> runtimeResults(Path model, Path doc) {
        return java.util.List.of(
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", doc.toString()),
                CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", doc.toString()),
                CliTestSupport.run("-m", model.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", doc.toString()));
    }

    @Test
    void allInterpreterVerbsRefuseAnUnsupportedZone(@TempDir Path dir) throws Exception {
        Path model = zoneModel(dir, "America/New_York");
        Path doc = emptyDoc(dir);
        for (CliTestSupport.Result result : runtimeResults(model, doc)) {
            assertZoneRefused(result, "America/New_York");
        }
    }

    @Test
    void theSameModelIsKernelCheckValid_theDocumentedSplit(@TempDir Path dir) throws Exception {
        // The kernel accepts America/New_York (a valid IANA id), so `model check` does NOT reject on the zone —
        // proving eval's refusal is a deliberate interpreter scope limit, not model invalidity.
        Path model = zoneModel(dir, "America/New_York");
        CliTestSupport.Result check = CliTestSupport.run("-m", model.toString(), "model", "check");
        assertEquals(0, check.code(), check::err);
        JsonNode json = M.readTree(check.out());
        assertEquals("read", json.path("outcome").asText(), check::out);
        assertTrue(json.path("valid").asBoolean(), check::out);
        assertEquals(0, json.path("diagnostics").size(), check::out);
    }

    @Test
    void allInterpreterVerbsRejectInvalidExplicitZonesInsteadOfEvaluatingUtc(@TempDir Path dir) throws Exception {
        Path doc = emptyDoc(dir);
        for (String zone : java.util.List.of("", "   ", "Europe/Berln")) {
            Path model = zoneModel(dir, zone);
            for (CliTestSupport.Result result : runtimeResults(model, doc)) {
                assertZoneInvalid(result, zone);
            }
        }
    }
}
