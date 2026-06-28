package io.github.mbackschat.a12.dm.schemakit.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Spike shape 1 (SCHEMAKIT-SPEC §6): a flat top-level object round-trips, and every imported model is
 * kernel-accepted — through the PUBLIC facade only ({@link ModelLoader}; no {@code :adapter}), which is
 * the dependency boundary this whole subproject is built to honor (SCHEMAKIT-SPEC §1).
 */
class JsonSchemaSpikeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FLAT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "customerName": { "type": "string" },
                "quantity":     { "type": "integer" },
                "express":      { "type": "boolean" }
              },
              "required": ["customerName", "quantity"]
            }
            """;

    @Test
    void importsFlatFieldsWithRequiredness() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(FLAT_SCHEMA);

        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.of("STRING"), doc.fieldKind("/Root/customerName"));
        assertEquals(Optional.of("NUMBER"), doc.fieldKind("/Root/quantity"));
        assertEquals(Optional.of("BOOLEAN"), doc.fieldKind("/Root/express"));

        assertEquals(Requiredness.REQUIRED, doc.requiredness("/Root/customerName").orElse(Requiredness.OPTIONAL));
        assertEquals(Requiredness.REQUIRED, doc.requiredness("/Root/quantity").orElse(Requiredness.OPTIONAL));
        assertEquals(Requiredness.OPTIONAL, doc.requiredness("/Root/express").orElse(Requiredness.OPTIONAL));

        // the report states every disposition — its accuracy IS the spike's evidence (SCHEMAKIT-SPEC §5)
        assertEquals(3, imported.report().dispositions().size());
        assertEquals(0, imported.report().diagnostics().size());
    }

    /**
     * JSON {@code number} has no faithful a12 target (an a12 number is always scale-bounded, default 0 =
     * integer). The transcoder must REPORT that, never silently turn a decimal into an integer — the
     * never-silent-degrade discipline (SCHEMAKIT-SPEC §4).
     */
    @Test
    void unboundedNumberIsReportedNotSilentlyMismapped() {
        String withNumber = """
                {
                  "type": "object",
                  "properties": { "unitPrice": { "type": "number" } }
                }
                """;
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(withNumber);

        // the field is NOT silently created as an integer …
        ModelDocument doc = ModelDocument.read(imported.dmJson());
        assertEquals(Optional.empty(), doc.fieldKind("/Root/unitPrice"));
        // … it is reported, and recorded as a refusal in the report
        assertEquals(1, imported.report().diagnostics().size());
        assertEquals(TranscodeReport.Tier.REFUSED, imported.report().dispositions().get(0).tier());
    }

    @Test
    void importedModelIsKernelAccepted() {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(FLAT_SCHEMA);
        assertKernelAccepts(imported.dmJson());
    }

    @Test
    void flatObjectRoundTrips() throws Exception {
        JsonSchemaImporter.ImportResult imported = new JsonSchemaImporter().importSchema(FLAT_SCHEMA);
        JsonSchemaExporter.ExportResult exported = new JsonSchemaExporter().export(imported.dmJson());

        assertEquals(normalize(FLAT_SCHEMA), normalize(exported.jsonSchema()),
                "import → export must reproduce {type, properties, required}");
    }

    private static void assertKernelAccepts(String dmJson) {
        try {
            ModelLoader.loadSelfContained(new StringReader(dmJson));   // public facade — the validity oracle
        } catch (ModelConsistencyException e) {
            fail("kernel rejected the imported model: " + e.getMessage());
        } catch (IOException e) {
            fail("could not load the imported model: " + e.getMessage());
        }
    }

    /** Parse and sort {@code required} so the round-trip comparison is order-insensitive. */
    private static JsonNode normalize(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        if (node.path("required").isArray()) {
            List<String> names = new ArrayList<>();
            node.get("required").forEach(n -> names.add(n.asText()));
            Collections.sort(names);
            ArrayNode sorted = MAPPER.createArrayNode();
            names.forEach(sorted::add);
            ((ObjectNode) node).set("required", sorted);
        }
        return node;
    }
}
