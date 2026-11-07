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
 * Kernel 31.3.1 stores a computation's suppressed warnings as its free-text preamble (A12K-4372, KERNEL-FINDINGS
 * KF298), so a spec still carrying the earlier {@code errorCodesToSuppress} list is refused with the replacement
 * named, on every surface a computation spec arrives through, rather than with the bare allowed-key list, which does
 * not say that the list became a {@code @SuppressWarning(...)} text. Each row also proves nothing was written.
 */
class SuppressionListRetirementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPUTATION = "/Subscription/Billing/EffectiveFeeComp";
    private static final String LEGACY = "\"errorCodesToSuppress\": [\"MVK_INVALID_COMPARE_DEC_PLACES\"],";

    @Test
    void computationAddNamesThePreamble(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path spec = write(dir, "spec.json", spec(LEGACY));
        String before = Files.readString(model);

        assertRefusedWithReplacement(model, before, CliTestSupport.run(
                "-m", model.toString(), "computation", "add", spec.toString()));
    }

    @Test
    void computationModifyNamesThePreamble(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "initial.json", spec("")).toString()).code());
        Path spec = write(dir, "spec.json", spec(LEGACY));
        String before = Files.readString(model);

        assertRefusedWithReplacement(model, before, CliTestSupport.run(
                "-m", model.toString(), "computation", "modify", COMPUTATION, "--spec", spec.toString()));
    }

    @Test
    void anApplyComputationOpNamesThePreamble(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path ops = write(dir, "ops.json", "[" + spec("\"target\":\"computation\",\"op\":\"add\"," + LEGACY) + "]");

        String before = Files.readString(model);

        assertRefusedWithReplacement(model, before, CliTestSupport.run("-m", model.toString(), "apply", ops.toString()));
    }

    private static void assertRefusedWithReplacement(Path model, String before, CliTestSupport.Result result)
            throws Exception {
        assertEquals(1, result.code(), result::out);
        JsonNode refusal = null;
        for (JsonNode d : MAPPER.readTree(result.out()).findValues("diagnostics")) {
            for (JsonNode one : d) {
                if ("errorCodesToSuppress".equals(one.path("where").path("arg").asText())) {
                    refusal = one;
                }
            }
        }
        assertTrue(refusal != null, result::out);
        assertEquals("RK_UNKNOWN_ARG", refusal.path("code").asText(), result::out);
        String fix = refusal.path("fix").asText();
        assertTrue(fix.contains("additionalParserInformation") && fix.contains("@SuppressWarning("), fix);
        assertEquals(before, Files.readString(model), "a refused spec must not mutate the model");
    }

    private static String spec(String extra) {
        return """
                { %s "computedField": "/Subscription/Billing/EffectiveFee",
                  "alternatives": [ { "operation": "[BaseFee]" } ],
                  "messages": [
                    {"locale":"en_US","text":"Effective fee follows base."},
                    {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                  ] }
                """.formatted(extra);
    }

    private static Path write(Path dir, String name, String text) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
