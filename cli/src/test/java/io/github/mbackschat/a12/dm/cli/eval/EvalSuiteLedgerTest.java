package io.github.mbackschat.a12.dm.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        return evalFile("benchmark.workflow.js");
    }

    private static Path gapFinderWorkflow() {
        return evalFile("gap-finder.workflow.js");
    }

    private static Path evalFile(String name) {
        for (String candidate : List.of("../eval/" + name, "eval/" + name, "../../eval/" + name)) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("eval/" + name + " not found from " + Path.of("").toAbsolutePath());
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
        assertTrue(workflow.contains("--dry-run"),
                "positive control: the workflow still names --dry-run outside the neutral producer prompt");
        String rendered = renderComputationPreflightPrompt(workflow, task);
        assertTrue(!rendered.contains("--dry-run"),
                () -> "the fully rendered clean-agent prompt discloses the option being measured: " + rendered);

        String indirectLeak = workflow.replace("${task.intent}", "${PREFLIGHT_OPTION}");
        assertThrows(AssertionError.class, () -> renderComputationPreflightPrompt(indirectLeak, task),
                "positive control: an indirectly interpolated option must not evade the prompt guard");
    }

    @Test
    void gapFinderReconcilesEveryManifestDerivedTargetWithAnAuditReceipt() throws IOException {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        Set<String> liveTargets = new TreeSet<>();
        for (JsonNode verb : manifest.path("verbs")) {
            String[] parts = verb.path("verb").asText().trim().split("\\s+");
            if (parts.length > 1) {
                liveTargets.add(parts[0]);
            }
        }
        assertTrue(!liveTargets.isEmpty(), "positive control: the live manifest must expose top-level targets");

        String workflow = Files.readString(gapFinderWorkflow());
        assertGapFinderCoverageContract(workflow);
        String missingReconciliation = workflow.replace("valid.forEach(assertAuditCoverage)",
                "/* AUDIT MUTANT: reports are accepted without denominator reconciliation */");
        assertThrows(AssertionError.class, () -> assertGapFinderCoverageContract(missingReconciliation),
                "positive control: removing per-report denominator reconciliation must fail");
    }

    private static String renderComputationPreflightPrompt(String workflow, JsonNode task) {
        String marker = "function computationPreflightPrompt(task)";
        int functionStart = workflow.indexOf(marker);
        assertTrue(functionStart >= 0, "the workflow must define the computation-preflight producer prompt");
        int templateStart = workflow.indexOf("return `", functionStart);
        assertTrue(templateStart >= 0, "the computation-preflight prompt must return one inspectable template");
        templateStart += "return `".length();
        int templateEnd = closingBacktick(workflow, templateStart);
        assertTrue(templateEnd > templateStart, "the computation-preflight template must have a closing backtick");

        String template = workflow.substring(templateStart, templateEnd);
        Map<String, String> representative = Map.of(
                "CLI", "bin/dmtool",
                "MODEL_FILE(task)", "/tmp/rk-eval-sandbox/" + task.path("model").asText() + ".dm.json",
                "skillBlock()", "",
                "JSON.stringify(task.proposal)", task.path("proposal").toString(),
                "task.intent", task.path("intent").asText());
        Matcher interpolation = Pattern.compile("\\$\\{([^}]+)}").matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (interpolation.find()) {
            String expression = interpolation.group(1);
            String value = representative.get(expression);
            if (value == null) {
                throw new AssertionError("unreviewed computation-preflight prompt interpolation: ${" + expression + "}");
            }
            interpolation.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        interpolation.appendTail(rendered);
        return rendered.toString().replace("\\`", "`");
    }

    private static int closingBacktick(String source, int start) {
        for (int i = start; i < source.length(); i++) {
            if (source.charAt(i) == '`' && (i == 0 || source.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }

    private static void assertGapFinderCoverageContract(String workflow) {
        assertTrue(workflow.contains("const MANIFEST = A.manifest"),
                "the gap-finder denominator must come from the passed live manifest");
        assertTrue(workflow.contains("const AUDIT_TARGETS = [...new Set(MANIFEST.verbs"),
                "the gap-finder must derive target names from manifest verbs");
        assertTrue(workflow.contains("required: ['findings', 'audits']"),
                "every explorer response must carry structured audit receipts");
        assertTrue(workflow.contains("minItems: 1"),
                "an explorer must not satisfy the audit contract with zero receipts");
        assertTrue(workflow.contains("valid.forEach(assertAuditCoverage)"),
                "every valid explorer report must reconcile against the manifest-derived denominator");
        assertTrue(workflow.contains("receipts.length !== covered.length || missing.length || unexpected.length"),
                "coverage reconciliation must reject duplicate, missing, and unexpected targets");
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
