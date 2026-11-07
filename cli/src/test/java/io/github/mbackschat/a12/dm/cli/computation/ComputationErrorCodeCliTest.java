package io.github.mbackschat.a12.dm.cli.computation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * End-to-end lock for a computation's {@code errorCode}: the spec key is A12's member, stored where A12 writes it
 * and shown by every review surface; a blank code is A12's own refusal (KERNEL-FINDINGS KF678).
 */
class ComputationErrorCodeCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPUTATION = "/Subscription/Billing/EffectiveFeeComp";

    @Test
    void addStoresTheCodeAndEveryReviewSurfaceShowsIt(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        var add = CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "spec.json", spec("\"errorCode\": \"FEE_MISMATCH\",")).toString());
        assertEquals(0, add.code(), add::out);
        assertTrue(Files.readString(model).contains("\"errorCode\" : \"FEE_MISMATCH\""));

        assertEquals("FEE_MISMATCH", data(model, "computation", "read", COMPUTATION).path("errorCode").asText());
        assertEquals("FEE_MISMATCH", data(model, "computation", "explain", COMPUTATION).path("errorCode").asText());
        assertEquals("FEE_MISMATCH", data(model, "model", "report").path("computations").get(0).path("errorCode").asText());
    }

    @Test
    void noCodeShowsNone(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "spec.json", spec("")).toString()).code());
        assertTrue(data(model, "computation", "read", COMPUTATION).path("errorCode").isMissingNode());
    }

    @Test
    void aBlankCodeIsA12sRefusalAndWritesNothing(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        String before = Files.readString(model);
        var add = CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "spec.json", spec("\"errorCode\": \" \",")).toString());
        assertEquals(1, add.code(), add::out);
        assertTrue(MAPPER.readTree(add.out()).path("diagnostics").findValuesAsText("code")
                .contains("MVK_ERROR_CODE_MISSING"), add::out);
        assertEquals(before, Files.readString(model));
    }

    private static String spec(String errorCodeMember) {
        return """
                { "computedField": "/Subscription/Billing/EffectiveFee", %s
                  "alternatives": [ { "operation": "[BaseFee]" } ],
                  "messages": [
                    {"locale":"en_US","text":"Effective fee follows base."},
                    {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                  ] }
                """.formatted(errorCodeMember);
    }

    private static JsonNode data(Path model, String... verb) throws Exception {
        String[] args = new String[verb.length + 2];
        args[0] = "-m";
        args[1] = model.toString();
        System.arraycopy(verb, 0, args, 2, verb.length);
        var result = CliTestSupport.run(args);
        assertEquals(0, result.code(), result::err);
        return MAPPER.readTree(result.out()).path("data");
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}
