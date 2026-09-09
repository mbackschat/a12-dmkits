package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

class BatchObservationInputIdentityTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void messageOnlyVerdictsHaveDistinctStableIdentities(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("model.json");
        String json = CliTestSupport.subscriptionJson();
        Files.writeString(model, json);
        var fee = BoundModel.ofJson(json).num("/Subscription/Addons/MonthlyFee");
        String condition = new DslRenderer().render(fee.gt(1000));
        ArrayNode ops = M.createArrayNode();
        add(ops, "plain", rule(condition, "--message", "en_US=Fee exceeds the cap."));
        add(ops, "invalid", rule(condition, "--message", "en_US=Fee $Missing.value$ exceeds the cap."));
        Path input = dir.resolve("ops.json");
        Files.writeString(input, ops.toString());
        Path artifact = dir.resolve("observations.json");
        var result = CliTestSupport.run("-m", model.toString(), "batch", input.toString(),
                "--observations", artifact.toString());
        assertEquals(0, result.code(), result::out);
        String first = Files.readString(artifact);
        var rows = M.readTree(first).path("observations");
        var children = M.readTree(result.out()).at("/data/results");
        for (int i = 0; i < 2; i++) {
            assertTrue(rows.get(i).path("valid").isBoolean(), first);
            assertEquals("KERNEL_CONFIRMED", children.get(i).at("/result/verification").asText(), result.out());
        }
        assertTrue(rows.get(0).path("valid").asBoolean(), first);
        assertFalse(rows.get(1).path("valid").asBoolean(), first);
        assertFalse(rows.get(1).path("codes").isEmpty(), first);
        assertNotEquals(rows.get(0).path("inputHash"), rows.get(1).path("inputHash"));
        Files.delete(artifact);
        var second = CliTestSupport.run("-m", model.toString(), "batch", input.toString(),
                "--observations", artifact.toString());
        assertEquals(0, second.code(), second::out);
        assertTrue(M.readTree(second.out()).path("written").asBoolean(), second::out);
        assertEquals(first, Files.readString(artifact));
        assertFalse(first.contains("Fee exceeds"));
        assertFalse(first.contains("Missing"));
    }

    @Test
    void candidateMetadataAndComputationTargetsParticipateButLocaleOrderDoesNot(@TempDir Path dir) throws Exception {
        String condition = new DslRenderer().render(BoundModel.ofJson(CliTestSupport.subscriptionJson())
                .num("/Subscription/Addons/MonthlyFee").gt(1000));
        String base = hash(dir, rule(condition));
        for (String option : List.of("--field", "--group", "--code", "--name", "--severity")) {
            var args = rule(condition);
            String value = switch (option) {
                case "--field" -> "/Subscription/Billing/BaseFee";
                case "--group" -> "/Subscription";
                case "--severity" -> "WARNING";
                default -> "Changed";
            };
            int index = args.indexOf(option);
            if (index >= 0) args.set(index + 1, value);
            else args.addAll(List.of(option, value));
            assertNotEquals(base, hash(dir, args), option);
        }
        assertEquals(hash(dir, rule(condition, "--message", "en_US=Cap", "--message", "de_DE=Grenze")),
                hash(dir, rule(condition, "--message", "de_DE=Grenze", "--message", "en_US=Cap")));
        assertEquals("02fba0ab4aa75cf362003a2984715894cf50652b3de7b4e65b02d1178e1bed5f",
                hash(dir, rule(condition, "--message", "en_US=Cap", "--message", "de_DE=Grenze")));
        assertNotEquals(hash(dir, rule(condition, "--message", "en_US=Cap")),
                hash(dir, rule(condition, "--message", "de_DE=Cap")));
        assertEquals(hash(dir, rule(condition, "--message", "en_US=Cap")),
                hash(dir, rule(condition, "--message", "en_US=Old", "--message", "en_US=Cap")));
        Path spec = dir.resolve("spec.json");
        Files.writeString(spec, "{}");
        var modify = new ArrayList<>(List.of("computation", "modify", "/Subscription/First",
                "--spec", spec.toString(), "--dry-run"));
        String first = hash(dir, modify);
        assertEquals("04e10e2892eb4edb4ec30651525e7620c242e078b40c3f954d1b9b4e257d6afa", first);
        Files.writeString(spec, "{}\n");
        assertNotEquals(first, hash(dir, modify));
        Files.writeString(spec, "{}");
        modify.set(2, "/Subscription/Second");
        assertNotEquals(first, hash(dir, modify));
        assertNotEquals(first, hash(dir, List.of("computation", "add", spec.toString(), "--dry-run")));
    }

    private static ArrayList<String> rule(String condition, String... extra) {
        var args = new ArrayList<>(List.of("rule", "check", "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", condition, "--code", "CAP"));
        args.addAll(List.of(extra));
        return args;
    }

    private static void add(ArrayNode ops, String id, List<String> argv) {
        var op = ops.addObject().put("id", id).put("verb", argv.get(0));
        op.set("args", M.valueToTree(argv.subList(1, argv.size())));
    }

    private static String hash(Path dir, List<String> argv) throws Exception {
        ArrayNode ops = M.createArrayNode();
        add(ops, "candidate", argv);
        Path destination = dir.resolve("hash.json");
        var plan = BatchObservationCapture.prepare(ops, dir.resolve("input.json"), destination, null);
        ArrayNode results = M.createArrayNode();
        results.addObject().put("exit", 0);
        plan.write(results);
        return M.readTree(Files.readString(destination)).at("/observations/0/inputHash").asText();
    }
}
