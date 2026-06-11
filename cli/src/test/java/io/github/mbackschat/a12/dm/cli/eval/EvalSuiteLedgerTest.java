package io.github.mbackschat.a12.dm.cli.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The eval suite-ledger guard (EVAL-SPEC §8d) — locks the task categorization so the cheap tiers can't rot.
 * The <b>kit</b> axis is now <b>structural</b>: a task's kit is its subfolder under {@code eval/tasks/}
 * ({@code rulekit/}, {@code modelkit/}, {@code mixed/}) — no derived family→kit map to drift. {@code difficulty}
 * ({@code simple|medium|complex}) is a required tag; the {@code smoke} + {@code simple} run tiers must keep
 * their coverage (so adding a task can't silently erode the gradient).
 */
class EvalSuiteLedgerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DIFFICULTIES = Set.of("simple", "medium", "complex");
    private static final Set<String> KITS = Set.of("rulekit", "modelkit", "mixed");

    private static Path tasksDir() {
        for (String candidate : List.of("../eval/tasks", "eval/tasks", "../../eval/tasks")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static List<Path> tasks() throws IOException {
        Path dir = tasksDir();
        if (dir == null) {
            throw new IllegalStateException("eval/tasks not found from " + Path.of("").toAbsolutePath());
        }
        try (var s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** A task's kit = its containing subfolder (the structural source of truth). */
    private static String kit(Path taskFile) {
        return taskFile.getParent().getFileName().toString();
    }

    @Test
    void everyTaskLivesInAKitFolderAndDeclaresADifficulty() throws IOException {
        for (Path f : tasks()) {
            String name = f.getFileName().toString();
            assertTrue(KITS.contains(kit(f)),
                    () -> name + " must live in a kit folder " + KITS + " — was under '" + kit(f) + "'");
            String difficulty = MAPPER.readTree(Files.readString(f)).path("difficulty").asText(null);
            assertTrue(DIFFICULTIES.contains(difficulty),
                    () -> name + " must declare difficulty " + DIFFICULTIES + ", got: " + difficulty);
        }
    }

    @Test
    void smokeCarriesAtLeastOneRulekitAgentRun() throws IOException {
        boolean rulekitSmoke = false;
        for (Path f : tasks()) {
            if ("rulekit".equals(kit(f)) && inSuite(MAPPER.readTree(Files.readString(f)), "smoke")) {
                rulekitSmoke = true;
            }
        }
        assertTrue(rulekitSmoke,
                "the `smoke` suite must include ≥1 rulekit task (its one agentic run); a modelkit smoke run "
                        + "is deferred to Phase 2+ (the modelkit agentic eval path, #45/B)");
    }

    @Test
    void simpleSuiteSpansAllThreeDifficulties() throws IOException {
        Set<String> covered = new TreeSet<>();
        int count = 0;
        for (Path f : tasks()) {
            JsonNode t = MAPPER.readTree(Files.readString(f));
            if (inSuite(t, "simple")) {
                covered.add(t.path("difficulty").asText());
                count++;
            }
        }
        assertTrue(count > 0, "the `simple` suite must be non-empty");
        assertTrue(covered.equals(DIFFICULTIES),
                () -> "the `simple` suite must span all difficulties " + DIFFICULTIES + ", covers: " + covered);
    }

    private static boolean inSuite(JsonNode task, String suite) {
        if (!task.has("suites")) {
            return false;
        }
        for (JsonNode s : task.get("suites")) {
            if (suite.equals(s.asText())) {
                return true;
            }
        }
        return false;
    }
}
