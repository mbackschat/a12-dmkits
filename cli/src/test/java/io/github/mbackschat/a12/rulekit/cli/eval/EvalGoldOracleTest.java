package io.github.mbackschat.a12.rulekit.cli.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

import io.github.mbackschat.a12.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * Layer 1 of the self-sufficiency benchmark (EVAL-SPEC §8) — the deterministic oracle. Reads every
 * task in {@code eval/tasks/} and asserts its <b>gold answer validates against the real kernel</b>,
 * so the curated corpus can't silently rot (a gold that stops validating is a red build). No LLM
 * involved — this is the stable, CI-able backbone the soft (agent) layer sits on.
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
    void goldValidatesAgainstTheKernel(Path taskFile) throws IOException {
        JsonNode task = MAPPER.readTree(Files.readString(taskFile));
        assumeTrue("authoring".equals(task.path("family").asText()),
                "oracle covers authoring golds; comprehension scored by the agent layer");

        String model = readModel(task.get("model").asText());
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
