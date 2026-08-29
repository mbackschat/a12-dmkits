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
            // The envelope, not just the message: the substring check alone passed for as long as this was a
            // plain stderr line, which is the shape being locked out. `where.arg` names the option and
            // `where.path` stays UNSET — the value is refused precisely because it is not a model path.
            JsonNode envelope = MAPPER.readTree(result.out());
            assertEquals("refused", envelope.path("outcome").asText(), result::out);
            JsonNode diagnostic = envelope.path("diagnostics").path(0);
            assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), result::out);
            assertEquals("--field", diagnostic.path("where").path("arg").asText(), result::out);
            assertFalse(diagnostic.path("where").has("path"), result::out);
            org.junit.jupiter.api.Assertions.assertTrue(
                    diagnostic.path("summary").asText().contains("--field must be an absolute path"),
                    () -> relative + ": " + result.out() + result.err());
        }
    }

    /**
     * The group-containment peer of the case above, on {@code rule check}. The refusal is deliberately STRICTER
     * than the kernel, which admits this pair when the rule does not iterate ([KF212]) — decided won't-fix, with
     * the reasoning and its re-open trigger in FEATURE-GAPS §4. So what is locked here is that the refusal is
     * STRUCTURED, not that refusing is correct; if that decision is ever reversed, this test changes with it.
     */
    @Test
    void ruleCheckRefusesAFieldOutsideTheGroupInsideTheEnvelope(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);

        CliTestSupport.Result result = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/Items/Sku", "--group", "/Order/Nope",
                "--condition", "FieldNotFilled(Sku)", "--code", "X");

        assertEquals(2, result.code(), () -> result.out() + result.err());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("refused", envelope.path("outcome").asText(), result::out);
        JsonNode diagnostic = envelope.path("diagnostics").path(0);
        assertEquals("RK_ERROR_FIELD_OUTSIDE_GROUP", diagnostic.path("code").asText(), result::out);
        assertEquals("--field", diagnostic.path("where").path("arg").asText(), result::out);
        assertEquals("/Order/Items/Sku", diagnostic.path("where").path("path").asText(),
                "this value IS a well-formed entity path, so the locus is named rather than left unset");
    }

    private static void assertNoSuchField(CliTestSupport.Result result) throws Exception {
        assertEquals(2, result.code(), () -> result.out() + result.err());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("refused", envelope.path("outcome").asText(), result::out);
        assertEquals("RK_NO_SUCH_FIELD", envelope.path("diagnostics").path(0).path("code").asText(), result::out);
        assertFalse(envelope.path("written").asBoolean(), result::out);
    }
}
