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
 * Kernel 31.0 admits a text field compared with a labelled enumeration (A12K-3969, KERNEL-FINDINGS KF655), and dmtool
 * carries it end to end: `rule add` persists both operand orders past the kernel gate, and `model eval` compares by
 * the stored value, so a text holding the value's label still fires. The runtime oracle for that last row is the
 * kernel differential {@code TextEnumComparisonDiffTest}; this class locks that dmtool reaches it. Own domain (a
 * subscription request).
 */
class TextEnumComparisonVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void bothOperandOrdersAreAuthoredAndEvaluatedByTheStoredValue(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.json");
        Files.writeString(model, DocumentModelDraft.named("subscription").locale("en_US")
                .rootGroup("Subscription", s -> s
                        .stringField("RequestedPlan", f -> { })
                        .enumField("Plan", e -> e
                                .value("BASIC", l -> l.label("en_US", "Basic plan"))
                                .value("PRO", l -> l.label("en_US", "Pro plan"))))
                .build().toJson());
        addRule(dir, model, "PLAN_MISMATCH", "FieldFilled(RequestedPlan) And [Plan] != [RequestedPlan]");
        addRule(dir, model, "PLAN_MATCH", "[RequestedPlan] == [Plan]");

        Map<String, List<String>> fired = Map.of(
                "value", fired(dir, model, "PRO"),
                "label", fired(dir, model, "Pro plan"),
                "other", fired(dir, model, "BASIC"));
        assertEquals(Map.of(
                "value", List.of("PLAN_MATCH"),
                "label", List.of("PLAN_MISMATCH"),
                "other", List.of("PLAN_MISMATCH")), fired);
    }

    private static void addRule(Path dir, Path model, String code, String condition) throws Exception {
        Path spec = write(dir, code + ".json", Map.of("field", "/Subscription/RequestedPlan", "code", code,
                "name", code, "condition", condition,
                "messages", List.of(Map.of("locale", "en_US", "text", "Check the requested plan."))));
        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());
        assertEquals(0, res.code(), res::out);
    }

    private static List<String> fired(Path dir, Path model, String requested) throws Exception {
        Path document = write(dir, "doc.json", Map.of("Subscription", Map.of("RequestedPlan", requested, "Plan", "PRO")));
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", document.toString());
        assertEquals(0, res.code(), res::out);
        List<String> codes = new ArrayList<>();
        MAPPER.readTree(res.out()).path("data").path("fired").forEach(code -> codes.add(code.asText()));
        return codes.stream().sorted().toList();
    }

    private static Path write(Path dir, String name, Object json) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, MAPPER.writeValueAsString(json));
        return file;
    }
}
