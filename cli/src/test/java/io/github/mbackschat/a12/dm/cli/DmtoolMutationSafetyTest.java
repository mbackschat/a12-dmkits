package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Byte-level mutation safety across all 48 mutating profiles, through the packaged process boundary. */
class DmtoolMutationSafetyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record PreviewCase(String verb, String modelResource, List<String> args) {}

    @Test
    void all39DryRunProfilesAreByteIdenticalAndTheirRealWriteIsThePositiveControl(@TempDir Path dir)
            throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Set<String> exercised = new HashSet<>();
        JsonNode examples = VerbDemonstrationTest.corpus();
        for (Iterator<String> names = examples.fieldNames(); names.hasNext();) {
            String verb = names.next();
            JsonNode example = examples.get(verb);
            if (!"preview".equals(example.path("expect").asText())) continue;
            runPublishedPreview(dmtool, verb, example, dir, exercised);
        }
        for (PreviewCase preview : richAndMultimodePreviews(dir)) {
            runPreviewCase(dmtool, preview, dir, exercised);
        }

        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        Set<String> declared = profileKeys(manifest, profile -> "SUPPORTED".equals(profile.path("dryRun").asText()));
        assertEquals(declared, exercised,
                () -> "dry-run cases and live registry differ; missing=" + difference(declared, exercised)
                        + ", stale=" + difference(exercised, declared));
        assertEquals(39, exercised.size());
    }

    @Test
    void theRemainingNineWriteProfilesPersistAndReadBack(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Set<String> exercised = new HashSet<>();

        Path created = dir.resolve("Created.dm.json");
        assertApplied(dmtool.run("model", "new", "--id", "Created", "--locale", "en_US", "--root", "Created",
                "-o", created.toString()), "model new");
        assertModelChecks(dmtool, created);
        exercised.add("model new#file");

        Path source = resource(dir.resolve("expand"), "order-ruled.dm.json");
        Path expanded = dir.resolve("expanded.dm.json");
        assertApplied(dmtool.run("-m", source.toString(), "model", "expand", "-o", expanded.toString()),
                "model expand");
        assertModelChecks(dmtool, expanded);
        exercised.add("model expand#file");

        Path schema = dir.resolve("simple.schema.json");
        Files.writeString(schema, "{\"title\":\"Imported\",\"type\":\"object\",\"properties\":{"
                + "\"name\":{\"type\":\"string\"}}}");
        Path imported = dir.resolve("Imported.dm.json");
        assertApplied(dmtool.run("model", "import-jsonschema", "--schema", schema.toString(), "-o",
                imported.toString()), "model import-jsonschema file");
        assertModelChecks(dmtool, imported);
        exercised.add("model import-jsonschema#file");

        Path bundleSchema = dir.resolve("bundle.openapi.json");
        Files.writeString(bundleSchema, """
                {"openapi":"3.1.0","info":{"title":"Bundle","version":"1"},"paths":{},
                 "components":{"schemas":{"A":{"type":"object","properties":{"a":{"type":"string"}}},
                 "B":{"type":"object","properties":{"b":{"type":"string"}}}}}}
                """);
        Path bundle = dir.resolve("bundle");
        assertApplied(dmtool.run("model", "import-jsonschema", "--schema", bundleSchema.toString(), "--out-dir",
                bundle.toString()), "model import-jsonschema bundle");
        assertModelChecks(dmtool, bundle.resolve("A.dm.json"));
        assertModelChecks(dmtool, bundle.resolve("B.dm.json"));
        exercised.add("model import-jsonschema#bundle");

        Path exportedSchema = dir.resolve("exported.schema.json");
        assertApplied(dmtool.run("-m", source.toString(), "model", "export-jsonschema", "-o",
                exportedSchema.toString()), "model export-jsonschema");
        assertTrue(MAPPER.readTree(Files.readString(exportedSchema)).isObject());
        exercised.add("model export-jsonschema#file");

        Path profileFile = dir.resolve("profile.json");
        assertApplied(dmtool.run("-m", source.toString(), "profile", "extract", "-o", profileFile.toString()),
                "profile extract");
        assertTrue(MAPPER.readTree(Files.readString(profileFile)).isObject());
        exercised.add("profile extract#file");

        Path synthesized = dir.resolve("synthesized.dm.json");
        assertApplied(dmtool.run("profile", "synthesize", "--input", profileFile.toString(), "--seed", "7", "-o",
                synthesized.toString()), "profile synthesize");
        assertModelChecks(dmtool, synthesized);
        exercised.add("profile synthesize#file");

        Path exportDir = dir.resolve("export-dir");
        PackagedDmtool.Result exported = dmtool.run("-m", source.toString(), "export", "--out-dir",
                exportDir.toString());
        assertEquals(0, exported.code(), exported::err);
        assertTrue(exported.out().isBlank());
        for (String file : List.of("fields.jsonl", "groups.jsonl", "rules.jsonl", "model-card.md")) {
            assertTrue(Files.isRegularFile(exportDir.resolve(file)), file);
        }
        exercised.add("export#directory");

        Path batchModel = resource(dir.resolve("batch"), "subscription.dm.json");
        Path fieldSpec = dir.resolve("batch-field.json");
        Files.writeString(fieldSpec,
                "{\"group\":\"/Subscription/Billing\",\"name\":\"BatchField\",\"kind\":\"STRING\"}");
        Path ops = dir.resolve("batch.json");
        Files.writeString(ops, "[{\"verb\":\"field\",\"args\":[\"add\","
                + MAPPER.writeValueAsString(fieldSpec.toString()) + "]}]");
        PackagedDmtool.Result batchResult = dmtool.run("-m", batchModel.toString(), "batch", ops.toString());
        assertEquals(0, batchResult.code(), batchResult::err);
        assertTrue(MAPPER.readTree(batchResult.out()).path("ok").asBoolean(), batchResult::out);
        assertEquals(0, dmtool.run("-m", batchModel.toString(), "field", "read",
                "/Subscription/Billing/BatchField").code());
        assertModelChecks(dmtool, batchModel);
        exercised.add("batch#dispatch");

        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        Set<String> declared = profileKeys(manifest, profile -> Set.of(
                "MODEL_WRITE", "ARTIFACT_WRITE", "MULTI_FILE_WRITE", "TRANSACTION", "DISPATCH")
                .contains(profile.path("effect").asText()));
        Set<String> dryRunWrites = profileKeys(manifest,
                profile -> "SUPPORTED".equals(profile.path("dryRun").asText()));
        declared.removeAll(dryRunWrites);
        assertEquals(declared, exercised,
                () -> "non-preview write cases and registry differ; missing=" + difference(declared, exercised)
                        + ", stale=" + difference(exercised, declared));
        assertEquals(9, exercised.size());
    }

    @Test
    void everyRegisteredRichInputOwnerRejectsUnknownKeysBeforeWriting(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Set<String> exercised = new HashSet<>();
        unknownEditor(dmtool, dir, "rule-add", "subscription.dm.json", "rule", "add", null, null);
        exercised.add("rule add");
        unknownEditor(dmtool, dir, "computation-add", "subscription.dm.json", "computation", "add", null, null);
        exercised.add("computation add");
        unknownEditor(dmtool, dir, "computation-add", "subscription-computed.dm.json",
                "computation", "modify", "/Subscription/Billing/EffectiveFeeComp", "--spec");
        exercised.add("computation modify");
        unknownEditor(dmtool, dir, "field-add", "subscription.dm.json", "field", "add", null, null);
        exercised.add("field add");
        unknownEditor(dmtool, dir, "field-modify", "subscription.dm.json", "field", "modify", null, null);
        exercised.add("field modify");
        unknownTypedef(dmtool, dir, false);
        exercised.add("typedef add");
        unknownTypedef(dmtool, dir, true);
        exercised.add("typedef modify");

        Path applyModel = resource(dir.resolve("unknown-apply"), "subscription.dm.json");
        Path apply = dir.resolve("unknown-apply.json");
        ObjectNode applyOp = (ObjectNode) schemaExample("apply", 0).get(0).deepCopy();
        applyOp.put("typo", true);
        Files.writeString(apply, MAPPER.writeValueAsString(List.of(applyOp)));
        assertUnknownLeavesModel(dmtool, applyModel,
                List.of("-m", applyModel.toString(), "apply", apply.toString(), "--dry-run"));
        exercised.add("apply");

        Path batchModel = resource(dir.resolve("unknown-batch"), "subscription.dm.json");
        Path fieldSpec = dir.resolve("unknown-batch-field.json");
        Files.writeString(fieldSpec,
                "{\"group\":\"/Subscription\",\"name\":\"NeverWritten\",\"kind\":\"STRING\"}");
        Path batch = dir.resolve("unknown-batch.json");
        Files.writeString(batch, "[{\"verb\":\"field\",\"args\":[\"add\","
                + MAPPER.writeValueAsString(fieldSpec.toString()) + "],\"typo\":true}]");
        assertUnknownLeavesModel(dmtool, batchModel,
                List.of("-m", batchModel.toString(), "batch", batch.toString()));
        exercised.add("batch");

        Path profileModel = resource(dir.resolve("unknown-profile"), "order-ruled.dm.json");
        Path profile = dir.resolve("unknown-profile.json");
        assertApplied(dmtool.run("-m", profileModel.toString(), "profile", "extract", "-o", profile.toString()),
                "profile fixture");
        ObjectNode badProfile = (ObjectNode) MAPPER.readTree(Files.readString(profile));
        badProfile.put("typo", true);
        Files.writeString(profile, badProfile.toPrettyString());
        Path output = dir.resolve("must-not-exist.dm.json");
        PackagedDmtool.Result rejected = dmtool.run("profile", "synthesize", "--input", profile.toString(), "-o",
                output.toString());
        assertEquals(1, rejected.code(), rejected::out);
        JsonNode profileRejection = MAPPER.readTree(rejected.out());
        assertEquals("rejected", profileRejection.path("outcome").asText(), rejected::out);
        assertEquals("RK_UNKNOWN_ARG", profileRejection.path("diagnostics").get(0).path("code").asText(),
                rejected::out);
        assertFalse(Files.exists(output), "unknown profile key must fail before the artifact write");
        exercised.add("profile synthesize");

        Set<String> registered = new HashSet<>(SchemaCommand.INPUT_OPS.keySet());
        registered.addAll(SchemaCommand.FRAMES);
        assertEquals(registered, exercised,
                () -> "rich-input safety cases and registries differ; missing=" + difference(registered, exercised)
                        + ", stale=" + difference(exercised, registered));
    }

    /**
     * Each case proves the persistence label it was registered under, and the registry cross-check is done
     * per label. Merging the two sets let the two labels be swapped without a failure: {@code apply} reverting
     * and {@code batch} keeping its first child are opposite guarantees, so one union set cannot tell which
     * verb made which promise.
     */
    @Test
    void everyDeclaredRollbackOrPartialProfileHasAPackagedLaterFailureDiscriminator(@TempDir Path dir)
            throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Set<String> rolledBack = new HashSet<>();
        Set<String> keptEarlierWork = new HashSet<>();

        Path applyDir = Files.createDirectories(dir.resolve("later-apply"));
        Path applyModel = resource(applyDir, "order-ruled.dm.json");
        String applyBefore = Files.readString(applyModel);
        Path applyOps = applyDir.resolve("ops.json");
        Files.writeString(applyOps, """
                [{"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                 {"target":"field","op":"add","group":"/Order/NoSuchGroup","name":"X","kind":"NUMBER"}]
                """);
        PackagedDmtool.Result apply = dmtool.run("-m", applyModel.toString(), "apply", applyOps.toString());
        assertEquals(1, apply.code(), apply::out);
        assertFalse(MAPPER.readTree(apply.out()).path("data").path("committed").asBoolean(), apply::out);
        assertEquals(applyBefore, Files.readString(applyModel), "apply rolls back the successful first child");
        assertModelChecks(dmtool, applyModel);
        rolledBack.add("apply#commit");

        Path batchDir = Files.createDirectories(dir.resolve("later-batch"));
        Path batchModel = resource(batchDir, "subscription.dm.json");
        Path batchSpec = batchDir.resolve("field.json");
        Files.writeString(batchSpec,
                "{\"group\":\"/Subscription/Billing\",\"name\":\"BatchCommitted\",\"kind\":\"STRING\"}");
        Path batchOps = batchDir.resolve("ops.json");
        String quotedBatchSpec = MAPPER.writeValueAsString(batchSpec.toString());
        Files.writeString(batchOps, "[{\"verb\":\"field\",\"args\":[\"add\"," + quotedBatchSpec
                + "]},{\"verb\":\"field\",\"args\":[\"add\"," + quotedBatchSpec + "]}]");
        PackagedDmtool.Result batch = dmtool.run("-m", batchModel.toString(), "batch", batchOps.toString());
        assertEquals(1, batch.code(), batch::out);
        assertFalse(MAPPER.readTree(batch.out()).path("ok").asBoolean(), batch::out);
        assertEquals(0, dmtool.run("-m", batchModel.toString(), "field", "read",
                "/Subscription/Billing/BatchCommitted").code(), "batch preserves the successful first child");
        assertModelChecks(dmtool, batchModel);
        keptEarlierWork.add("batch#dispatch");

        Path extractDir = Files.createDirectories(dir.resolve("later-extract"));
        Path extractModel = extractDir.resolve("order.dm.json");
        String extractSource = io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("order")
                .locale("en_US")
                .rootGroup("Order", order -> order.group("Shipping",
                        shipping -> shipping.field("Zip", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING)))
                .build().toJson();
        Files.writeString(extractModel, extractSource);
        Path blocked = extractDir.resolve("blocked.dm.json");
        Files.createDirectory(blocked);
        PackagedDmtool.Result extract = dmtool.run("-m", extractModel.toString(), "group", "extract",
                "/Order/Shipping", "--reference", "shipping", "-o", blocked.toString());
        assertEquals(2, extract.code(), extract::err);
        Path extracted = extractDir.resolve("shipping.dm.json");
        assertTrue(Files.isRegularFile(extracted), "the first submodel write remains after the later base write");
        assertEquals(extractSource, Files.readString(extractModel));
        assertModelChecks(dmtool, extracted);
        assertModelChecks(dmtool, extractModel);
        keptEarlierWork.add("group extract#commit");

        Path exportDir = Files.createDirectories(dir.resolve("later-export"));
        Path exportModel = resource(exportDir, "order-ruled.dm.json");
        Path artifacts = exportDir.resolve("artifacts");
        Files.createDirectories(artifacts.resolve("groups.jsonl"));
        PackagedDmtool.Result export = dmtool.run("-m", exportModel.toString(), "export", "--out-dir",
                artifacts.toString());
        assertEquals(2, export.code(), export::err);
        assertTrue(Files.isRegularFile(artifacts.resolve("fields.jsonl")),
                "the first export artifact remains after the second write fails");
        assertModelChecks(dmtool, exportModel);
        keptEarlierWork.add("export#directory");

        Path importDir = Files.createDirectories(dir.resolve("later-import"));
        Path schema = importDir.resolve("bundle.openapi.json");
        Files.writeString(schema, """
                {"openapi":"3.1.0","info":{"title":"Bundle","version":"1"},"paths":{},
                 "components":{"schemas":{"A":{"type":"object","properties":{"a":{"type":"string"}}},
                 "B":{"type":"object","properties":{"b":{"type":"string"}}}}}}
                """);
        Path bundle = importDir.resolve("bundle");
        Files.createDirectories(bundle.resolve("B.dm.json"));
        PackagedDmtool.Result imported = dmtool.run("model", "import-jsonschema", "--schema", schema.toString(),
                "--out-dir", bundle.toString());
        assertEquals(2, imported.code(), imported::err);
        assertTrue(Files.isRegularFile(bundle.resolve("A.dm.json")),
                "the first imported component remains after the later component fails");
        assertModelChecks(dmtool, bundle.resolve("A.dm.json"));
        keptEarlierWork.add("model import-jsonschema#bundle");

        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        Set<String> declaredRollback = profileKeys(manifest,
                profile -> "ROLLBACK".equals(profile.path("failurePersistence").asText()));
        Set<String> declaredPartial = profileKeys(manifest,
                profile -> "PARTIAL".equals(profile.path("failurePersistence").asText()));
        assertEquals(declaredRollback, rolledBack,
                () -> "ROLLBACK cases and live registry differ; missing="
                        + difference(declaredRollback, rolledBack) + ", stale="
                        + difference(rolledBack, declaredRollback));
        assertEquals(declaredPartial, keptEarlierWork,
                () -> "PARTIAL cases and live registry differ; missing="
                        + difference(declaredPartial, keptEarlierWork) + ", stale="
                        + difference(keptEarlierWork, declaredPartial));
        assertEquals(1, rolledBack.size());
        assertEquals(4, keptEarlierWork.size());
    }

    @Test
    void aMissingRequiredKeyInALaterBatchFrameItemWritesNothing(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path caseDir = Files.createDirectories(dir.resolve("required-batch"));
        Path model = resource(caseDir, "subscription.dm.json");
        String before = Files.readString(model);
        Path spec = caseDir.resolve("field.json");
        Files.writeString(spec,
                "{\"group\":\"/Subscription/Billing\",\"name\":\"MustNotAppear\",\"kind\":\"STRING\"}");
        Path ops = caseDir.resolve("ops.json");
        Files.writeString(ops, "[{\"verb\":\"field\",\"args\":[\"add\","
                + MAPPER.writeValueAsString(spec.toString()) + "]},{}]");

        PackagedDmtool.Result result = dmtool.run("-m", model.toString(), "batch", ops.toString());

        assertEquals(1, result.code(), result::out);
        assertEquals("RK_INVALID_VALUE",
                MAPPER.readTree(result.out()).path("diagnostics").get(0).path("code").asText(), result::out);
        assertEquals(before, Files.readString(model), "the complete frame is validated before child zero");
        assertModelChecks(dmtool, model);
    }

    @Test
    void profileSynthesisRejectsANestedDynamicMapRequirementBeforeWriting(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir.resolve("profile-required"), "subscription.dm.json");
        PackagedDmtool.Result extracted = dmtool.run("-m", model.toString(), "profile", "extract");
        assertEquals(0, extracted.code(), extracted::err);
        ObjectNode envelope = (ObjectNode) MAPPER.readTree(extracted.out());
        ObjectNode byDepth = (ObjectNode) envelope.at("/data/structure/repeatability/chainProductByDepth");
        assertFalse(byDepth.isEmpty(), extracted::out);
        ((ObjectNode) byDepth.elements().next()).remove("buckets");
        Path profile = dir.resolve("invalid-required.profile.json");
        Files.writeString(profile, envelope.toPrettyString());
        Path output = dir.resolve("must-not-exist-required.dm.json");

        PackagedDmtool.Result rejected = dmtool.run("profile", "synthesize", "--input", profile.toString(),
                "-o", output.toString());

        assertEquals(1, rejected.code(), rejected::err);
        JsonNode result = MAPPER.readTree(rejected.out());
        assertEquals("rejected", result.path("outcome").asText(), rejected::out);
        assertEquals("RK_INVALID_VALUE", result.path("diagnostics").get(0).path("code").asText(), rejected::out);
        assertTrue(result.path("diagnostics").get(0).path("where").path("arg").asText().endsWith("/buckets"),
                rejected::out);
        assertFalse(Files.exists(output), "schema-invalid profile reached the artifact write");
    }

    @Test
    void optionalNullValuesAreRejectedBeforeDispatchOrWriting(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path caseDir = Files.createDirectories(dir.resolve("optional-null"));
        Path model = resource(caseDir, "subscription.dm.json");
        String before = Files.readString(model);
        Path field = caseDir.resolve("field.json");
        Files.writeString(field,
                "{\"group\":\"/Subscription/Billing\",\"name\":\"NullGlobal\",\"kind\":\"STRING\",\"global\":null}");

        PackagedDmtool.Result fieldResult = dmtool.run("-m", model.toString(), "field", "add", field.toString());
        assertSchemaRejection(fieldResult, "global");
        assertEquals(before, Files.readString(model), "schema-invalid optional null reached the model write");

        Path batch = caseDir.resolve("batch.json");
        Files.writeString(batch, "[{\"verb\":\"manifest\",\"args\":null}]");
        PackagedDmtool.Result batchResult = dmtool.run("batch", batch.toString());
        assertSchemaRejection(batchResult, "[]/args");
    }

    private static void runPublishedPreview(PackagedDmtool dmtool, String verb, JsonNode example, Path root,
            Set<String> exercised) throws Exception {
        Path previewDir = Files.createDirectories(root.resolve("preview-" + verb.replace(' ', '-')));
        List<String> preview = VerbDemonstrationTest.materialize(verb, example, previewDir);
        Path previewModel = modelOf(preview);
        if (verb.equals("model normalize")) Files.writeString(previewModel, Files.readString(previewModel) + "\n");
        String before = Files.readString(previewModel);
        PackagedDmtool.Result previewResult = dmtool.run(preview);
        assertPreview(previewResult, verb);
        assertEquals(before, Files.readString(previewModel), () -> verb + " --dry-run changed the model");

        Path commitDir = Files.createDirectories(root.resolve("commit-" + verb.replace(' ', '-')));
        List<String> commit = new ArrayList<>(VerbDemonstrationTest.materialize(verb, example, commitDir));
        commit.remove("--dry-run");
        Path commitModel = modelOf(commit);
        if (verb.equals("model normalize")) Files.writeString(commitModel, Files.readString(commitModel) + "\n");
        String commitBefore = Files.readString(commitModel);
        PackagedDmtool.Result committed = dmtool.run(commit);
        assertApplied(committed, verb);
        assertNotEquals(commitBefore, Files.readString(commitModel),
                () -> verb + " positive control reported applied without changing the model");
        assertModelChecks(dmtool, commitModel);
        exercised.add(verb + "#commit");
    }

    private static void runPreviewCase(PackagedDmtool dmtool, PreviewCase preview, Path root,
            Set<String> exercised) throws Exception {
        Path previewDir = Files.createDirectories(root.resolve("preview-extra-" + preview.verb.replace(' ', '-')));
        Path previewModel = resource(previewDir, preview.modelResource);
        List<String> previewArgs = replaceModel(preview.args, previewModel);
        String before = Files.readString(previewModel);
        PackagedDmtool.Result previewResult = dmtool.run(previewArgs);
        assertPreview(previewResult, preview.verb);
        assertEquals(before, Files.readString(previewModel), () -> preview.verb + " preview changed the model");

        Path commitDir = Files.createDirectories(root.resolve("commit-extra-" + preview.verb.replace(' ', '-')));
        Path commitModel = resource(commitDir, preview.modelResource);
        List<String> commitArgs = new ArrayList<>(replaceModel(preview.args, commitModel));
        commitArgs.remove("--dry-run");
        String commitBefore = Files.readString(commitModel);
        PackagedDmtool.Result committed = dmtool.run(commitArgs);
        assertApplied(committed, preview.verb);
        assertNotEquals(commitBefore, Files.readString(commitModel),
                () -> preview.verb + " positive control did not mutate");
        assertModelChecks(dmtool, commitModel);
        exercised.add(preview.verb + "#commit");
    }

    private static List<PreviewCase> richAndMultimodePreviews(Path dir) throws Exception {
        Path rule = writeSchemaExample(dir, "rule-add", 0, "rule.json");
        Path computation = writeSchemaExample(dir, "computation-add", 0, "computation.json");
        Path fieldAdd = writeSchemaExample(dir, "field-add", 0, "field-add.json");
        Path fieldModify = writeSchemaExample(dir, "field-modify", 0, "field-modify.json");
        Path apply = writeSchemaExample(dir, "apply", 0, "apply.json");
        return List.of(
                new PreviewCase("rule add", "subscription.dm.json",
                        List.of("-m", "<model>", "rule", "add", rule.toString(), "--dry-run")),
                new PreviewCase("computation add", "subscription.dm.json",
                        List.of("-m", "<model>", "computation", "add", computation.toString(), "--dry-run")),
                new PreviewCase("computation modify", "subscription-computed.dm.json",
                        List.of("-m", "<model>", "computation", "modify",
                                "/Subscription/Billing/EffectiveFeeComp", "--spec", computation.toString(), "--dry-run")),
                new PreviewCase("field add", "subscription.dm.json",
                        List.of("-m", "<model>", "field", "add", fieldAdd.toString(), "--dry-run")),
                new PreviewCase("field modify", "subscription.dm.json",
                        List.of("-m", "<model>", "field", "modify", fieldModify.toString(), "--dry-run")),
                new PreviewCase("apply", "subscription.dm.json",
                        List.of("-m", "<model>", "apply", apply.toString(), "--dry-run")),
                new PreviewCase("meta", "order-ruled.dm.json",
                        List.of("-m", "<model>", "meta", "/Order/Quantity", "--annotation", "owner=qa", "--dry-run")),
                new PreviewCase("patterns", "order-ruled.dm.json",
                        List.of("-m", "<model>", "patterns", "date-order", "--arg",
                                "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate", "--apply", "--dry-run")));
    }

    private static void unknownEditor(PackagedDmtool dmtool, Path dir, String schema, String modelResource,
            String target, String op, String positional, String specFlag) throws Exception {
        Path caseDir = Files.createDirectories(dir.resolve("unknown-" + target + "-" + op));
        Path model = resource(caseDir, modelResource);
        ObjectNode bad = (ObjectNode) schemaExample(schema, 0).deepCopy();
        bad.put("typo", true);
        Path spec = caseDir.resolve("spec.json");
        Files.writeString(spec, bad.toPrettyString());
        List<String> args = new ArrayList<>(List.of("-m", model.toString(), target, op));
        if (positional != null) args.add(positional);
        if (specFlag != null) args.add(specFlag);
        args.add(spec.toString());
        args.add("--dry-run");
        assertUnknownLeavesModel(dmtool, model, args);
    }

    private static void unknownTypedef(PackagedDmtool dmtool, Path dir, boolean modify) throws Exception {
        Path caseDir = Files.createDirectories(dir.resolve("unknown-typedef-" + (modify ? "modify" : "add")));
        Path model = resource(caseDir, modify ? "typedefs.dm.json" : "subscription.dm.json");
        ObjectNode bad = (ObjectNode) schemaExample("typedef-add", 0).deepCopy();
        bad.put("typo", true);
        Path spec = caseDir.resolve("spec.json");
        Files.writeString(spec, bad.toPrettyString());
        String id = modify ? "PostalCodeType" : "NewType";
        assertUnknownLeavesModel(dmtool, model,
                List.of("-m", model.toString(), "typedef", modify ? "modify" : "add", "--id", id,
                        spec.toString(), "--dry-run"));
    }

    private static void assertUnknownLeavesModel(PackagedDmtool dmtool, Path model, List<String> args)
            throws Exception {
        String before = Files.readString(model);
        PackagedDmtool.Result rejected = dmtool.run(args);
        assertEquals(1, rejected.code(), () -> rejected.err() + rejected.out());
        JsonNode envelope = MAPPER.readTree(rejected.out());
        assertEquals("rejected", envelope.path("outcome").asText(), rejected::out);
        assertTrue(envelope.toString().contains("RK_UNKNOWN_ARG"), rejected::out);
        assertEquals(before, Files.readString(model), "unknown key reached the write path");
    }

    private static void assertSchemaRejection(PackagedDmtool.Result rejected, String arg) throws Exception {
        assertEquals(1, rejected.code(), () -> rejected.err() + rejected.out());
        JsonNode envelope = MAPPER.readTree(rejected.out());
        assertEquals("rejected", envelope.path("outcome").asText(), rejected::out);
        assertEquals("RK_INVALID_VALUE", envelope.path("diagnostics").get(0).path("code").asText(), rejected::out);
        assertEquals(arg, envelope.path("diagnostics").get(0).path("where").path("arg").asText(), rejected::out);
        assertFalse(envelope.path("written").asBoolean(), rejected::out);
    }

    private static void assertPreview(PackagedDmtool.Result result, String label) throws Exception {
        assertEquals(0, result.code(), () -> label + ": " + result.err() + result.out());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("preview", envelope.path("outcome").asText(), () -> label + ": " + result.out());
        assertFalse(envelope.path("written").asBoolean(), () -> label + ": " + result.out());
        ResultEnvelopeAssertions.assertConforms(envelope, label);
    }

    private static void assertApplied(PackagedDmtool.Result result, String label) throws Exception {
        assertEquals(0, result.code(), () -> label + ": " + result.err() + result.out());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("applied", envelope.path("outcome").asText(), () -> label + ": " + result.out());
        assertTrue(envelope.path("written").asBoolean(), () -> label + ": " + result.out());
        ResultEnvelopeAssertions.assertConforms(envelope, label);
    }

    private static void assertModelChecks(PackagedDmtool dmtool, Path model) throws Exception {
        PackagedDmtool.Result checked = dmtool.run("-m", model.toString(), "model", "check");
        assertEquals(0, checked.code(), () -> model + ": " + checked.err() + checked.out());
        assertTrue(MAPPER.readTree(checked.out()).path("valid").asBoolean(), checked::out);
    }

    private static Path modelOf(List<String> args) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).equals("-m") || args.get(i).equals("--model")) return Path.of(args.get(i + 1));
        }
        throw new AssertionError("no model in " + args);
    }

    private static List<String> replaceModel(List<String> args, Path model) {
        return args.stream().map(value -> value.equals("<model>") ? model.toString() : value).toList();
    }

    private static Path resource(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path target = dir.resolve(Path.of(name).getFileName());
        try (InputStream input = DmtoolMutationSafetyTest.class.getResourceAsStream("/models/" + name)) {
            assertTrue(input != null, () -> "missing /models/" + name);
            Files.writeString(target, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        return target;
    }

    private static JsonNode schemaExample(String name, int index) throws Exception {
        try (InputStream input = DmtoolMutationSafetyTest.class.getResourceAsStream(
                "/schema/" + name + ".schema.json")) {
            assertTrue(input != null, () -> "missing schema " + name);
            return MAPPER.readTree(input).path("examples").get(index);
        }
    }

    private static Path writeSchemaExample(Path dir, String name, int index, String file) throws Exception {
        Path path = dir.resolve(file);
        Files.writeString(path, schemaExample(name, index).toPrettyString());
        return path;
    }

    private interface ProfilePredicate { boolean test(JsonNode profile); }

    private static Set<String> profileKeys(JsonNode manifest, ProfilePredicate predicate) {
        Set<String> keys = new HashSet<>();
        for (JsonNode verb : manifest.path("verbs")) {
            for (JsonNode profile : verb.path("operationalProfiles")) {
                if (predicate.test(profile)) keys.add(verb.path("verb").asText() + "#" + profile.path("id").asText());
            }
        }
        return keys;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new HashSet<>(left);
        difference.removeAll(right);
        return difference;
    }
}
