package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The black-box public contract, executed against the exact packaged launcher selected by Gradle. */
class DmtoolPublicContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUNTIME = Set.of("model eval", "model compute", "rule eval");
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
        assertEquals(81, leaves);
        assertEquals(48, writes);
        assertEquals(39, previews);
        assertEquals(RUNTIME, runtime, "only the three interpreter runtime routes may claim runtime execution");
    }

    @Test
    void packagedSchemaAndManifestRenderTheSameOperationalOwner(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        int checked = 0;
        for (JsonNode verb : manifest.path("verbs")) {
            String name = verb.path("verb").asText();
            assertEquals(name, verb.path("operationalSchema").asText(), () -> name + " lacks its operational pointer");
            PackagedDmtool.Result result = dmtool.run(
                    ("schema " + verb.path("operationalSchema").asText()).split(" "));
            assertEquals(0, result.code(), () -> name + ": " + result.err());
            JsonNode contract = MAPPER.readTree(result.out());
            assertEquals(verb.path("operationalProfiles"), contract.path("operationalProfiles"),
                    () -> name + " manifest/schema profiles drifted");
            checked++;
        }
        assertEquals(81, checked, "every live leaf projects its operational owner through schema");

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

    private static JsonNode manifestVerb(JsonNode manifest, String name) {
        for (JsonNode verb : manifest.path("verbs")) {
            if (name.equals(verb.path("verb").asText())) return verb;
        }
        throw new AssertionError("manifest has no " + name);
    }
}
