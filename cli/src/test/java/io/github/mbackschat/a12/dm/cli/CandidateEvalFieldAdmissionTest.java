package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Kernel-basis and sibling guard for candidate runtime evaluation's error-field admission. */
class CandidateEvalFieldAdmissionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MISSING = "/Order/DefinitelyMissing";

    @Test
    void theKernelAndBothCandidateRuntimeRoutesRejectANonexistentErrorField(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String modelJson = Files.readString(model);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");

        var kernel = new RuleValidator().check(modelJson,
                new DraftRule("/Order", "MissingFieldCandidate", "../DefinitelyMissing",
                        "[Quantity] > 100", "MISSING", "ERROR"));
        assertFalse(kernel.ok(), "the client refusal must not be broader than the real kernel's admission gate");

        CliTestSupport.Result modelEval = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--condition", "[Quantity] > 100", "--field", MISSING, "--instance", instance.toString());
        CliTestSupport.Result ruleEval = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", MISSING, "--instance", instance.toString());

        assertNoSuchField(modelEval);
        assertNoSuchField(ruleEval);
        assertEquals(modelJson, Files.readString(model), "neither rejected candidate route may mutate the model");
    }

    @Test
    void ruleEvalRejectsRelativeFieldSyntaxBeforeLookingUpTheField(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");

        for (String relative : java.util.List.of("Quantity", "Order/Quantity")) {
            CliTestSupport.Result result = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                    "--condition", "[Quantity] > 100", "--field", relative, "--instance", instance.toString());

            assertEquals(2, result.code(), () -> result.out() + result.err());
            org.junit.jupiter.api.Assertions.assertTrue(
                    (result.out() + result.err()).contains("--field must be an absolute path"),
                    () -> relative + ": " + result.out() + result.err());
        }
    }

    private static void assertNoSuchField(CliTestSupport.Result result) throws Exception {
        assertEquals(2, result.code(), () -> result.out() + result.err());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("refused", envelope.path("outcome").asText(), result::out);
        assertEquals("RK_NO_SUCH_FIELD", envelope.path("diagnostics").path(0).path("code").asText(), result::out);
        assertFalse(envelope.path("written").asBoolean(), result::out);
    }
}
