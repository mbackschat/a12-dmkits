package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A model without {@code content.modelInfo}, which A12 reads and expands but whose rules its runtime cannot run
 * (KERNEL-FINDINGS KF650), is refused by every interpreter-backed verb as a structured, corrective envelope naming
 * the member, while {@code model expand} still answers, because A12 expands it too.
 */
class EvalRuntimeUnevaluableFrameRefusalTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path withoutModelInfo(Path dir) throws IOException {
        ObjectNode model = (ObjectNode) M.readTree(EvalRuntimeUnevaluableFrameRefusalTest.class
                .getResourceAsStream("/models/order-ruled.dm.json"));
        ((ObjectNode) model.path("content")).remove("modelInfo");
        Path f = dir.resolve("order-no-info.dm.json");
        Files.writeString(f, model.toString());
        return f;
    }

    @Test
    void everyInterpreterVerbRefusesAModelA12sRuntimeCannotEvaluate(@TempDir Path dir) throws Exception {
        Path model = withoutModelInfo(dir);
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, "{}");
        for (CliTestSupport.Result res : List.of(
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", doc.toString()),
                CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", doc.toString()),
                CliTestSupport.run("-m", model.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", doc.toString()))) {
            assertTrue(res.code() != 0, () -> "the refusal exits non-zero: " + res.out() + res.err());
            JsonNode j = M.readTree(res.out());
            assertEquals("refused", j.path("outcome").asText(), res::out);
            JsonNode diag = j.path("diagnostics").path(0);
            assertEquals("RK_MODEL_PREPARATION_FAILED", diag.path("code").asText(), res::out);
            assertTrue(diag.path("summary").asText().contains("content.modelInfo"), res::out);
            assertTrue(!diag.path("fix").asText().isBlank(), res::out);
        }
        CliTestSupport.Result expanded = CliTestSupport.run("-m", model.toString(), "model", "expand");
        assertEquals(0, expanded.code(), () -> "A12 expands the model, so expand answers: " + expanded.out() + expanded.err());
    }

    /**
     * English rules in a German model do not parse, so A12 evaluates none of its rules (KERNEL-FINDINGS KF283). The
     * refusal names the rule whose text does not parse; it is the model's, never an internal error of the tool.
     */
    @Test
    void aRuleThatDoesNotParseInItsLanguageIsRefusedAsTheModels(@TempDir Path dir) throws Exception {
        ObjectNode model = (ObjectNode) M.readTree(EvalRuntimeUnevaluableFrameRefusalTest.class
                .getResourceAsStream("/models/order-ruled.dm.json"));
        ((ObjectNode) model.at("/content/modelConfig")).putObject("conditionLanguage").put("code", "de_DE");
        Path f = dir.resolve("order-german.dm.json");
        Files.writeString(f, model.toString());
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, "{}");
        CliTestSupport.Result res = CliTestSupport.run("-m", f.toString(), "model", "eval", "--instance", doc.toString());
        JsonNode diag = M.readTree(res.out()).path("diagnostics").path(0);
        assertEquals("RK_MODEL_PREPARATION_FAILED", diag.path("code").asText(), res::out);
        assertTrue(diag.path("summary").asText().contains("does not parse"), res::out);
    }
}
