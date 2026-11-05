package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Kernel 31.0's `SumOfTerms` (A12K-3756, KERNEL-FINDINGS KF670) end to end through dmtool alone: `operators` serves
 * it, `rule add` and `computation add` persist it past the kernel gate, and `model eval` / `model compute` evaluate
 * it on a document through the interpreter. The two documents sit on either side of the rule's bar, so the fired
 * set and the computed total each move with the term sum. Own domain (an order's shipping weight).
 */
class SumOfTermsVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TERM = "SumOfTerms(Items*, [Items/Count] * [Items/UnitWeight])";

    @Test
    void operatorsServesTheTermSum() throws Exception {
        var res = CliTestSupport.run("operators", "SumOfTerms");
        assertEquals(0, res.code(), res::err);
        JsonNode operator = MAPPER.readTree(res.out());
        assertEquals(List.of("SumOfTerms", "SummeVonTermen"),
                List.of(operator.path("keyword").path("en").asText(), operator.path("keyword").path("de").asText()));
    }

    @Test
    void aTermSumIsAuthoredCheckedAndEvaluatedThroughTheCli(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("order.json");
        Files.writeString(model, DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .stringField("CustomerName", f -> { })
                        .numberField("TotalWeight", n -> n.fractionDigits(2))
                        .group("Items", i -> i.repeatable(10)
                                .numberField("Count", n -> n.fractionDigits(0))
                                .numberField("UnitWeight", n -> n.fractionDigits(2))))
                .build().toJson());
        Path ruleSpec = write(dir, "rule.json", Map.of("field", "/Order/CustomerName", "code", "TOO_HEAVY",
                "name", "TooHeavy", "condition", "FieldFilled(CustomerName) And " + TERM + " > 1000",
                "messages", List.of(Map.of("locale", "en_US", "text", "The order is too heavy to ship."))));
        Path computationSpec = write(dir, "computation.json", Map.of("computedField", "/Order/TotalWeight",
                "group", "/Order", "name", "Weigh", "alternatives", List.of(Map.of("operation", TERM)),
                "messages", List.of(Map.of("locale", "en_US", "text", "The order's total weight."))));
        var rule = CliTestSupport.run("-m", model.toString(), "rule", "add", ruleSpec.toString());
        assertEquals(0, rule.code(), rule::out);
        var computation = CliTestSupport.run("-m", model.toString(), "computation", "add", computationSpec.toString());
        assertEquals(0, computation.code(), computation::out);

        Path heavy = write(dir, "heavy.json", Map.of("Order", Map.of("CustomerName", "Ada",
                "Items", List.of(Map.of("Count", 3, "UnitWeight", 250), Map.of("Count", 1, "UnitWeight", 400)))));
        Path light = write(dir, "light.json", Map.of("Order", Map.of("CustomerName", "Ada",
                "Items", List.of(Map.of("Count", 3, "UnitWeight", 25)))));
        assertEquals(Map.of("heavy", List.of("TOO_HEAVY"), "light", List.of()),
                Map.of("heavy", fired(model, heavy), "light", fired(model, light)));
        assertEquals(Map.of("heavy", "1150", "light", "75"),
                Map.of("heavy", totalWeight(model, heavy), "light", totalWeight(model, light)));
    }

    private static List<String> fired(Path model, Path document) throws Exception {
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", document.toString());
        assertEquals(0, res.code(), res::out);
        List<String> codes = new ArrayList<>();
        MAPPER.readTree(res.out()).path("data").path("fired").forEach(code -> codes.add(code.asText()));
        return codes;
    }

    private static String totalWeight(Path model, Path document) throws Exception {
        var res = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", document.toString());
        assertEquals(0, res.code(), res::out);
        for (JsonNode computed : MAPPER.readTree(res.out()).path("data").path("computed")) {
            if (computed.path("field").asText().equals("Order/TotalWeight")) {
                return computed.path("value").asText();
            }
        }
        return "(none)";
    }

    private static Path write(Path dir, String name, Object json) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, MAPPER.writeValueAsString(json));
        return file;
    }
}
