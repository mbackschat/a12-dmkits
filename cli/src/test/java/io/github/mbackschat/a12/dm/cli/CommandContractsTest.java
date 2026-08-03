package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** The graduated operational-contract column of the live CLI conformance matrix. */
class CommandContractsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyLiveLeafPublishesAtLeastOneCompleteOperationalProfile() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        int profiles = 0;
        for (JsonNode verb : manifest.path("verbs")) {
            String name = verb.path("verb").asText();
            JsonNode operational = verb.path("operationalProfiles");
            assertTrue(operational.isArray() && !operational.isEmpty(),
                    () -> name + " has no operational profile");
            Set<String> ids = new HashSet<>();
            for (JsonNode profile : operational) {
                profiles++;
                assertTrue(ids.add(profile.path("id").asText()),
                        () -> name + " repeats operational profile id " + profile.path("id").asText());
                for (String key : Set.of("when", "execution", "effect", "failurePersistence", "dryRun",
                        "aggregateSuccess", "topLevelOk", "returns", "provenance")) {
                    assertTrue(profile.has(key), () -> name + "#" + profile.path("id").asText()
                            + " is missing " + key);
                }
            }
        }
        assertTrue(profiles > 81,
                "argument-dependent commands must make the profile denominator larger than the 81 live leaves");
    }

    @Test
    void theDerivedOperationalDenominatorHasAll48WritesAnd39Previews() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        int writes = 0;
        int previews = 0;
        for (JsonNode verb : manifest.path("verbs")) {
            for (JsonNode profile : verb.path("operationalProfiles")) {
                if (Set.of("MODEL_WRITE", "ARTIFACT_WRITE", "MULTI_FILE_WRITE", "TRANSACTION", "DISPATCH")
                        .contains(profile.path("effect").asText())) {
                    writes++;
                }
                if ("ACTIVE".equals(profile.path("dryRun").asText())) {
                    previews++;
                }
            }
        }
        assertEquals(48, writes,
                "36 shared editors + 3 custom editors + 8 artifact writers + batch dispatch");
        assertEquals(39, previews, "every live --dry-run editing leaf has one active-preview profile");
    }

    @Test
    void highRiskArgumentDependentProfilesStateTheirDistinctSemantics() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());

        assertProfile(manifest, "apply", "commit", "TRANSACTION", "ROLLBACK", "TRANSACTION_COMMITTED");
        assertProfile(manifest, "batch", "dispatch", "DISPATCH", "PARTIAL", "ALL_CHILD_EXITS_ZERO");
        assertProfile(manifest, "group extract", "commit", "MULTI_FILE_WRITE", "PARTIAL", "SELF");
        assertProfile(manifest, "export", "directory", "MULTI_FILE_WRITE", "PARTIAL", "SELF");
        assertProfile(manifest, "model import-jsonschema", "bundle", "MULTI_FILE_WRITE", "PARTIAL", "SELF");

        assertProfile(manifest, "meta", "read", "READ_ONLY", "NO_WRITE", "SELF");
        assertProfile(manifest, "meta", "commit", "MODEL_WRITE", "NO_WRITE", "SELF");
        assertProfile(manifest, "patterns", "scaffold", "READ_ONLY", "NO_WRITE", "SELF");
        assertProfile(manifest, "patterns", "commit", "MODEL_WRITE", "NO_WRITE", "SELF");
        assertProfile(manifest, "schema", "orientation", "READ_ONLY", "NO_WRITE", "SELF");
        assertProfile(manifest, "workspace graph", "tree", "READ_ONLY", "NO_WRITE", "SELF");
    }

    @Test
    void schemaAndGeneratedHelpProjectTheSameOwner() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        JsonNode batchManifest = verb(manifest, "batch").path("operationalProfiles");
        JsonNode batchSchema = MAPPER.readTree(CliTestSupport.run("schema", "batch").out())
                .path("operationalProfiles");
        assertEquals(batchManifest, batchSchema, "manifest and schema must render the same command-contract owner");

        CliTestSupport.Result help = CliTestSupport.run("batch", "--help");
        assertEquals(0, help.code(), help::err);
        assertTrue(help.out().contains("Operational contract:"), help::out);
        assertTrue(help.out().contains("PARTIAL"), help::out);
        assertTrue(help.out().contains("ALL_CHILD_EXITS_ZERO"), help::out);
    }

    @Test
    void selectorsAreNotVacuousForTheKnownMultiModeCommands() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        for (String name : Set.of("meta", "patterns", "apply", "group extract", "model new", "model expand",
                "model import-jsonschema", "model export-jsonschema", "profile extract", "profile synthesize",
                "export", "workspace list", "workspace graph", "model report", "schema")) {
            JsonNode profiles = verb(manifest, name).path("operationalProfiles");
            assertTrue(profiles.size() > 1, () -> name + " needs explicit argument-dependent profiles");
            boolean hasPredicate = false;
            for (JsonNode profile : profiles) {
                JsonNode when = profile.path("when");
                hasPredicate |= !when.path("present").isEmpty()
                        || !when.path("absent").isEmpty()
                        || !when.path("anyPresent").isEmpty()
                        || !when.path("values").isEmpty();
            }
            assertTrue(hasPredicate, () -> name + " profiles do not discriminate any invocation");
        }
    }

    private static void assertProfile(
            JsonNode manifest,
            String verb,
            String id,
            String effect,
            String persistence,
            String aggregate) {
        JsonNode profile = profile(manifest, verb, id);
        assertEquals(effect, profile.path("effect").asText(), () -> verb + "#" + id);
        assertEquals(persistence, profile.path("failurePersistence").asText(), () -> verb + "#" + id);
        assertEquals(aggregate, profile.path("aggregateSuccess").asText(), () -> verb + "#" + id);
    }

    private static JsonNode profile(JsonNode manifest, String name, String id) {
        for (JsonNode profile : verb(manifest, name).path("operationalProfiles")) {
            if (id.equals(profile.path("id").asText())) {
                return profile;
            }
        }
        throw new AssertionError(name + " has no profile " + id);
    }

    private static JsonNode verb(JsonNode manifest, String name) {
        for (JsonNode verb : manifest.path("verbs")) {
            if (name.equals(verb.path("verb").asText())) {
                return verb;
            }
        }
        throw new AssertionError("manifest has no verb " + name);
    }
}
