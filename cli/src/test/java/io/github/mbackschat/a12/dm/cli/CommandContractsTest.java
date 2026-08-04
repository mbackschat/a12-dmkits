package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
                assertEquals("CASE_INSENSITIVE", profile.path("when").path("valueComparison").asText(),
                        () -> name + " must publish how semantic values are compared");
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
        assertEquals(49, writes,
                "36 shared editors + 3 custom editors + 8 artifact writers + two batch dispatch modes");
        assertEquals(39, previews, "every live --dry-run editing leaf has one active-preview profile");
    }

    @Test
    void highRiskArgumentDependentProfilesStateTheirDistinctSemantics() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());

        assertProfile(manifest, "apply", "commit", "TRANSACTION", "ROLLBACK", "TRANSACTION_COMMITTED");
        assertProfile(manifest, "batch", "dispatch", "DISPATCH", "CONTINUE_AFTER_CHILD_FAILURE",
                "ALL_CHILD_EXITS_ZERO");
        assertProfile(manifest, "batch", "dispatch-with-observations", "ARTIFACT_WRITE",
                "CONTINUE_AFTER_CHILD_FAILURE",
                "ALL_CHILD_EXITS_ZERO_AND_ARTIFACT_WRITTEN");
        JsonNode observations = profile(manifest, "batch", "dispatch-with-observations");
        assertEquals("ALL_CHILD_EXITS_ZERO_AND_ARTIFACT_WRITTEN",
                observations.path("topLevelOk").asText());
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

    /**
     * {@code NO_WRITE} rides 43 profiles whose effect is a write, so the scope of the persistence claim has to
     * be published wherever the value is — not left to a doc the value does not point at. The discriminator is
     * two-directional on purpose: presence alone would pass on a vocabulary bolted onto every response.
     */
    @Test
    void thePersistenceClaimPublishesItsScopeOnEveryVerbThatCanWrite() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        JsonNode vocabulary = manifest.path("contractVocabulary");
        JsonNode persistence = vocabulary.path("failurePersistence");
        String scope = persistence.path("scope").asText();
        assertTrue(scope.contains("I/O failure") && scope.contains("abnormal process termination"),
                () -> "the scope must name both excluded failure classes: " + scope);
        assertTrue(scope.contains("never atomically replaced"),
                () -> "the scope must say why neither is guaranteed: " + scope);

        Set<String> declaredValues = new HashSet<>();
        persistence.path("values").fieldNames().forEachRemaining(declaredValues::add);
        assertEquals(Set.of("NO_WRITE", "ROLLBACK", "PARTIAL", "CONTINUE_AFTER_CHILD_FAILURE"), declaredValues);
        persistence.path("values").fields().forEachRemaining(entry ->
                assertFalse(entry.getValue().asText().isBlank(), () -> entry.getKey() + " has no published meaning"));

        int writing = 0;
        int readOnly = 0;
        for (JsonNode verb : manifest.path("verbs")) {
            String name = verb.path("verb").asText();
            boolean writes = false;
            for (JsonNode profile : verb.path("operationalProfiles")) {
                assertTrue(declaredValues.contains(profile.path("failurePersistence").asText()),
                        () -> name + " publishes a persistence value the vocabulary does not define");
                writes |= !"READ_ONLY".equals(profile.path("effect").asText());
            }
            CliTestSupport.Result result = CliTestSupport.run(
                    Stream.concat(Stream.of("schema"), Arrays.stream(name.split(" "))).toArray(String[]::new));
            assertEquals(0, result.code(), () -> name + ": " + result.err());
            JsonNode contract = MAPPER.readTree(result.out());
            if (writes) {
                writing++;
                assertEquals(vocabulary, contract.path("contractVocabulary"),
                        () -> name + " can write but does not publish the persistence scope");
            } else {
                readOnly++;
                assertTrue(contract.path("contractVocabulary").isMissingNode(),
                        () -> name + " writes nothing, so a persistence scope there is noise");
            }
        }
        assertEquals(47, writing, "every leaf with at least one writing profile carries the scope");
        assertEquals(34, readOnly, "the always-read-only leaves stay free of it");
    }

    @Test
    void theScopeReachesTheGeneratedHelpOfAWritingLeafOnly() {
        CliTestSupport.Result writing = CliTestSupport.run("field", "add", "--help");
        assertEquals(0, writing.code(), writing::err);
        assertTrue(writing.out().contains("persistence scope:"), writing::out);
        assertTrue(writing.out().contains("never atomically replaced"), writing::out);

        CliTestSupport.Result read = CliTestSupport.run("field", "read", "--help");
        assertEquals(0, read.code(), read::err);
        assertTrue(read.out().contains("Operational contract:"), read::out);
        assertFalse(read.out().contains("persistence scope:"), read::out);
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
        assertTrue(help.out().contains("CONTINUE_AFTER_CHILD_FAILURE"), help::out);
        assertTrue(help.out().contains("ALL_CHILD_EXITS_ZERO"), help::out);
    }

    @Test
    void parsedSemanticSelectorsAreTotalAndDisjointForEveryMultiModeProfile() {
        Map<String, List<String>> cases = Map.ofEntries(
                Map.entry("apply#commit", List.of("apply", "ops.json")),
                Map.entry("apply#preview", List.of("apply", "ops.json", "--dry-run")),
                Map.entry("batch#dispatch", List.of("batch", "ops.json")),
                Map.entry("batch#dispatch-with-observations",
                        List.of("batch", "ops.json", "--observations", "observations.json")),
                Map.entry("group extract#commit", List.of("group", "extract", "/R/G", "--reference", "g")),
                Map.entry("group extract#preview", List.of("group", "extract", "/R/G", "--reference", "g", "--dry-run")),
                Map.entry("meta#read", List.of("meta", "/R/F")),
                Map.entry("meta#commit", List.of("meta", "/R/F", "--annotation", "owner=qa")),
                Map.entry("meta#preview", List.of("meta", "/R/F", "--annotation", "owner=qa", "--dry-run")),
                Map.entry("patterns#catalog", List.of("patterns")),
                Map.entry("patterns#detail", List.of("patterns", "date-order")),
                Map.entry("patterns#scaffold", List.of("patterns", "date-order", "--arg", "earlier=/R/A")),
                Map.entry("patterns#commit", List.of("patterns", "date-order", "--arg", "earlier=/R/A", "--apply")),
                Map.entry("patterns#preview", List.of("patterns", "date-order", "--arg", "earlier=/R/A", "--apply", "--dry-run")),
                Map.entry("model new#stdout", List.of("model", "new", "--id", "R", "--locale", "en_US", "--root", "R")),
                Map.entry("model new#file", List.of("model", "new", "--id", "R", "--locale", "en_US", "--root", "R", "-o", "r.json")),
                Map.entry("model expand#stdout", List.of("model", "expand")),
                Map.entry("model expand#file", List.of("model", "expand", "-o", "r.json")),
                Map.entry("model export-jsonschema#stdout", List.of("model", "export-jsonschema")),
                Map.entry("model export-jsonschema#file", List.of("model", "export-jsonschema", "-o", "r.json")),
                Map.entry("profile synthesize#stdout", List.of("profile", "synthesize", "--input", "p.json")),
                Map.entry("profile synthesize#file", List.of("profile", "synthesize", "--input", "p.json", "-o", "r.json")),
                Map.entry("profile extract#envelope", List.of("profile", "extract")),
                Map.entry("profile extract#file", List.of("profile", "extract", "-o", "p.json")),
                Map.entry("model import-jsonschema#stdout", List.of("model", "import-jsonschema", "--schema", "s.json")),
                Map.entry("model import-jsonschema#file", List.of("model", "import-jsonschema", "--schema", "s.json", "-o", "m.json")),
                Map.entry("model import-jsonschema#bundle", List.of("model", "import-jsonschema", "--schema", "s.json", "--out-dir", "models")),
                Map.entry("export#stdout", List.of("export")),
                Map.entry("export#directory", List.of("export", "--out-dir", "artifacts")),
                Map.entry("workspace list#json-default", List.of("workspace", "list", "models")),
                Map.entry("workspace list#json", List.of("workspace", "list", "models", "--format", "JSON")),
                Map.entry("workspace list#json-validated", List.of("workspace", "list", "models", "--validate")),
                Map.entry("workspace list#json-validated-explicit", List.of("workspace", "list", "models", "--validate", "--format", "JSON")),
                Map.entry("workspace list#table", List.of("workspace", "list", "models", "--format", "TABLE")),
                Map.entry("workspace list#table-validated", List.of("workspace", "list", "models", "--validate", "--format", "TABLE")),
                Map.entry("workspace graph#json-default", List.of("workspace", "graph", "models")),
                Map.entry("workspace graph#json", List.of("workspace", "graph", "models", "--format", "JSON")),
                Map.entry("workspace graph#tree", List.of("workspace", "graph", "models", "--format", "TREE")),
                Map.entry("workspace graph#dot", List.of("workspace", "graph", "models", "--format", "DOT")),
                Map.entry("model report#report", List.of("model", "report")),
                Map.entry("model report#tree", List.of("model", "report", "--tree")),
                Map.entry("schema#orientation", List.of("schema")),
                Map.entry("schema#contract", List.of("schema", "field", "read")));

        Set<String> exercised = new HashSet<>();
        cases.forEach((expected, args) -> {
            List<CommandContracts.Profile> matches = CommandContracts.matchingProfiles(args);
            assertEquals(1, matches.size(), () -> expected + " matched " + matches + " for " + args);
            String actual = CommandContracts.qualifiedVerb(args) + "#" + matches.getFirst().id();
            assertEquals(expected, actual, () -> "wrong selector branch for " + args);
            exercised.add(actual);
        });

        assertEquals(cases.keySet(), exercised, "every named multi-mode profile has a positive control");
        assertMatch("apply#commit", "apply", "ops.json", "--dry-run=false");
        assertMatch("patterns#commit", "patterns", "date-order", "--arg", "earlier=/R/A", "--apply",
                "--dry-run=false");
        assertTrue(CommandContracts.matchingProfiles(List.of("patterns", "--arg", "earlier=/R/A")).isEmpty(),
                "an invocation the command rejects must not be assigned the catalog profile");
        assertTrue(CommandContracts.matchingProfiles(List.of("meta", "/R/F", "--dry-run")).isEmpty(),
                "a mutation-only option rejected in read mode must not match the read profile");
        assertTrue(CommandContracts.matchingProfiles(List.of("meta", "/R/F", "-o", "out.json")).isEmpty(),
                "a mutation-only destination rejected in read mode must not match the read profile");
        assertTrue(CommandContracts.matchingProfiles(List.of("patterns", "date-order", "--arg", "earlier=/R/A",
                "--dry-run=false")).isEmpty(),
                "an explicitly supplied dry-run flag rejected without --apply must not match scaffold");
        assertTrue(CommandContracts.matchingProfiles(List.of("model", "report", "--root", "/R")).isEmpty(),
                "tree-only projection options rejected in report mode must not match report");
        assertTrue(CommandContracts.matchingProfiles(List.of("model", "import-jsonschema", "--schema", "s.json",
                "--out-dir", "models", "-o", "ignored.json")).isEmpty(),
                "mutually exclusive output destinations must not match the bundle profile");
        assertTrue(CommandContracts.matchingProfiles(List.of("model", "import-jsonschema", "--schema", "s.json",
                "--out-dir", "models", "--component", "R")).isEmpty(),
                "the single-component selector rejected in bundle mode must not match the bundle profile");
    }

    @Test
    void resultEnvelopeProfilesDeclareTheGlobalTextView() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        int envelopeProfiles = 0;
        for (JsonNode verb : manifest.path("verbs")) {
            for (JsonNode profile : verb.path("operationalProfiles")) {
                JsonNode returns = profile.path("returns");
                if ("RESULT_ENVELOPE".equals(returns.path("kind").asText())) {
                    envelopeProfiles++;
                    JsonNode view = returns.path("views").get(0);
                    assertEquals("RAW_TEXT", view.path("kind").asText(), () -> verb.path("verb").asText());
                    assertEquals(List.of("true"), MAPPER.convertValue(
                            view.path("when").path("values").path("--text"), List.class));
                } else {
                    assertTrue(returns.path("views").isEmpty(),
                            () -> "raw/artifact output cannot claim envelope text rendering: " + profile);
                    assertTrue(Set.of("NONE", "NOT_EMITTED")
                                    .contains(profile.path("provenance").path("consistency").asText()),
                            () -> "raw output cannot promise a provenance field it cannot emit: " + profile);
                }
            }
        }
        assertTrue(envelopeProfiles > 65, "the modifier guard must cover the whole envelope surface");
    }

    @Test
    void ordinaryStaticReadsClassifyConsistencyPrecisely() throws Exception {
        JsonNode manifest = MAPPER.readTree(CliTestSupport.run("manifest").out());
        Set<String> required = Set.of(
                "model check", "model describe", "model diff", "model read", "model usage",
                "rule read", "rule explain", "rule deps", "rule format",
                "computation read", "computation explain", "computation format", "where-used");
        Set<String> optional = Set.of("rule check", "workspace check");
        Set<String> none = Set.of(
                "model info", "profile compare", "field read", "group read", "typedef read", "include read",
                "config read", "workspace roles");
        required.forEach(name -> assertEquals("REQUIRED",
                verb(manifest, name).path("operationalProfiles").get(0).path("provenance").path("consistency").asText(), name));
        optional.forEach(name -> assertEquals("OPTIONAL",
                verb(manifest, name).path("operationalProfiles").get(0).path("provenance").path("consistency").asText(), name));
        none.forEach(name -> assertEquals("NONE",
                verb(manifest, name).path("operationalProfiles").get(0).path("provenance").path("consistency").asText(), name));
    }

    private static void assertMatch(String expected, String... args) {
        List<String> command = List.of(args);
        List<CommandContracts.Profile> matches = CommandContracts.matchingProfiles(command);
        assertEquals(1, matches.size(), () -> expected + " matched " + matches);
        assertEquals(expected, CommandContracts.qualifiedVerb(command) + "#" + matches.getFirst().id());
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
