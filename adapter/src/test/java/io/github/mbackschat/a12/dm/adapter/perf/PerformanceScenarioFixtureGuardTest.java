package io.github.mbackschat.a12.dm.adapter.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.interpreter.gen.PlacementSink;

/**
 * Freshness guard for the shared JVM/Node/native performance fixtures. The models come directly from
 * {@link PerfCatalog}, whose rules are authored through modelkit/EditSession/rulekit.
 */
class PerformanceScenarioFixtureGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE_DIR_PROPERTY = "performance.fixture.dir";

    @Test
    void committedFixturesMatchTheTypedPerformanceCatalog() throws IOException {
        var b1 = PerfCatalog.INSTANCE.b1();
        var b2 = PerfCatalog.INSTANCE.b2();
        var rules48 = PerfCatalog.INSTANCE.manyRules(48, false);
        var rules192 = PerfCatalog.INSTANCE.manyRules(192, false);
        var expected = new LinkedHashMap<String, String>();
        expected.put("b1.dm.json", b1.getKernelModel());
        expected.put("b2.dm.json", b2.getKernelModel());
        expected.put("many-rules-48.dm.json", rules48.getKernelModel());
        expected.put("many-rules-192.dm.json", rules192.getKernelModel());
        expected.put("many-rules-48.instance.json", instanceJson(rules48, 100));
        var directory = Path.of(System.getProperty(FIXTURE_DIR_PROPERTY));

        if (Boolean.getBoolean("performance.fixtures.fix")) {
            Files.createDirectories(directory);
            for (var fixture : expected.entrySet()) {
                Files.writeString(directory.resolve(fixture.getKey()), fixture.getValue());
            }
            return;
        }

        for (var fixture : expected.entrySet()) {
            var path = directory.resolve(fixture.getKey());
            assertEquals(
                    JSON.readTree(fixture.getValue()),
                    JSON.readTree(Files.readString(path)),
                    () -> path + " drifted from its typed performance-catalog author");
        }
    }

    private static String instanceJson(PerfScenario scenario, int scale) throws IOException {
        var sink = new InstanceJsonSink(scenario.getInterpModel().getRepeatableGroups());
        scenario.getInstance().invoke(sink, scale, 0);
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sink.result()) + System.lineSeparator();
    }

    private static final class InstanceJsonSink implements PlacementSink {
        private final Set<String> repeatableGroups;
        private final ObjectNode fields = JSON.createObjectNode();

        private InstanceJsonSink(Set<String> repeatableGroups) {
            this.repeatableGroups = Set.copyOf(repeatableGroups);
        }

        private ObjectNode result() {
            return JSON.createObjectNode().set("fields", fields);
        }

        @Override
        public void group(String path, int... reps) {
            var segments = segments(path);
            descend(segments, reps, segments.length);
        }

        @Override
        public void field(String path, String value, int... reps) {
            var segments = segments(path);
            descend(segments, reps, segments.length - 1).put(segments[segments.length - 1], value);
        }

        @Override
        public void emptyField(String path, int... reps) {
            // An absent key is the document-instance representation of an unfilled field.
        }

        private ObjectNode descend(String[] segments, int[] reps, int count) {
            var current = fields;
            var groupPath = new StringBuilder();
            for (int index = 0; index < count; index++) {
                groupPath.append('/').append(segments[index]);
                current = repeatableGroups.contains(groupPath.toString())
                        ? row(arrayChild(current, segments[index]), reps[index])
                        : objectChild(current, segments[index]);
            }
            return current;
        }

        private static ArrayNode arrayChild(ObjectNode parent, String name) {
            if (parent.get(name) instanceof ArrayNode array) {
                return array;
            }
            var array = JSON.createArrayNode();
            parent.set(name, array);
            return array;
        }

        private static ObjectNode objectChild(ObjectNode parent, String name) {
            if (parent.get(name) instanceof ObjectNode object) {
                return object;
            }
            var object = JSON.createObjectNode();
            parent.set(name, object);
            return object;
        }

        private static ObjectNode row(ArrayNode array, int repetition) {
            var index = repetition - 1;
            while (array.size() <= index) {
                array.addObject();
            }
            return (ObjectNode) array.get(index);
        }

        private static String[] segments(String path) {
            return path.substring(1).split("/");
        }
    }
}
