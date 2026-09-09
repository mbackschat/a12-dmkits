package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** The black-box public contract, executed against the exact packaged launcher selected by Gradle. */
@Execution(ExecutionMode.CONCURRENT)
class DmtoolPublicContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUNTIME = Set.of("model eval", "model compute", "rule eval");

    @Test
    void packagedVersionIdentifiesItsBaseRevisionAndDivergence(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Properties build = packagedVersionProperties();
        String revision = build.getProperty("sourceRevision");
        String state = build.getProperty("sourceState");

        PackagedDmtool.Result human = dmtool.run("--version");
        assertEquals(0, human.code(), human::err);
        assertTrue(human.out().contains("source: " + revision + " (" + state.toLowerCase() + ")"), human::out);

        PackagedDmtool.Result machine = dmtool.run("manifest");
        assertEquals(0, machine.code(), machine::err);
        JsonNode source = MAPPER.readTree(machine.out()).path("version").path("source");
        assertEquals(revision, source.path("revision").asText(), machine::out);
        assertEquals(state, source.path("state").asText(), machine::out);
    }

    @Test
    void packagedOperatorListMapsCatalogIdsToAuthoredKeywords(@TempDir Path dir) throws Exception {
        PackagedDmtool.Result result = new PackagedDmtool(dir).run("operators");
        assertEquals(0, result.code(), result::err);
        JsonNode validType = java.util.stream.StreamSupport.stream(
                        MAPPER.readTree(result.out()).path("operators").spliterator(), false)
                .filter(operator -> "ValidType".equals(operator.path("id").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ValidType is absent from packaged operators output"));
        assertEquals("Valid", validType.path("keyword").asText(), result::out);
    }

    @Test
    void packagedManifestOwnsTheCompleteOperationalDenominator(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        PackagedDmtool.Result result = dmtool.run("manifest");
        assertEquals(0, result.code(), result::err);
        JsonNode manifest = MAPPER.readTree(result.out());

        int leaves = 0;
        int writes = 0;
        int previews = 0;
        Set<String> runtime = new HashSet<>();
        for (JsonNode verb : manifest.path("verbs")) {
            leaves++;
            String name = verb.path("verb").asText();
            assertFalse(verb.path("operationalProfiles").isEmpty(), () -> name + " has no profiles");
            for (JsonNode profile : verb.path("operationalProfiles")) {
                for (String key : List.of("id", "when", "execution", "effect", "failurePersistence",
                        "dryRun", "aggregateSuccess", "topLevelOk", "returns", "provenance")) {
                    assertTrue(profile.has(key), () -> name + "#" + profile.path("id").asText() + " lacks " + key);
                }
                assertEquals("CASE_INSENSITIVE", profile.path("when").path("valueComparison").asText(), name);
                if (Set.of("MODEL_WRITE", "ARTIFACT_WRITE", "MULTI_FILE_WRITE", "TRANSACTION", "DISPATCH")
                        .contains(profile.path("effect").asText())) {
                    writes++;
                }
                if ("ACTIVE".equals(profile.path("dryRun").asText())) previews++;
                if ("DM_INTERPRETER_RUNTIME".equals(profile.path("execution").asText())) runtime.add(name);
            }
        }
        assertEquals(ManifestVerbTest.EXPECTED_VERBS.size(), leaves);
        assertEquals(49, writes);
        assertEquals(39, previews);
        assertEquals(RUNTIME, runtime, "only the three interpreter runtime routes may claim runtime execution");
    }

    @Test
    void packagedSchemaAndManifestRenderTheSameOperationalOwner(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        Map<String, PackagedDmtool.Result> rendered = new HashMap<>();
        int checked = 0;
        for (JsonNode verb : manifest.path("verbs")) {
            String name = verb.path("verb").asText();
            assertEquals(name, verb.path("operationalSchema").asText(), () -> name + " lacks its operational pointer");
            for (String key : List.of("schema", "operationalSchema")) {
                if (!verb.hasNonNull(key)) {
                    continue;
                }
                String pointer = verb.path(key).asText();
                PackagedDmtool.Result scalar = rendered.get(pointer);
                if (scalar == null) {
                    scalar = dmtool.run("schema", pointer);
                    rendered.put(pointer, scalar);
                }
                assertEquals(0, scalar.code(), name + " scalar " + key + ": " + scalar.err());
            }
            PackagedDmtool.Result result = rendered.get(verb.path("operationalSchema").asText());
            JsonNode contract = MAPPER.readTree(result.out());
            assertEquals(verb.path("operationalProfiles"), contract.path("operationalProfiles"),
                    () -> name + " manifest/schema profiles drifted");
            checked++;
        }
        assertEquals(ManifestVerbTest.EXPECTED_VERBS.size(), checked,
                "every live leaf projects its operational owner through schema");

        PackagedDmtool.Result scalarFieldAdd = rendered.get("field add");
        PackagedDmtool.Result tokenizedFieldAdd = dmtool.run("schema", "field", "add");
        assertEquals(0, scalarFieldAdd.code(), scalarFieldAdd::err);
        assertEquals(tokenizedFieldAdd.out(), scalarFieldAdd.out(),
                "the reported field-add failure must stay locked through the packaged launcher");

        for (String frame : List.of("apply", "batch")) {
            JsonNode schema = MAPPER.readTree(dmtool.run("schema", frame).out());
            assertEquals(manifestVerb(manifest, frame).path("operationalProfiles"),
                    schema.path("operationalProfiles"), frame + " frame schema drifted");
        }
    }

    @Test
    void packagedWorkedInvocationsRespectTheirDeclaredOutputAndEnvelopeContracts(@TempDir Path dir)
            throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        JsonNode examples = VerbDemonstrationTest.corpus();
        int envelopes = 0;
        int raw = 0;
        for (Iterator<String> names = examples.fieldNames(); names.hasNext();) {
            String verb = names.next();
            if (verb.startsWith("_")) continue;
            Path fixture = Files.createDirectories(dir.resolve(verb.replace(' ', '-')));
            JsonNode example = examples.get(verb);
            List<String> command = VerbDemonstrationTest.materialize(verb, example, fixture);
            List<CommandContracts.Profile> matches = CommandContracts.matchingProfiles(command);
            assertEquals(1, matches.size(), () -> verb + " profiles did not partition " + command + ": " + matches);
            CommandContracts.Profile profile = matches.getFirst();
            PackagedDmtool.Result result = dmtool.run(command);
            assertEquals(0, result.code(), () -> verb + " packaged example failed: " + result.err() + result.out());
            String expect = example.path("expect").asText();
            if (Set.of("read", "preview").contains(expect)) {
                JsonNode envelope = MAPPER.readTree(result.out());
                assertEquals(expect, envelope.path("outcome").asText(), () -> verb + ": " + result.out());
                ResultEnvelopeAssertions.assertConforms(envelope, verb);
                switch (profile.provenance().consistency()) {
                    case REQUIRED -> assertTrue(envelope.hasNonNull("verification"),
                            () -> verb + " promised a consistency receipt: " + result.out());
                    case NONE -> assertFalse(envelope.has("verification"),
                            () -> verb + " promised no consistency receipt: " + result.out());
                    case OPTIONAL, NESTED, CHILD_DEFINED, NOT_EMITTED -> { }
                }
                if (profile.provenance().runtime() == CommandContracts.Runtime.REQUIRED) {
                    assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), () -> verb + ": " + result.out());
                    assertTrue(envelope.hasNonNull("engineVersion") && envelope.hasNonNull("toolVersion"), result::out);
                }
                envelopes++;
            } else {
                assertFalse(result.out().isBlank(), () -> verb + " raw route emitted nothing");
                raw++;
            }
        }
        int envelopeCount = envelopes;
        int rawCount = raw;
        assertTrue(envelopeCount > 55, () -> "packaged envelope coverage shrank: " + envelopeCount);
        assertTrue(rawCount >= 7, () -> "packaged artifact coverage shrank: " + rawCount);
    }

    @Test
    void publicEnvelopeDescriptionsDeferToTheOperationalOutputProfile(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        String help = dmtool.run("--help").out();
        assertFalse(PublicOutputClaimsTest.containsBlanketClaim(help.replace('\n', ' ')),
                "root help must not universalize the profiled input/output contract");
        assertFalse(PublicOutputClaimsTest.containsJsonInOutSlogan(help),
                "root help must not use the universal JSON-in/JSON-out slogan");
        assertTrue(help.contains("explicit JSON/text artifact profiles"), help);

        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        JsonNode seedProfiles = manifestVerb(manifest, "model seed").path("operationalProfiles");
        assertEquals(1, seedProfiles.size(), "model seed has one unconditional output contract");
        assertTrue(selectorIsUnconditional(seedProfiles.get(0).path("when")), seedProfiles::toPrettyString);
        assertEquals("RAW_JSON", seedProfiles.get(0).path("returns").path("kind").asText(),
                "model seed is the positive control: its primary output is an artifact, not an envelope");
        Path model = resource(dir.resolve("outputs"), "order-ruled.dm.json");
        PackagedDmtool.Result seed = dmtool.run("-m", model.toString(), "model", "seed", "--seed", "42");
        assertEquals(0, seed.code(), seed::err);
        JsonNode document = MAPPER.readTree(seed.out());
        assertTrue(document.has("Order"), seed::out);
        assertFalse(document.has("outcome"), "model seed must emit the Document artifact, not an envelope");

        JsonNode readProfiles = manifestVerb(manifest, "field read").path("operationalProfiles");
        assertEquals(1, readProfiles.size(), "field read has one primary output contract");
        JsonNode readReturns = readProfiles.get(0).path("returns");
        assertEquals("RESULT_ENVELOPE", readReturns.path("kind").asText());
        JsonNode readViews = readReturns.path("views");
        assertEquals(1, readViews.size(), "field read has one output view override");
        JsonNode textView = readViews.get(0);
        JsonNode textValues = textView.path("when").path("values").path("--text");
        assertEquals(1, textValues.size(), "the raw-text view has one accepted --text value");
        assertEquals("true", textValues.get(0).asText());
        assertEquals("RAW_TEXT", textView.path("kind").asText());
        PackagedDmtool.Result textRead = dmtool.run("-m", model.toString(), "field", "read",
                "/Order/Quantity", "--text=true");
        assertEquals(0, textRead.code(), textRead::err);
        assertFalse(textRead.out().isBlank(), "the RAW_TEXT view must not discard stdout");
        assertTrue(textRead.out().startsWith("read /Order/Quantity"), textRead::out);

        String schemaDescription = MAPPER.readTree(dmtool.run("schema", "result").out())
                .path("description").asText();
        assertOutputSelectionIsExplained(schemaDescription);
        assertFalse(schemaDescription.contains("every `dmtool` verb"), schemaDescription);

        String skill = Files.readString(Path.of("../skill/SKILL.md"));
        assertOutputSelectionIsExplained(skill);
        assertFalse(skill.contains("universal output envelope every verb emits"),
                "the skill must not contradict raw artifact/catalog routes");
    }

    @Test
    void packagedGlobalTextViewUsesTheParsedBooleanValue(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir, "subscription.dm.json");

        PackagedDmtool.Result enabled = dmtool.run("-m", model.toString(), "field", "read",
                "/Subscription/AutoRenew", "--text=true");
        PackagedDmtool.Result disabled = dmtool.run("-m", model.toString(), "field", "read",
                "/Subscription/AutoRenew", "--text=false");

        assertEquals(0, enabled.code(), enabled::err);
        assertFalse(enabled.out().stripLeading().startsWith("{"), enabled::out);
        assertEquals("read", MAPPER.readTree(disabled.out()).path("outcome").asText(), disabled::out);
    }

    @Test
    void packagedPostEvaluationRefusalsKeepRequiredRuntimeProvenance(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path customModel = resource(dir.resolve("model-eval"), "payment-customtype.dm.json");
        Path customDocument = dir.resolve("custom-document.json");
        Files.writeString(customDocument,
                "{\"Payment\":{\"AccountHolder\":\"Acme\",\"Iban\":\"NOT-AN-IBAN\"}}");
        Path ruleModel = resource(dir.resolve("rule-eval"), "order-ruled.dm.json");
        Path ruleDocument = dir.resolve("rule-document.json");
        Files.writeString(ruleDocument, "{\"Order\":{\"CustomerName\":\"Acme\"}}");

        for (PackagedDmtool.Result refusal : List.of(
                dmtool.run("-m", customModel.toString(), "model", "eval", "--instance",
                        customDocument.toString(), "--strict-custom"),
                dmtool.run("-m", ruleModel.toString(), "rule", "eval", "/Order/EligibilityCheck", "--instance",
                        ruleDocument.toString(), "--strict-custom"))) {
            assertEquals(2, refusal.code(), refusal::out);
            JsonNode envelope = MAPPER.readTree(refusal.out());
            assertEquals("refused", envelope.path("outcome").asText(), refusal::out);
            assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), refusal::out);
            assertTrue(envelope.hasNonNull("engineVersion") && envelope.hasNonNull("toolVersion"), refusal::out);
        }
    }

    @Test
    void packagedRuleEvalRunsANonPersistedCandidate(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir.resolve("candidate-rule-eval"), "order-ruled.dm.json");
        String before = Files.readString(model);
        Path document = dir.resolve("candidate-rule-eval.json");
        Files.writeString(document, "{\"Order\":{\"Quantity\":\"150\"}}");

        PackagedDmtool.Result result = dmtool.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", "/Order/Quantity",
                "--instance", document.toString());

        assertEquals(0, result.code(), () -> result.out() + result.err());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("fired", envelope.path("data").path("verdict").asText(), result::out);
        assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), result::out);
        assertFalse(envelope.path("written").asBoolean(), result::out);
        assertEquals(before, Files.readString(model), "packaged candidate eval must leave the model byte-identical");

        PackagedDmtool.Result missing = dmtool.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", "/Order/DefinitelyMissing",
                "--instance", document.toString());
        assertEquals(2, missing.code(), () -> missing.out() + missing.err());
        JsonNode refusal = MAPPER.readTree(missing.out());
        assertEquals("RK_NO_SUCH_FIELD", refusal.path("diagnostics").path(0).path("code").asText(), missing::out);
        assertEquals(before, Files.readString(model), "a packaged rejected candidate must not write the model");
    }

    @Test
    void packagedBatchWritesReplayableKernelObservations(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir.resolve("batch-observations"), "subscription.dm.json");
        Path ops = dir.resolve("batch-observations.json");
        String condition = "[MonthlyFee] > 1000";
        Files.writeString(ops, "[{\"id\":\"fee-cap\",\"verb\":\"rule\",\"args\":[\"check\",\"--field\","
                + "\"/Subscription/Addons/MonthlyFee\",\"--condition\"," + MAPPER.writeValueAsString(condition)
                + ",\"--code\",\"CAP\"]}]");
        Path observations = dir.resolve("kernel-observations.json");

        PackagedDmtool.Result first = dmtool.run("-m", model.toString(), "batch", ops.toString(),
                "--observations", observations.toString());

        assertEquals(0, first.code(), () -> first.out() + first.err());
        String firstBytes = Files.readString(observations);
        JsonNode artifact = MAPPER.readTree(firstBytes);
        assertEquals(2, artifact.path("schemaVersion").asInt());
        assertEquals("fee-cap", artifact.path("observations").get(0).path("id").asText());
        assertTrue(artifact.path("observations").get(0).path("valid").asBoolean());
        JsonNode firstEnvelope = MAPPER.readTree(first.out());
        assertTrue(firstEnvelope.path("written").asBoolean());
        assertEquals(observations.toAbsolutePath().normalize().toString(), firstEnvelope.path("output").asText());
        assertFalse(firstBytes.contains(dir.toString()), "the packaged artifact must exclude local paths");
        JsonNode served = MAPPER.readTree(dmtool.run("schema", "batch").out()).path("observationArtifact");
        assertEquals("BatchObservations", served.path("title").asText());

        PackagedDmtool.Result second = dmtool.run("-m", model.toString(), "batch", ops.toString(),
                "--observations", observations.toString());
        assertEquals(first.code(), second.code(), second::err);
        assertEquals(firstBytes, Files.readString(observations));
    }

    @Test
    void packagedLiteralTextPositionalDoesNotSelectTheTextView(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir, "subscription.dm.json");
        Path literalTextSpec = dir.resolve("--text");
        Files.writeString(literalTextSpec, """
                {
                  "computedField": "/Subscription/Billing/EffectiveFee",
                  "name": "LiteralTextFile",
                  "alternatives": [{"operation":"[/Subscription/Billing/BaseFee]"}],
                  "messages": [{"locale":"en_US","text":"Literal text."},{"locale":"de_DE","text":"Wörtlicher Text."}]
                }
                """);

        PackagedDmtool.Result direct = dmtool.run("-m", model.toString(), "computation", "add", "--dry-run",
                "--", "--text");
        assertEquals(0, direct.code(), () -> direct.out() + direct.err());
        assertEquals("preview", MAPPER.readTree(direct.out()).path("outcome").asText(),
                "a positional named --text must retain the primary JSON envelope");

        Path ops = dir.resolve("literal-text-ops.json");
        Files.writeString(ops, "[{\"id\":\"literal-text\",\"verb\":\"computation\","
                + "\"args\":[\"add\",\"--dry-run\",\"--\",\"--text\"]}]");
        Path observations = dir.resolve("literal-text-observations.json");
        PackagedDmtool.Result batch = dmtool.run("-m", model.toString(), "batch", ops.toString(),
                "--observations", observations.toString());

        assertEquals(0, batch.code(), () -> batch.out() + batch.err());
        JsonNode child = MAPPER.readTree(batch.out()).path("data").path("results").get(0);
        assertTrue(child.has("result"), "batch must receive the child's JSON envelope, not a text-rendered stdout");
        assertFalse(child.has("stdout"), "a positional named --text is not the global option");
        assertEquals("preview", child.path("result").path("outcome").asText());
        assertEquals("literal-text",
                MAPPER.readTree(Files.readString(observations)).path("observations").get(0).path("id").asText());
    }

    @Test
    void packagedRejectedModeCombinationsMatchNoOperationalProfile(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir.resolve("modes"), "order-ruled.dm.json");
        Path schema = dir.resolve("mode.schema.json");
        Files.writeString(schema, "{\"title\":\"R\",\"type\":\"object\"}");
        List<List<String>> rejected = List.of(
                List.of("-m", model.toString(), "patterns", "date-order", "--arg",
                        "earlier=/Order/OrderDate", "--dry-run=false"),
                List.of("-m", model.toString(), "model", "report", "--root", "/Order"),
                List.of("model", "import-jsonschema", "--schema", schema.toString(), "--out-dir",
                        dir.resolve("bundle").toString(), "-o", dir.resolve("ignored.json").toString()),
                List.of("model", "import-jsonschema", "--schema", schema.toString(), "--out-dir",
                        dir.resolve("component-bundle").toString(), "--component", "R"),
                List.of("-m", model.toString(), "meta", "/Order", "--dry-run"));

        for (List<String> command : rejected) {
            assertTrue(CommandContracts.matchingProfiles(command).isEmpty(),
                    () -> "rejected invocation matched an operational profile: " + command);
            PackagedDmtool.Result result = dmtool.run(command);
            assertEquals(2, result.code(), () -> command + ": " + result.out() + result.err());
            assertTrue(result.out().isBlank(), () -> command + " promised no envelope: " + result.out());
            assertFalse(result.err().isBlank(), () -> command + " lacks its corrective usage error");
        }
        assertFalse(Files.exists(dir.resolve("bundle")));
        assertFalse(Files.exists(dir.resolve("ignored.json")));
    }

    private static Path resource(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        try (InputStream input = DmtoolPublicContractTest.class.getResourceAsStream("/models/" + name)) {
            assertTrue(input != null, () -> "missing /models/" + name);
            Files.writeString(target, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        return target;
    }

    private static Properties packagedVersionProperties() throws Exception {
        Path snapshot = Path.of(System.getProperty("dmtool.versionProperties", "")).toAbsolutePath();
        assertTrue(Files.isRegularFile(snapshot),
                () -> "publicContractTest needs -Ddmtool.versionProperties from the packaged build, got "
                        + snapshot);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(snapshot)) {
            properties.load(input);
        }
        assertTrue(properties.containsKey("sourceRevision"), () -> snapshot + " lacks sourceRevision");
        assertTrue(properties.containsKey("sourceState"), () -> snapshot + " lacks sourceState");
        return properties;
    }

    private static JsonNode manifestVerb(JsonNode manifest, String name) {
        for (JsonNode verb : manifest.path("verbs")) {
            if (name.equals(verb.path("verb").asText())) return verb;
        }
        throw new AssertionError("manifest has no " + name);
    }

    private static void assertOutputSelectionIsExplained(String prose) {
        assertTrue(prose.contains("matching `operationalProfiles[].returns.views[]` overrides "
                        + "`operationalProfiles[].returns.kind`"),
                () -> "public output contract must state view-before-primary precedence: " + prose);
        for (String term : List.of("operationalProfiles[].returns.views[]", "operationalProfiles[].returns.kind")) {
            assertTrue(prose.contains(term), () -> "public output contract omits " + term + ": " + prose);
        }
        // DERIVED from the enum, not a hand-kept list. The list form could only check the kinds someone
        // remembered to add to it, so `NDJSON_STREAM` reached the enum and this literal while the skill was
        // left behind — the guard named the term it was itself missing elsewhere. Now a sixth constant fails
        // here until BOTH published surfaces explain it, which is what CLAUDE.md's verb row advertises.
        for (CommandContracts.ReturnKind kind : CommandContracts.ReturnKind.values()) {
            assertTrue(prose.contains(kind.name()),
                    () -> "public output contract omits the " + kind.name() + " return kind: " + prose);
        }
    }

    private static boolean selectorIsUnconditional(JsonNode when) {
        return when.path("supplied").isEmpty()
                && when.path("omitted").isEmpty()
                && when.path("anySet").isEmpty()
                && when.path("noneSet").isEmpty()
                && when.path("values").isEmpty();
    }
}
