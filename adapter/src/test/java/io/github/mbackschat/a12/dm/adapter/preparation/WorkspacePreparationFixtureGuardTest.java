package io.github.mbackschat.a12.dm.adapter.preparation;

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
        root.put("version", 2);
        var cases = root.putObject("cases");
        var committed = WorkspacePreparationFixtures.committedFiles();
        for (var fixture : WorkspacePreparationFixtures.cases()) {
            var entry = cases.putObject(fixture.id());
            entry.put("entryModelId", fixture.entryModelId());
            ObjectNode sources = entry.putObject("sources");
            for (var modelId : fixture.sources().keySet()) {
                sources.put(modelId, pathOf(committed, modelId));
            }
            ObjectNode effectiveFieldTypes = entry.putObject("effectiveFieldTypes");
            var observed = effectiveFieldTypes(fixture);
            assertEquals(new TreeSet<>(fixture.expectedFields()), new TreeSet<>(observed.keySet()),
                    () -> fixture.id() + " effective field-type observation coverage");
            observed.forEach(effectiveFieldTypes::set);
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
    }

    private static Map<String, JsonNode> effectiveFieldTypes(WorkspacePreparationFixtures.FixtureCase fixture)
            throws IOException {
        var expanded = JSON.readTree(ModelReader.expand(fixture.entryJson(), fixture.resolver()).toJson());
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

    private static String pathOf(java.util.Map<String, String> committed, String modelId) {
        var suffix = "/" + modelId + ".dm.json";
        return committed.keySet().stream()
                .filter(path -> path.endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no committed path for model " + modelId));
    }
}
