package io.github.mbackschat.a12.dm.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.cli.CliTestSupport;

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

    private static Path benchmarkWorkflow() {
        for (String candidate : List.of("../eval/benchmark.workflow.js", "eval/benchmark.workflow.js",
                "../../eval/benchmark.workflow.js")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("eval/benchmark.workflow.js not found from " + Path.of("").toAbsolutePath());
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
    void smokeCarriesARulekitAndAModelkitAgentRun() throws IOException {
        Set<String> smokeKits = new TreeSet<>();
        for (Path f : tasks()) {
            if (inSuite(MAPPER.readTree(Files.readString(f)), "smoke")) {
                smokeKits.add(kit(f));
            }
        }
        assertTrue(smokeKits.contains("rulekit"),
                "the `smoke` suite must include ≥1 rulekit task (a rulekit agentic run)");
        assertTrue(smokeKits.contains("modelkit"),
                "the `smoke` suite must include ≥1 modelkit task (a modelkit agentic run — landed with #45/B); "
                        + "tag a `structure` task with suites:[\"smoke\"]");
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

    @Test
    void runtimeTasksCoverEveryLiveDocumentInstanceVerb() throws IOException {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        Set<String> liveRuntimeVerbs = new TreeSet<>();
        for (JsonNode verb : manifest.path("verbs")) {
            for (JsonNode param : verb.path("params")) {
                if ("--instance".equals(param.path("flag").asText())) {
                    liveRuntimeVerbs.add(verb.path("verb").asText());
                }
            }
        }
        assertTrue(!liveRuntimeVerbs.isEmpty(), "positive control: the live CLI must expose document-instance verbs");

        Set<String> covered = new TreeSet<>();
        for (Path f : tasks()) {
            JsonNode task = MAPPER.readTree(Files.readString(f));
            if ("runtime".equals(task.path("family").asText())) {
                assertTrue(task.path("expected").path("humanReadable").asBoolean(),
                        () -> f.getFileName() + " must exercise the human-readable projection");
                assertTrue(!task.path("expected").path("summaryLine").asText().isBlank(),
                        () -> f.getFileName() + " must lock a real human-readable summary");
                covered.add(task.path("expected").path("verb").asText());
            }
        }
        assertTrue(covered.equals(liveRuntimeVerbs),
                () -> "runtime tasks must cover exactly the live --instance verbs; live=" + liveRuntimeVerbs
                        + ", covered=" + covered);
    }

    @Test
    void computationPreflightKeepsTheDiscoveryPromptNeutral() throws IOException {
        List<JsonNode> preflights = new java.util.ArrayList<>();
        for (Path f : tasks()) {
            JsonNode task = MAPPER.readTree(Files.readString(f));
            if ("computation-preflight".equals(task.path("family").asText())) {
                preflights.add(task);
            }
        }
        assertEquals(1, preflights.size(), "the bare-tool suite carries one focused computation preflight");
        JsonNode task = preflights.getFirst();
        assertTrue(!task.path("intent").asText().contains("--dry-run"),
                "the intent must not disclose the option being discovered");
        assertEquals("computation add", task.path("expected").path("verb").asText());
        assertEquals("--dry-run", task.path("expected").path("readOnlyOption").asText());

        String workflow = Files.readString(benchmarkWorkflow());
        int promptStart = workflow.indexOf("function computationPreflightPrompt(task)");
        int promptEnd = workflow.indexOf("\nconst AUTHOR_SCHEMA", promptStart);
        assertTrue(promptStart >= 0 && promptEnd > promptStart,
                "positive control: the guard must locate the computation-preflight prompt body");
        assertTrue(workflow.contains("--dry-run"),
                "positive control: the workflow still names --dry-run outside the neutral producer prompt");
        String producerPrompt = workflow.substring(promptStart, promptEnd);
        assertTrue(!producerPrompt.contains("--dry-run"),
                "the clean agent's producer prompt must not disclose the option being measured");
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
