package io.github.mbackschat.a12.dm.cli.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The eval **suite ledger** guard (EVAL-SPEC §8d) — locks the task categorization so the cheap tiers can't
 * silently rot (the project's anti-drift pattern: every member classified, CI-checked). Two axes + the
 * suites:
 * <ul>
 *   <li><b>difficulty</b> {@code simple|medium|complex} — required on every task (intrinsic; can't be derived);</li>
 *   <li><b>kit</b> {@code rulekit|modelkit} — derived from {@code family} via {@link #KIT}, so a NEW family
 *       must be classified here or the build fails;</li>
 *   <li><b>suites</b> — the {@code smoke} (deterministic core + ≥1 rulekit agent run) and {@code simple}
 *       (a fast agentic spread) tiers: {@code simple} must span all three difficulties; {@code smoke} must
 *       carry at least one rulekit agentic task.</li>
 * </ul>
 * So adding a task forces a difficulty + a kit-classified family, and the cheap tiers keep their coverage.
 */
class EvalSuiteLedgerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DIFFICULTIES = Set.of("simple", "medium", "complex");

    /** Every eval family classified by kit — the source of truth for the rulekit ⟂ modelkit axis. */
    private static final Map<String, String> KIT = Map.ofEntries(
            Map.entry("authoring", "rulekit"),
            Map.entry("authoring-computation", "rulekit"),
            Map.entry("modify-computation", "rulekit"),
            Map.entry("modify-rule", "rulekit"),
            Map.entry("comprehension", "rulekit"),
            Map.entry("self-correction", "rulekit"),
            Map.entry("structure", "modelkit"),
            Map.entry("delete-safety", "modelkit"),
            Map.entry("session", "modelkit"),
            Map.entry("refactor", "modelkit"));

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
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyTaskDeclaresADifficultyAndAKitClassifiedFamily() throws IOException {
        for (Path f : tasks()) {
            JsonNode t = MAPPER.readTree(Files.readString(f));
            String name = f.getFileName().toString();
            String difficulty = t.path("difficulty").asText(null);
            assertTrue(DIFFICULTIES.contains(difficulty),
                    () -> name + " must declare difficulty " + DIFFICULTIES + ", got: " + difficulty);
            String family = t.path("family").asText(null);
            assertTrue(KIT.containsKey(family),
                    () -> name + " has family '" + family + "' not classified as a kit in EvalSuiteLedgerTest.KIT "
                            + "— classify it (rulekit/modelkit) so the cheap tiers stay coherent");
        }
    }

    @Test
    void smokeCarriesAtLeastOneRulekitAgentRun() throws IOException {
        boolean rulekitSmoke = false;
        for (Path f : tasks()) {
            JsonNode t = MAPPER.readTree(Files.readString(f));
            if (inSuite(t, "smoke") && "rulekit".equals(KIT.get(t.path("family").asText()))) {
                rulekitSmoke = true;
            }
        }
        assertTrue(rulekitSmoke,
                "the `smoke` suite must include ≥1 rulekit task (its one agentic run); a modelkit smoke run "
                        + "is deferred to Phase 2+ (the modelkit agentic eval path, suite-ledger §8d / B)");
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
        assertFalse(count == 0, "the `simple` suite must be non-empty");
        assertTrue(covered.equals(DIFFICULTIES),
                () -> "the `simple` suite must span all difficulties " + DIFFICULTIES + ", covers: " + covered);
    }

    @Test
    void everyKitClassifiedFamilyIsActuallyUsed() throws IOException {
        // reverse parity — a kit entry for a family no task uses is stale; catch it so KIT can't drift ahead of the corpus
        Set<String> used = new HashSet<>();
        for (Path f : tasks()) {
            used.add(MAPPER.readTree(Files.readString(f)).path("family").asText());
        }
        for (String family : KIT.keySet()) {
            assertTrue(used.contains(family),
                    () -> "KIT classifies family '" + family + "' but no task uses it — remove the stale entry");
        }
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
