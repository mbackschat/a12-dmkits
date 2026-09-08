package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.cli.computation.ComputationSpec;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

class CandidateMessageCheckTest {
    @Test
    void specAndPatternCandidatesCheckAuthoredMessagesBeforePersisting(@TempDir Path dir) throws Exception {
        var mapper = new ObjectMapper();
        String json = DocumentModelDraft.named("shipment-quantities").locale("en_US")
                .rootGroup("Shipment", root -> root.numberField("Quantity", f -> { })
                        .numberField("Total", f -> { }).dateField("Sent", f -> { })
                        .dateField("Arrived", f -> { })).build().toJson();
        Path model = dir.resolve("model.json");
        Files.writeString(model, json);
        var shipment = EditSession.read(json).group("/Shipment");
        var quantity = shipment.num("Quantity");
        var total = shipment.num("Total");
        var sent = shipment.field("Sent");
        var arrived = shipment.field("Arrived");
        for (boolean malformed : List.of(false, true)) {
            String message = malformed ? "Quantity $" : "Check the quantity.";
            var rule = mapper.createObjectNode().put("field", quantity.field().path().value())
                    .put("condition", new DslRenderer().render(quantity.gt(10))).put("code", "CAP")
                    .put("name", "QuantityCap");
            rule.set("messages", mapper.valueToTree(List.of(Dsl.en(message))));
            Path ruleSpec = dir.resolve("rule.json");
            Files.writeString(ruleSpec, rule.toString());
            var computation = mapper.createObjectNode().put("computedField", total.field().path().value())
                    .put("name", "TotalQuantity");
            computation.putArray("alternatives").addObject().put("operation", new DslRenderer().render(quantity.value()));
            computation.set("messages", mapper.valueToTree(List.of(Dsl.en(message))));
            assertEquals(List.of(Dsl.en(message)), ComputationSpec.parse(computation).draft().messages());
            Path computationSpec = dir.resolve("computation.json");
            Files.writeString(computationSpec, computation.toString());
            List<List<String>> calls = List.of(
                    List.of("rule", "add", ruleSpec.toString(), "--dry-run"),
                    List.of("computation", "add", computationSpec.toString(), "--dry-run"),
                    List.of("patterns", "date-order", "--arg", "earlier=" + sent.path().value(),
                            "--arg", "later=" + arrived.path().value(),
                            "--message", "en_US=" + message),
                    List.of("patterns", "tiered-amount", "--arg", "computed=" + total.field().path().value(),
                            "--arg", "driver=" + quantity.field().path().value(),
                            "--arg", "threshold=10", "--arg", "below=0", "--arg", "atOrAbove=5", "--message", "en_US=" + message));
            for (var call : calls) {
                var args = new java.util.ArrayList<>(List.of("-m", model.toString()));
                args.addAll(call);
                var result = CliTestSupport.run(args.toArray(String[]::new));
                var envelope = mapper.readTree(result.out());
                assertEquals("KERNEL_CONFIRMED", envelope.path("verification").asText(), result::out);
                assertEquals(malformed ? 1 : 0, result.code(), result::out);
                if (malformed && !call.get(0).equals("patterns")) {
                    String candidateSummary = call.get(0).equals("rule") ? "condition" : "computation";
                    assertTrue(envelope.path("summary").asText().startsWith(candidateSummary + " rejected by the kernel ("),
                            result::out);
                }
                if (malformed || call.get(0).equals("patterns")) {
                    assertTrue(envelope.path("valid").isBoolean(), result::out);
                    assertEquals(!malformed, envelope.path("valid").asBoolean(), result::out);
                } else {
                    assertEquals("preview", envelope.path("outcome").asText(), result::out);
                    assertTrue(envelope.path("ok").asBoolean(), result::out);
                }
                assertFalse(envelope.path("written").asBoolean(), result::out);
                assertEquals(json, Files.readString(model));
            }
        }
    }
}
