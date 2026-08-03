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
    void allTenRichInputOwnersRejectUnknownKeysBeforeWriting(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        int checked = 0;
        checked += unknownEditor(dmtool, dir, "rule-add", "subscription.dm.json", "rule", "add", null, null);
        checked += unknownEditor(dmtool, dir, "computation-add", "subscription.dm.json", "computation", "add", null, null);
        checked += unknownEditor(dmtool, dir, "computation-add", "subscription-computed.dm.json",
                "computation", "modify", "/Subscription/Billing/EffectiveFeeComp", "--spec");
        checked += unknownEditor(dmtool, dir, "field-add", "subscription.dm.json", "field", "add", null, null);
        checked += unknownEditor(dmtool, dir, "field-modify", "subscription.dm.json", "field", "modify", null, null);
        checked += unknownTypedef(dmtool, dir, false);
        checked += unknownTypedef(dmtool, dir, true);

        Path applyModel = resource(dir.resolve("unknown-apply"), "subscription.dm.json");
        Path apply = dir.resolve("unknown-apply.json");
        ObjectNode applyOp = (ObjectNode) schemaExample("apply", 0).get(0).deepCopy();
        applyOp.put("typo", true);
        Files.writeString(apply, MAPPER.writeValueAsString(List.of(applyOp)));
        assertUnknownLeavesModel(dmtool, applyModel,
                List.of("-m", applyModel.toString(), "apply", apply.toString(), "--dry-run"));
        checked++;

        Path batchModel = resource(dir.resolve("unknown-batch"), "subscription.dm.json");
        Path fieldSpec = dir.resolve("unknown-batch-field.json");
        Files.writeString(fieldSpec,
                "{\"group\":\"/Subscription\",\"name\":\"NeverWritten\",\"kind\":\"STRING\"}");
        Path batch = dir.resolve("unknown-batch.json");
        Files.writeString(batch, "[{\"verb\":\"field\",\"args\":[\"add\","
                + MAPPER.writeValueAsString(fieldSpec.toString()) + "],\"typo\":true}]");
        assertUnknownLeavesModel(dmtool, batchModel,
                List.of("-m", batchModel.toString(), "batch", batch.toString()));
        checked++;

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
        assertNotEquals(0, rejected.code(), rejected::out);
        assertFalse(Files.exists(output), "unknown profile key must fail before the artifact write");
        checked++;

        assertEquals(10, checked);
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

    private static int unknownEditor(PackagedDmtool dmtool, Path dir, String schema, String modelResource,
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
        return 1;
    }

    private static int unknownTypedef(PackagedDmtool dmtool, Path dir, boolean modify) throws Exception {
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
        return 1;
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
