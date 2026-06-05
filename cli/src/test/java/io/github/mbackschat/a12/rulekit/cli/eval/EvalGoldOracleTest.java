package io.github.mbackschat.a12.rulekit.cli.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mbackschat.a12.rulekit.adapter.Ingest;
import io.github.mbackschat.a12.rulekit.adapter.KernelAdapter;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * Layer 1 of the self-sufficiency benchmark (EVAL-SPEC §8) — the deterministic oracle. Reads every
 * task in {@code eval/tasks/} and asserts its backbone holds against the real kernel, so the curated
 * corpus can't silently rot (a task that stops holding is a red build). No LLM involved — this is the
 * stable, CI-able backbone the soft (agent) layer sits on. Per family: <b>authoring</b> golds must
 * <b>validate</b> (kernel accepts the rendered rule); <b>comprehension</b> rules must <b>ingest to
 * {@code Converted}</b> (they're readable by {@code explain}). The richer agent scoring is layer 2.
 */
class EvalGoldOracleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path tasksDir() {
        for (String candidate : List.of("../eval/tasks", "eval/tasks", "../../eval/tasks")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    static List<Path> tasks() throws IOException {
        Path dir = tasksDir();
        if (dir == null) {
            throw new IllegalStateException("eval/tasks not found from " + Path.of("").toAbsolutePath());
        }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void corpusIsPresent() throws IOException {
        assertFalse(tasks().isEmpty(), "the eval/tasks corpus must hold at least one task");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tasks")
    void taskBackboneHolds(Path taskFile) throws IOException {
        JsonNode task = MAPPER.readTree(Files.readString(taskFile));
        String model = readModel(task.get("model").asText());
        switch (task.path("family").asText()) {
            // Authoring: the gold condition must validate against the real kernel.
            case "authoring" -> assertGoldValidates(task, model, taskFile);
            // Comprehension: the model's named rules must ingest cleanly (Converted, not Opaque) —
            // i.e. they are actually readable by explain/ingest. The richer "did the agent explain
            // it right?" scoring is the agent layer's job (the task's `expected` rubric).
            case "comprehension" -> assertRulesIngest(task, model, taskFile);
            default -> fail(taskFile.getFileName() + " has unknown family: " + task.path("family").asText());
        }
    }

    private static void assertGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        String field = gold.get("field").asText();
        int slash = field.lastIndexOf('/');
        String group = field.substring(0, slash);
        String errorEntityRelPath = "../" + field.substring(group.length() + 1);

        DraftRule draft = new DraftRule(group, task.get("id").asText() + "Gold",
                errorEntityRelPath, gold.get("condition").asText(), gold.get("code").asText(), "ERROR");
        ValidationResult result = new RuleValidator().check(model, draft);

        assertTrue(result.ok(),
                () -> taskFile.getFileName() + " gold must validate against the kernel, got: " + result.diagnostics());
    }

    private static void assertRulesIngest(JsonNode task, String modelJson, Path taskFile) {
        BoundModel model = BoundModel.ofJson(modelJson);
        KernelAdapter adapter = KernelAdapter.of(model);
        for (JsonNode r : task.get("rules")) {
            String rulePath = r.asText();
            Ingest ingest = adapter.ingest(rulePath);
            assertTrue(ingest instanceof Ingest.Converted,
                    () -> taskFile.getFileName() + " rule " + rulePath
                            + " must ingest to Converted (readable by explain), got: " + ingest);
        }
    }

    private static String readModel(String name) throws IOException {
        try (InputStream in = EvalGoldOracleTest.class.getClassLoader()
                .getResourceAsStream("models/" + name + ".dm.json")) {
            if (in == null) {
                throw new IllegalStateException("no fixture models/" + name + ".dm.json on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
