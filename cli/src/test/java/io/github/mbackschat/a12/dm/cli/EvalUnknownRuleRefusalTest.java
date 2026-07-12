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
 * F5 class-guard: an unknown rule handed to the RUNTIME verbs ({@code rule eval} / {@code model eval --rule})
 * is a <b>structured {@code refused} envelope</b> ({@code RK_NO_SUCH_RULE} + a corrective fix), never a bare
 * stderr line — and in BOTH engines (the interpreter, and the kernel leg via the {@code KernelEval} harness).
 * The defect spanned modes: the kernel leg {@code fail()}ed with prose, and the interpreter path either threw
 * "no such rule" ({@code rule eval}) or silently reported the missing rule as {@code fired:false} ({@code model
 * eval}). The read-family guard
 * ({@link ReadMissingTargetEnvelopeTest}) can't reach these — they need a document {@code --instance}.
 */
class EvalUnknownRuleRefusalTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path instance(Path dir) throws IOException {
        Path f = dir.resolve("doc.json");
        Files.writeString(f, "{ \"fields\": {} }");
        return f;
    }

    private static void assertRuleNotFound(CliTestSupport.Result res) throws Exception {
        assertTrue(res.code() != 0, () -> "an unknown rule exits non-zero: " + res.out() + res.err());
        JsonNode j = M.readTree(res.out());   // MUST parse — was a bare stderr line
        assertEquals("refused", j.path("outcome").asText(),
                () -> "an unknown rule is a structured refusal: " + res.out());
        JsonNode diag = j.path("diagnostics").path(0);
        assertEquals("RK_NO_SUCH_RULE", diag.path("code").asText(), () -> "carries RK_NO_SUCH_RULE: " + res.out());
        assertTrue(!diag.path("fix").asText().isBlank(), () -> "carries a corrective fix: " + res.out());
    }

    @Test
    void ruleEvalOfAnUnknownRuleRefusesInBothEngines(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path doc = instance(dir);
        assertRuleNotFound(CliTestSupport.run(
                "-m", model.toString(), "rule", "eval", "/Order/Nope", "--instance", doc.toString()));
        assertRuleNotFound(CliTestSupport.runKernel(
                "-m", model.toString(), "rule", "eval", "/Order/Nope", "--instance", doc.toString()));
    }

    @Test
    void modelEvalNarrowedToAnUnknownRuleRefusesInBothEngines(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path doc = instance(dir);
        assertRuleNotFound(CliTestSupport.run(
                "-m", model.toString(), "model", "eval", "--rule", "/Order/Nope", "--instance", doc.toString()));
        assertRuleNotFound(CliTestSupport.runKernel(
                "-m", model.toString(), "model", "eval", "--rule", "/Order/Nope", "--instance", doc.toString()));
    }
}
