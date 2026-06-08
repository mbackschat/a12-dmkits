package io.github.mbackschat.a12.dm.cli.eval;

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
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

import java.util.ArrayList;

/**
 * Layer 1 of the self-sufficiency benchmark (EVAL-SPEC §8) — the deterministic oracle. Reads every
 * task in {@code eval/tasks/} and asserts its backbone holds against the real kernel, so the curated
 * corpus can't silently rot (a task that stops holding is a red build). No LLM involved — this is the
 * stable, CI-able backbone the soft (agent) layer sits on. Per family: <b>authoring</b> golds must
 * <b>validate</b> (kernel accepts the rendered rule); <b>comprehension</b> rules must <b>read to
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
        String model = readModelFromTask(task);
        switch (task.path("family").asText()) {
            // Authoring: the gold condition must validate against the real kernel (multi-file models
            // resolve their includes via --include-dir → ReferenceResolvers.directory).
            case "authoring" -> assertGoldValidates(task, model, taskFile);
            // Authoring a computation: the gold computation (computed field + alternatives) must validate.
            case "authoring-computation" -> assertComputationGoldValidates(task, model, taskFile);
            // Modify a computation: replacing the named existing computation with the gold must re-bind
            // (kernel-accept) — the deterministic backbone of the F8 `modify` loop.
            case "modify-computation" -> assertModifiedComputationGoldValidates(task, model, taskFile);
            // Modify a rule: replacing the named existing rule's condition with the gold must re-bind
            // (kernel-accept) — the rule-side counterpart of modify-computation.
            case "modify-rule" -> assertModifiedRuleGoldValidates(task, model, taskFile);
            // Comprehension: the model's named rules must read cleanly (Converted, not Opaque) —
            // i.e. they are actually readable by explain/read. The richer "did the agent explain
            // it right?" scoring is the agent layer's job (the task's `expected` rubric).
            case "comprehension" -> assertRulesRead(task, model, taskFile);
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
        ValidationResult result = new RuleValidator().check(model, draft, resolverFor(task));

        assertTrue(result.ok(),
                () -> taskFile.getFileName() + " gold must validate against the kernel, got: " + result.diagnostics());
    }

    /**
     * A modify-computation gold: REPLACE the named existing computation with the gold re-expression and
     * assert the result re-binds (kernel-accepts). Mirrors {@code cli modify --dry-run}'s gate
     * (replaceComputation + BoundModel.ofJson) — the in-place modification, not an add of a second
     * computation on the already-computed field.
     */
    private static void assertModifiedComputationGoldValidates(JsonNode task, String model, Path taskFile) {
        String fullName = task.get("computation").asText();       // e.g. /Subscription/Billing/EffectiveFeeComp
        int nameSlash = fullName.lastIndexOf('/');
        String group = fullName.substring(0, nameSlash);
        String name = fullName.substring(nameSlash + 1);

        JsonNode gold = task.get("gold");
        String computedField = gold.get("computedField").asText();
        String relPath = "../" + computedField.substring(computedField.lastIndexOf('/') + 1);
        List<DraftComputation.Alternative> alternatives = new ArrayList<>();
        for (JsonNode alt : gold.get("alternatives")) {
            String precondition = alt.hasNonNull("precondition") ? alt.get("precondition").asText() : null;
            alternatives.add(new DraftComputation.Alternative(precondition, alt.get("operation").asText()));
        }
        String commonPrecondition = gold.hasNonNull("commonPrecondition") ? gold.get("commonPrecondition").asText() : null;
        DraftComputation draft = new DraftComputation(group, name, relPath, commonPrecondition, alternatives);
        // Messages for every declared locale (a computation needs one per language, like a rule).
        List<LocalizedText> messages = List.of(
                new LocalizedText("en_US", task.get("id").asText() + " modified."),
                new LocalizedText("de_DE", task.get("id").asText() + " geändert."));

        String replaced = ModelWriter.replaceComputation(model, fullName, draft, messages);
        try {
            BoundModel.ofJson(replaced);   // binds + checkConsistency; throws if the kernel rejects
        } catch (RuntimeException e) {
            fail(taskFile.getFileName() + " modify-computation gold must re-bind (kernel-accept), got: " + e.getMessage());
        }
    }

    /**
     * A modify-rule gold: REPLACE the named existing rule's CONDITION with the gold re-expression and
     * assert the result re-binds (kernel-accepts). Mirrors {@code cli modify --rule … --condition …
     * --dry-run}'s gate (replaceCondition + BoundModel.ofJson) — the in-place re-expression of an
     * existing rule, not the authoring of a new one.
     */
    private static void assertModifiedRuleGoldValidates(JsonNode task, String model, Path taskFile) {
        String fullName = task.get("rule").asText();          // e.g. /Order/DeliveryNotBeforeOrder
        String condition = task.get("gold").get("condition").asText();
        String replaced = ModelWriter.replaceCondition(model, fullName, condition);
        try {
            BoundModel.ofJson(replaced, resolverFor(task));   // binds + checkConsistency; throws if rejected
        } catch (RuntimeException e) {
            fail(taskFile.getFileName() + " modify-rule gold must re-bind (kernel-accept), got: " + e.getMessage());
        }
    }

    private static void assertComputationGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        String computedField = gold.get("computedField").asText();
        int slash = computedField.lastIndexOf('/');
        String group = computedField.substring(0, slash);
        String relPath = "../" + computedField.substring(slash + 1);

        List<DraftComputation.Alternative> alternatives = new ArrayList<>();
        for (JsonNode alt : gold.get("alternatives")) {
            String precondition = alt.hasNonNull("precondition") ? alt.get("precondition").asText() : null;
            alternatives.add(new DraftComputation.Alternative(precondition, alt.get("operation").asText()));
        }
        String commonPrecondition = gold.hasNonNull("commonPrecondition")
                ? gold.get("commonPrecondition").asText() : null;

        DraftComputation draft = new DraftComputation(group, task.get("id").asText() + "Gold", relPath,
                commonPrecondition, alternatives);
        ValidationResult result = new RuleValidator().check(model, draft);

        assertTrue(result.ok(),
                () -> taskFile.getFileName() + " computation gold must validate against the kernel, got: "
                        + result.diagnostics());
    }

    private static void assertRulesRead(JsonNode task, String modelJson, Path taskFile) {
        BoundModel model = BoundModel.ofJson(modelJson);
        KernelAdapter adapter = KernelAdapter.of(model);
        for (JsonNode r : task.get("rules")) {
            String rulePath = r.asText();
            ReadResult read = adapter.read(rulePath);
            assertTrue(read instanceof ReadResult.Converted,
                    () -> taskFile.getFileName() + " rule " + rulePath
                            + " must read to Converted (readable by explain), got: " + read);
        }
    }

    /**
     * The task's model JSON. A self-contained task names its model by stem ({@code models/<model>.dm.json}
     * on the classpath); a multi-file task carries an explicit repo-relative {@code modelPath}, which we
     * map onto the classpath by its {@code resources/}-relative tail (so it loads from the same test
     * resources regardless of working directory).
     */
    private static String readModelFromTask(JsonNode task) throws IOException {
        if (task.hasNonNull("modelPath")) {
            return readResource(classpathTail(task.get("modelPath").asText()));
        }
        return readModel(task.get("model").asText());
    }

    /**
     * The reference resolver for a task: a {@link ReferenceResolvers#directory} over the {@code includeDir}
     * tree (resolved on the classpath) for a multi-file task, else self-contained. The directory is found
     * via the classloader, so it points at the copied test resources on disk.
     */
    private static IDocumentModelReferenceResolver resolverFor(JsonNode task) {
        if (!task.hasNonNull("includeDir")) {
            return ReferenceResolvers.selfContained();
        }
        String tail = classpathTail(task.get("includeDir").asText());
        var url = EvalGoldOracleTest.class.getClassLoader().getResource(tail);
        if (url == null) {
            throw new IllegalStateException("no include-dir resource '" + tail + "' on the test classpath");
        }
        try {
            return ReferenceResolvers.directory(Path.of(url.toURI()));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("bad include-dir URI: " + url, e);
        }
    }

    /** The classpath-relative tail of a repo-relative resource path (the part after {@code resources/}). */
    private static String classpathTail(String repoRelativePath) {
        int marker = repoRelativePath.indexOf("resources/");
        return marker < 0 ? repoRelativePath : repoRelativePath.substring(marker + "resources/".length());
    }

    private static String readResource(String classpathPath) throws IOException {
        try (InputStream in = EvalGoldOracleTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("no resource " + classpathPath + " on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readModel(String name) throws IOException {
        return readResource("models/" + name + ".dm.json");
    }
}
