package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
            if (!verb.hasNonNull("schema")) continue;
            String name = verb.path("verb").asText();
            PackagedDmtool.Result result = dmtool.run(("schema " + verb.path("schema").asText()).split(" "));
            assertEquals(0, result.code(), () -> name + ": " + result.err());
            JsonNode contract = MAPPER.readTree(result.out());
            assertEquals(verb.path("operationalProfiles"), contract.path("operationalProfiles"),
                    () -> name + " manifest/schema profiles drifted");
            checked++;
        }
        int schemaCount = checked;
        assertTrue(schemaCount > 65,
                () -> "the schema projection must cover the directional surface; checked=" + schemaCount);

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
            PackagedDmtool.Result result = dmtool.run(command);
            assertEquals(0, result.code(), () -> verb + " packaged example failed: " + result.err() + result.out());
            String expect = example.path("expect").asText();
            if (Set.of("read", "preview").contains(expect)) {
                JsonNode envelope = MAPPER.readTree(result.out());
                assertEquals(expect, envelope.path("outcome").asText(), () -> verb + ": " + result.out());
                ResultEnvelopeAssertions.assertConforms(envelope, verb);
                if (RUNTIME.contains(verb)) {
                    assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), () -> verb + ": " + result.out());
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

    private static JsonNode manifestVerb(JsonNode manifest, String name) {
        for (JsonNode verb : manifest.path("verbs")) {
            if (name.equals(verb.path("verb").asText())) return verb;
        }
        throw new AssertionError("manifest has no " + name);
    }
}
