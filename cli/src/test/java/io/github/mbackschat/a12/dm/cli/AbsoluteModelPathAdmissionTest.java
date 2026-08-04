package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Class guard for every field-taking author/eval route that derives a group from the last slash. */
class AbsoluteModelPathAdmissionTest {

    @Test
    void everySiblingRejectsAMultiSegmentRelativeFieldBeforeDispatchOrMutation(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);
        Path instance = dir.resolve("order.json");
        Files.writeString(instance, "{\"Order\":{\"Quantity\":\"150\"}}");
        Path ruleSpec = dir.resolve("rule.json");
        Files.writeString(ruleSpec, """
                {"field":"Order/Quantity","condition":"[Quantity] > 100","code":"REL",
                 "messages":[{"locale":"en_US","text":"relative"}]}
                """);
        Path computationSpec = dir.resolve("computation.json");
        Files.writeString(computationSpec, """
                {"computedField":"Order/Quantity","alternatives":[{"operation":"[Quantity]"}],
                 "messages":[{"locale":"en_US","text":"relative"}]}
                """);
        Path apply = dir.resolve("apply.json");
        Files.writeString(apply, """
                [{"target":"rule","op":"add","field":"Order/Quantity","condition":"[Quantity] > 100",
                  "code":"REL","messages":[{"locale":"en_US","text":"relative"}]}]
                """);

        List<CliTestSupport.Result> results = List.of(
                CliTestSupport.run("-m", model.toString(), "rule", "check",
                        "--field", "Order/Quantity", "--condition", "[Quantity] > 100", "--code", "REL"),
                CliTestSupport.run("-m", model.toString(), "rule", "check", "--group", "Order",
                        "--field", "Order/Quantity", "--condition", "[Quantity] > 100", "--code", "REL"),
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString(),
                        "--field", "Order/Quantity", "--condition", "[Quantity] > 100"),
                CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", instance.toString(),
                        "--group", "Order", "--field", "Order/Quantity", "--condition", "[Quantity] > 100"),
                CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--instance", instance.toString(),
                        "--group", "Order", "--field", "Order/Quantity", "--condition", "[Quantity] > 100"),
                CliTestSupport.run("-m", model.toString(), "rule", "eval", "--instance", instance.toString(),
                        "--field", "Order/Quantity", "--condition", "[Quantity] > 100"),
                CliTestSupport.run("-m", model.toString(), "rule", "add", ruleSpec.toString()),
                CliTestSupport.run("-m", model.toString(), "computation", "add", computationSpec.toString()),
                CliTestSupport.run("-m", model.toString(), "apply", apply.toString()));

        for (CliTestSupport.Result result : results) {
            assertNotEquals(0, result.code(), () -> result.out() + result.err());
            assertTrue((result.out() + result.err()).contains("absolute path"),
                    () -> "relative entity path reached a later gate: " + result.out() + result.err());
        }
        assertEquals(before, Files.readString(model), "every relative-path rejection must happen before mutation");
    }
}
