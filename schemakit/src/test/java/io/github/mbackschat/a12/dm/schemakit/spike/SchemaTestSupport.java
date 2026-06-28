package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/** Shared spike-test helpers: the public-facade kernel-accept gate, and an order-insensitive schema compare. */
final class SchemaTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaTestSupport() {}

    /** Assert the kernel accepts a DM-JSON model — through the PUBLIC facade only ({@link ModelLoader}; no :adapter). */
    static void assertKernelAccepts(String dmJson) {
        try {
            ModelLoader.loadSelfContained(new StringReader(dmJson));
        } catch (ModelConsistencyException e) {
            fail("kernel rejected the imported model: " + e.getMessage());
        } catch (IOException e) {
            fail("could not load the imported model: " + e.getMessage());
        }
    }

    /** Parse a schema and sort every {@code required} array (at every nesting level) for an order-insensitive compare. */
    static JsonNode normalize(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            sortRequired(node);
            return node;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sortRequired(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<JsonNode> children = new ArrayList<>();
            obj.fields().forEachRemaining(e -> children.add(e.getValue()));
            children.forEach(SchemaTestSupport::sortRequired);
            JsonNode req = obj.get("required");
            if (req != null && req.isArray()) {
                List<String> names = new ArrayList<>();
                req.forEach(n -> names.add(n.asText()));
                Collections.sort(names);
                ArrayNode sorted = MAPPER.createArrayNode();
                names.forEach(sorted::add);
                obj.set("required", sorted);
            }
        } else if (node.isArray()) {
            node.forEach(SchemaTestSupport::sortRequired);
        }
    }
}
