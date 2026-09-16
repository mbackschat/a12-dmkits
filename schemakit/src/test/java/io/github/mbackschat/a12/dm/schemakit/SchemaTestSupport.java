package io.github.mbackschat.a12.dm.schemakit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.mbackschat.a12.dm.gate.PortableConsistencyGate;
import io.github.mbackschat.a12.dm.gate.PortableGateRun;

import static org.junit.jupiter.api.Assertions.fail;

/** Shared schemakit test helpers: the two-oracle accept gate, and an order-insensitive schema compare. */
final class SchemaTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaTestSupport() {}

    /**
     * Assert A12 accepts a DM-JSON model — <b>and that the Kernel-free checker does not refuse what it
     * accepts</b>.
     *
     * <p>The first half is the transcoder's own gate, through the dm.core seam ({@link ModelLoader}) so the
     * structural shapes need no adapter type in schemakit's main source. The second is F3-b: the same models,
     * through {@code dmkits-consistency-gate} with no kernel involved. It rides this one seam on purpose —
     * every shape test in this module already calls it, so the portable arm gets the whole import corpus
     * rather than a handful of hand-picked fixtures, at no authoring cost per test.
     *
     * <p><b>Only over-rejection fails here, and that asymmetry is the contract.</b> The kernel has just
     * accepted the model, so a portable ERROR is the checker refusing what A12 admits — the one direction no
     * consumer can detect, because the kernel is never asked. The converse says nothing: this checker
     * completes only the families it implements and reports no VALID verdict at all, so its silence is
     * expected and is never evidence.
     */
    static void assertKernelAccepts(String dmJson) {
        try {
            ModelLoader.loadSelfContained(dmJson);
        } catch (ModelConsistencyException e) {
            fail("kernel rejected the imported model: " + e.getMessage());
        }
        assertPortableCheckerDoesNotRefuse(dmJson);
    }

    /**
     * The portable half of the gate above, named so it has a control of its own.
     *
     * <p>Without one the arm is unfalsifiable: it fires only on a disagreement between A12 and the checker,
     * and there is none today, so an implementation that quietly stopped checking would pass every test in
     * this module. {@code PortableAcceptanceGateTest} calls this directly on a model the checker DOES refuse.
     */
    static void assertPortableCheckerDoesNotRefuse(String dmJson) {
        PortableGateRun portable = PortableConsistencyGate.check(dmJson);
        if (!portable.validation().ok()) {
            fail("the kernel ACCEPTS this imported model and the portable checker refuses it — an "
                    + "over-rejection: " + portable.validation().diagnostics());
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
