package io.github.mbackschat.a12.dm.cli.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.core.ModelResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.context.ComputationExplanation;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.adapter.ReferenceRewriter;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Workspace;
import io.github.mbackschat.a12.dm.modelkit.WorkspaceRoles;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;

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
        try (var s = Files.walk(dir)) {   // recurse the kit subfolders (rulekit/ modelkit/ mixed/)
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** A valid kernel identifier for a task's gold element — ids are now slug stems (start with a digit), so prefix a letter. */
    private static String goldName(JsonNode task) {
        return "Gold_" + task.get("id").asText().replaceAll("[^A-Za-z0-9]", "_");
    }

    @Test
    void corpusIsPresent() throws IOException {
        assertFalse(tasks().isEmpty(), "the eval/tasks corpus must hold at least one task");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tasks")
    void taskBackboneHolds(Path taskFile) throws IOException {
        JsonNode task = MAPPER.readTree(Files.readString(taskFile));
        // Explicitly-deferred tasks (e.g. the refactor/rename gold) wait on a later phase — skip their backbone.
        org.junit.jupiter.api.Assumptions.assumeFalse("deferred".equals(task.path("status").asText()),
                taskFile.getFileName() + ": status=deferred — backbone activates with its phase");
        String model = readModelFromTask(task);
        switch (task.path("family").asText()) {
            // Authoring: the gold condition must validate against the real kernel (multi-file models
            // resolve their includes via -w/--workspace → ReferenceResolvers.directory). Self-correction
            // shares the backbone: its gold is the *recovered* rule, which must likewise validate.
            case "authoring", "self-correction" -> assertGoldValidates(task, model, taskFile);
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
            // Computation-comprehension: the named computation reads to Converted AND `computation explain`
            // surfaces its commonPrecondition + alternatives — the structured material a correct NL reading needs.
            case "computation-comprehension" -> assertComputationsExplainable(task, model, taskFile);
            // Modelkit structure (field/group add): applying the gold edit re-binds kernel-valid AND the
            // expected element exists.
            case "structure" -> assertStructureGoldValidates(task, model, taskFile);
            // Safe-delete: cascading the gold removal (the field + its referrers, found via whereUsed)
            // re-binds AND every expectRemoved element is gone.
            case "delete-safety" -> assertDeleteSafetyGoldValidates(task, model, taskFile);
            // Mixed `apply` session: running the gold op-list on one EditSession re-binds at the single gate
            // AND the expected field + rule are present (the north-star read→edit→write flow).
            case "session" -> assertSessionGoldValidates(task, model, taskFile);
            // Refactor (rename/move): rewrite references (the kernel's MoveSupportDM) + relocate the
            // declaration, re-bind, and assert the new path exists while the old is gone.
            case "refactor" -> assertRefactorGoldValidates(task, model, taskFile);
            // Metadata (describe/annotate/comment): apply the gold metadata edit, re-bind kernel-valid, and
            // assert the value reads back (the descriptions/annotations/comment CRUD surface).
            case "metadata" -> assertMetadataGoldValidates(task, model, taskFile);
            // Template (group multiselect/attachment): expand the usageType template, re-bind kernel-valid
            // (the kernel's MultiSelect/AttachmentGroupRule accept it), and assert the group exists.
            case "template" -> assertTemplateGoldValidates(task, model, taskFile);
            // Workspace-roles (R4): lint the fixture workspace via WorkspaceRoles and assert the produced
            // finding codes equal `expectedCodes` — the cross-file access-control backbone (no single model).
            case "workspace-roles" -> assertRolesAudit(task, taskFile);
            default -> fail(taskFile.getFileName() + " has unknown family: " + task.path("family").asText());
        }
    }

    private static void assertGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        String field = gold.get("field").asText();
        int slash = field.lastIndexOf('/');
        String group = field.substring(0, slash);
        String errorEntityRelPath = "../" + field.substring(group.length() + 1);

        DraftRule draft = new DraftRule(group, goldName(task),
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

        DraftComputation draft = new DraftComputation(group, goldName(task), relPath,
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
     * A computation-comprehension backbone: each named computation must read to {@code Converted} AND
     * {@code computation explain} must surface its <b>commonPrecondition</b> + <b>alternatives</b> — the
     * structured material (the *when* + the *bands*) a correct NL reading depends on. The richer "did the
     * agent explain it right?" scoring is the agent layer's rubric (the task's {@code expected}).
     */
    private static void assertComputationsExplainable(JsonNode task, String modelJson, Path taskFile) {
        BoundModel model = BoundModel.ofJson(modelJson);
        KernelAdapter adapter = KernelAdapter.of(model);
        for (JsonNode c : task.get("computations")) {
            String path = c.asText();
            ReadComputation read = adapter.readComputation(path);
            assertTrue(read instanceof ReadComputation.Converted,
                    () -> taskFile.getFileName() + " computation " + path + " must read to Converted, got: " + read);
            ComputationExplanation exp =
                    new ModelContext(model).explainComputation(((ReadComputation.Converted) read).computation());
            assertTrue(exp.commonPrecondition().isPresent(),
                    () -> taskFile.getFileName() + " computation " + path + " explain must surface a commonPrecondition");
            assertFalse(exp.alternatives().isEmpty(),
                    () -> taskFile.getFileName() + " computation " + path + " explain must surface alternatives (the bands)");
        }
    }

    /**
     * A workspace-roles gold (R4): lint the fixture workspace via {@link WorkspaceRoles} and assert the
     * produced finding codes equal the task's {@code expectedCodes}. The cross-file access-control backbone —
     * it locks the gold (the lint really produces those findings on the fixture) the same way an authoring
     * gold is locked by the kernel accepting it.
     */
    private static void assertRolesAudit(JsonNode task, Path taskFile) {
        Path ws = resourceDir(classpathTail(task.get("workspaceDir").asText()));
        List<Workspace.Model> models = Workspace.scan(ws, true);
        WorkspaceRoles.Resolution resolution = WorkspaceRoles.resolve(ws, Optional.empty(), Optional.empty());
        Set<String> codes = new TreeSet<>();
        WorkspaceRoles.check(models, resolution).forEach(f -> codes.add(f.code().name()));
        Set<String> expected = new TreeSet<>();
        task.get("expectedCodes").forEach(c -> expected.add(c.asText()));
        assertEquals(expected, codes,
                () -> taskFile.getFileName() + " lint must produce exactly the expected finding codes");
    }

    /** Resolve a classpath-relative directory (e.g. {@code models/roles-ws}) to a filesystem {@link Path}. */
    private static Path resourceDir(String classpathTail) {
        var url = EvalGoldOracleTest.class.getClassLoader().getResource(classpathTail);
        if (url == null) {
            throw new IllegalStateException("no workspace dir resource '" + classpathTail + "' on the test classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("bad workspace dir URI: " + url, e);
        }
    }

    /**
     * A structure gold (e.g. {@code field add}): apply the edit to an {@link EditSession}, assert it
     * re-binds kernel-valid at the gate, and that the {@code expectField} now exists.
     */
    private static void assertStructureGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        var resolver = resolverFor(task);
        EditSession session = EditSession.read(model, resolver);
        String op = gold.get("op").asText();
        switch (op) {
            case "field add" -> session.addField(gold.get("group").asText(), gold.get("name").asText(),
                    FieldKind.valueOf(gold.get("kind").asText()));
            case "group add" -> session.addGroup(gold.get("parent").asText(), gold.get("name").asText());
            default -> fail(taskFile.getFileName() + " unsupported structure op: " + op);
        }
        assertReBinds(session, taskFile, "structure");
        if (gold.hasNonNull("expectField")) {
            String expect = gold.get("expectField").asText();
            assertTrue(BoundModel.ofJson(session.toJson(), resolver).fieldAt(expect).isPresent(),
                    () -> taskFile.getFileName() + " expected field " + expect + " to exist after the edit");
        }
    }

    /**
     * A template gold ({@code group multiselect} / {@code group attachment}): expand the {@code usageType}
     * template via {@link EditSession}, assert it re-binds kernel-valid (the kernel's
     * {@code MultiSelectGroupRule}/{@code AttachmentGroupRule} accept the composition), and the group exists.
     */
    private static void assertTemplateGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        var resolver = resolverFor(task);
        EditSession session = EditSession.read(model, resolver);
        String op = gold.get("op").asText();
        String parent = gold.get("parent").asText();
        String name = gold.get("name").asText();
        switch (op) {
            case "group multiselect" -> {
                JsonNode valsNode = gold.get("values");
                String[] values = new String[valsNode.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = valsNode.get(i).asText();
                }
                int maxRows = gold.get("maxRows").asInt();
                String field = gold.get("field").asText();
                session.addMultiSelectGroup(parent, name, m -> m.enumField(field, values).maxRows(maxRows));
            }
            case "group attachment" -> session.addAttachmentGroup(parent, name, a -> { });
            default -> fail(taskFile.getFileName() + " unsupported template op: " + op);
        }
        assertReBinds(session, taskFile, "template");
        if (gold.hasNonNull("expectGroup")) {
            String expect = gold.get("expectGroup").asText();
            assertTrue(ModelDocument.read(session.toJson()).groupPaths().contains(expect),
                    taskFile.getFileName() + " expected group " + expect + " to exist after the template");
        }
    }

    /**
     * A metadata gold ({@code describe} / {@code annotate} / {@code comment}): apply the gold metadata edit
     * to an {@link EditSession}, assert it re-binds kernel-valid, and that the value reads back through the
     * kernel (so the slot was honored, not silently dropped).
     */
    private static void assertMetadataGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        var resolver = resolverFor(task);
        EditSession session = EditSession.read(model, resolver);
        String op = gold.get("op").asText();
        boolean external = "external".equals(gold.path("kind").asText("internal"));
        switch (op) {
            case "describe" -> {
                if (external) {
                    session.setExternalDescription(gold.get("ref").asText(), gold.get("locale").asText(),
                            gold.get("text").asText());
                } else {
                    session.setInternalDescription(gold.get("ref").asText(), gold.get("locale").asText(),
                            gold.get("text").asText());
                }
            }
            case "annotate" -> session.setAnnotation(gold.get("ref").asText(),
                    gold.get("name").asText(), gold.get("value").asText());
            case "comment" -> session.setModelComment(gold.get("text").asText());
            default -> fail(taskFile.getFileName() + " unsupported metadata op: " + op);
        }
        assertReBinds(session, taskFile, "metadata");

        ModelDocument doc = ModelDocument.read(session.toJson());
        String where = taskFile.getFileName() + " metadata gold must read back";
        switch (op) {
            case "describe" -> {
                Map<String, String> desc = external
                        ? doc.externalDescription(gold.get("ref").asText())
                        : doc.internalDescription(gold.get("ref").asText());
                assertEquals(gold.get("text").asText(), desc.get(gold.get("locale").asText()), where);
            }
            case "annotate" -> assertEquals(gold.get("value").asText(),
                    doc.annotations(gold.get("ref").asText()).get(gold.get("name").asText()), where);
            case "comment" -> assertEquals(gold.get("text").asText(), doc.modelComment().orElse(null), where);
            default -> { /* unreachable — fail() above */ }
        }
    }

    /**
     * A refactor gold (e.g. {@code field rename}): rewrite every reference via the kernel's
     * {@code MoveSupportDM} ({@code adapter.ReferenceRewriter}), apply the declaration rename/move, assert
     * the result re-binds kernel-valid, and that the new path now exists while the old is gone. The re-bind
     * is the real test — a reference left dangling at the old path would make the kernel reject. Applied via
     * {@link ModelDocument} + the rewriter (mirroring the CLI verb), not a bare {@link EditSession}, because
     * the reference-rewriting engine lives in the adapter module (which {@code core}/{@code EditSession}
     * cannot depend on); a bare rename would dangle the referrers.
     */
    private static void assertRefactorGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        var resolver = resolverFor(task);
        String op = gold.get("op").asText();
        String oldPath = gold.get("field").asText();
        String to = gold.get("to").asText();
        String result;
        try {
            result = switch (op) {
                case "field rename" -> {
                    String newPath = oldPath.substring(0, oldPath.lastIndexOf('/') + 1) + to;
                    yield ModelDocument.read(ReferenceRewriter.rewriteReferences(model, Map.of(oldPath, newPath)))
                            .renameField(oldPath, to).validate(resolver).toJson();
                }
                default -> {
                    fail(taskFile.getFileName() + " unsupported refactor op: " + op);
                    yield null;
                }
            };
        } catch (ModelConsistencyException e) {
            fail(taskFile.getFileName() + " refactor gold must re-bind (kernel-accept), got: " + e.getMessage());
            return;
        }
        String newPath = gold.get("expect").get("to").asText();
        ModelDocument after = ModelDocument.read(result);
        assertTrue(after.fieldPaths().contains(newPath) || after.groupPaths().contains(newPath),
                () -> taskFile.getFileName() + " expected the renamed path " + newPath + " to exist");
        assertFalse(after.fieldPaths().contains(oldPath) || after.groupPaths().contains(oldPath),
                () -> taskFile.getFileName() + " expected the old path " + oldPath + " to be gone");
    }

    /**
     * A delete-safety gold: cascade-remove the field (its referrers found via {@code whereUsed}, exactly as
     * {@code field remove --cascade} does), assert the result re-binds, and that every {@code expectRemoved}
     * element is gone.
     */
    private static void assertDeleteSafetyGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        String field = gold.get("field").asText();
        var resolver = resolverFor(task);
        EditSession session = EditSession.read(model, resolver);
        KernelAdapter before = KernelAdapter.ofJson(model, resolver);
        for (String r : new TreeSet<>(before.whereUsed(field))) {
            session.removeRule(r);
        }
        for (String c : new TreeSet<>(before.whereUsedInComputations(field))) {
            session.removeComputation(c);
        }
        session.removeField(field);
        assertReBinds(session, taskFile, "delete-safety");
        BoundModel after = BoundModel.ofJson(session.toJson(), resolver);
        var rulesAfter = KernelAdapter.of(after).ruleFullNames();
        for (JsonNode rem : gold.get("expectRemoved")) {
            String path = rem.asText();
            assertTrue(after.fieldAt(path).isEmpty() && !rulesAfter.contains(path),
                    () -> taskFile.getFileName() + " expected " + path + " to be removed by the cascade");
        }
    }

    /**
     * A mixed {@code session} gold: run the gold op-list on one {@link EditSession} (field/group add +
     * rule add, interleaved like {@code apply}), assert it re-binds at the single gate, and that the
     * expected field + rule are present.
     */
    private static void assertSessionGoldValidates(JsonNode task, String model, Path taskFile) {
        JsonNode gold = task.get("gold");
        var resolver = resolverFor(task);
        EditSession session = EditSession.read(model, resolver);
        for (JsonNode opNode : gold.get("ops")) {
            String target = opNode.get("target").asText();
            String op = opNode.get("op").asText();
            if (target.equals("field") && op.equals("add")) {
                session.addField(opNode.get("group").asText(), opNode.get("name").asText(),
                        FieldKind.valueOf(opNode.get("kind").asText()));
            } else if (target.equals("group") && op.equals("add")) {
                session.addGroup(opNode.get("parent").asText(), opNode.get("name").asText());
            } else if (target.equals("rule") && op.equals("add")) {
                addRuleFromSpec(session, opNode.get("spec"));
            } else {
                fail(taskFile.getFileName() + " unsupported session op: " + target + " " + op);
            }
        }
        assertReBinds(session, taskFile, "session");
        BoundModel after = BoundModel.ofJson(session.toJson(), resolver);
        JsonNode expect = gold.get("expect");
        if (expect.hasNonNull("field")) {
            String f = expect.get("field").asText();
            assertTrue(after.fieldAt(f).isPresent(),
                    () -> taskFile.getFileName() + " expected field " + f + " after the session");
        }
        if (expect.hasNonNull("rule")) {
            String r = expect.get("rule").asText();
            assertTrue(KernelAdapter.of(after).ruleFullNames().contains(r),
                    () -> taskFile.getFileName() + " expected rule " + r + " after the session");
        }
    }

    /** Add a rule from an {@code apply}-style spec (the same name/group/path derivation as {@code rule add}). */
    private static void addRuleFromSpec(EditSession session, JsonNode spec) {
        String field = spec.get("field").asText();
        int slash = field.lastIndexOf('/');
        String group = field.substring(0, slash);
        String relPath = "../" + field.substring(group.length() + 1);
        String code = spec.get("code").asText();
        String name = spec.hasNonNull("name") ? spec.get("name").asText() : code;
        List<LocalizedText> messages = new ArrayList<>();
        for (JsonNode m : spec.path("messages")) {
            messages.add(new LocalizedText(m.get("locale").asText(), m.get("text").asText()));
        }
        session.addRule(group, name, relPath, code, spec.get("condition").asText(), "ERROR", messages);
    }

    /** The single kernel gate: the session must re-bind (deserialize → expand → checkConsistency). */
    private static void assertReBinds(EditSession session, Path taskFile, String family) {
        try {
            session.validate();
        } catch (ModelConsistencyException e) {
            fail(taskFile.getFileName() + " " + family + " gold must re-bind (kernel-accept), got: " + e.getMessage());
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
        if (task.hasNonNull("model")) {
            return readModel(task.get("model").asText());
        }
        return null; // a workspace-level task (workspace-roles) operates on a directory, not one model
    }

    /**
     * The reference resolver for a task: a {@link ReferenceResolvers#directory} over the {@code includeDir}
     * tree (resolved on the classpath) for a multi-file task, else self-contained. The directory is found
     * via the classloader, so it points at the copied test resources on disk.
     */
    private static ModelResolver resolverFor(JsonNode task) {
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
