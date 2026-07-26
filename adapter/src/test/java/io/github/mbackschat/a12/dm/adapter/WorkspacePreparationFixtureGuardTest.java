package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.adapter.preparation.WorkspacePreparationFixtures;

/**
 * Freshness guard for the committed raw-workspace matrix. Every DM-JSON source is regenerated through the typed
 * authoring surface; the manifest is derived from the same fixture cases, so common JVM/Node tests consume the
 * exact source closure the Kernel/JVM-reader parity test observed.
 */
class WorkspacePreparationFixtureGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE_DIR_PROPERTY = "workspace.preparation.fixture.dir";

    @Test
    void committedWorkspaceMatchesTheTypedAuthoringSurface() throws IOException {
        var directory = Path.of(System.getProperty(FIXTURE_DIR_PROPERTY));
        var expected = new LinkedHashMap<>(WorkspacePreparationFixtures.committedFiles());
        expected.put("manifest.json", manifest());

        if (Boolean.getBoolean("workspace.preparation.fixtures.fix")) {
            for (var fixture : expected.entrySet()) {
                var path = directory.resolve(fixture.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, fixture.getValue());
            }
            return;
        }

        var actualPaths = new TreeSet<String>();
        if (Files.isDirectory(directory)) {
            try (var files = Files.walk(directory)) {
                files.filter(Files::isRegularFile)
                        .map(directory::relativize)
                        .map(Path::toString)
                        .forEach(actualPaths::add);
            }
        }
        assertEquals(new TreeSet<>(expected.keySet()), actualPaths,
                "workspace fixture files drifted from the typed catalog");
        for (var fixture : expected.entrySet()) {
            var path = directory.resolve(fixture.getKey());
            assertEquals(JSON.readTree(fixture.getValue()), JSON.readTree(Files.readString(path)),
                    () -> path + " drifted from its typed author");
        }
    }

    private static String manifest() throws IOException {
        var root = JSON.createObjectNode();
        root.put("version", 4);
        var cases = root.putObject("cases");
        var committed = WorkspacePreparationFixtures.committedFiles();
        for (var fixture : WorkspacePreparationFixtures.cases()) {
            var entry = cases.putObject(fixture.id());
            entry.put("entryModelId", fixture.entryModelId());
            ObjectNode sources = entry.putObject("sources");
            for (var modelId : fixture.sources().keySet()) {
                sources.put(modelId, pathOf(committed, modelId));
            }
            var observation = PreparationParityHarness.observe(fixture.entryJson(), fixture.resolver());
            PreparationParityHarness.assertEquivalent(observation);
            var expanded = expandedModel(fixture);
            ObjectNode effectiveFieldTypes = entry.putObject("effectiveFieldTypes");
            var observed = effectiveFieldTypes(expanded);
            assertEquals(new TreeSet<>(fixture.expectedFields()), new TreeSet<>(observed.keySet()),
                    () -> fixture.id() + " effective field-type observation coverage");
            observed.forEach(effectiveFieldTypes::set);
            ObjectNode preparedStructure = entry.putObject("preparedStructure");
            var elements = preparedStructure(observation.reader());
            fixture.expectedFields().forEach(path ->
                    assertEquals("Field", elements.get(path).path("elementType").asText(), path));
            fixture.expectedRules().forEach(path ->
                    assertEquals("Rule", elements.get(path).path("elementType").asText(), path));
            fixture.expectedComputations().keySet()
                    .forEach(path ->
                            assertEquals("Computation", elements.get(path).path("elementType").asText(), path));
            elements.forEach(preparedStructure::set);
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
    }

    private static JsonNode expandedModel(WorkspacePreparationFixtures.FixtureCase fixture)
            throws IOException {
        return JSON.readTree(ModelReader.expand(fixture.entryJson(), fixture.resolver()).toJson());
    }

    private static Map<String, JsonNode> effectiveFieldTypes(JsonNode expanded) {
        var fields = new java.util.TreeMap<String, JsonNode>();
        collectEffectiveFieldTypes(expanded, fields);
        return fields;
    }

    private static void collectEffectiveFieldTypes(JsonNode node, Map<String, JsonNode> fields) {
        if (node.isObject()) {
            if ("Field".equals(node.path("type").asText()) && node.path("id").isTextual()) {
                fields.put(node.path("id").asText(), node.path("Field").path("fieldType"));
            }
            node.elements().forEachRemaining(child -> collectEffectiveFieldTypes(child, fields));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectEffectiveFieldTypes(child, fields));
        }
    }

    private static Map<String, JsonNode> preparedStructure(
            PreparationParityHarness.PreparedSurface surface) {
        var elements = new java.util.TreeMap<String, JsonNode>();
        surface.elements().forEach((path, facts) -> {
            var structure = JSON.createObjectNode();
            structure.put("elementType", facts.group() ? "Group" : "Field");
            structure.set("facts", JSON.valueToTree(facts));
            elements.put(path, structure);
        });
        surface.rules().forEach(path -> elements.put(path, executableStructure("Rule")));
        surface.computations().keySet()
                .forEach(path -> elements.put(path, executableStructure("Computation")));
        return elements;
    }

    private static ObjectNode executableStructure(String elementType) {
        var structure = JSON.createObjectNode();
        structure.put("elementType", elementType);
        return structure;
    }

    private static String pathOf(java.util.Map<String, String> committed, String modelId) {
        var suffix = "/" + modelId + ".dm.json";
        return committed.keySet().stream()
                .filter(path -> path.endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no committed path for model " + modelId));
    }
}
