package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

class ModelProfilesCompareTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reportsEveryAxisForEqualBareAndEnvelopeProfiles() throws Exception {
        String profile = ModelProfiles.extract(simpleModel());
        ObjectNode envelope = JSON.createObjectNode();
        envelope.set("data", JSON.readTree(profile));

        JsonNode result = JSON.readTree(ModelProfiles.compare(profile, envelope.toString()));

        assertTrue(result.path("matches").asBoolean());
        assertTrue(result.path("axes").size() > 1);
        assertEquals("computations.aggregateScaleMatch.matched", result.path("axes").get(0).path("path").asText());
    }

    @Test
    void exposesAnExactStructuralMismatch() throws Exception {
        String target = ModelProfiles.extract(simpleModel());
        ObjectNode actual = (ObjectNode) JSON.readTree(target);
        ((ObjectNode) actual.path("structure")).put("maxNestingDepth", 2);

        JsonNode result = JSON.readTree(ModelProfiles.compare(target, actual.toString()));
        JsonNode failure = findAxis(result, "structure.maxNestingDepth");

        assertFalse(result.path("matches").asBoolean());
        assertEquals("EXACT", failure.path("policy").asText());
        assertEquals("FAIL", failure.path("verdict").asText());
    }

    private static JsonNode findAxis(JsonNode comparison, String path) {
        for (JsonNode axis : comparison.path("axes")) {
            if (path.equals(axis.path("path").asText())) {
                return axis;
            }
        }
        throw new AssertionError("missing comparison axis " + path);
    }

    private static String simpleModel() {
        return DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", root -> root.field("Name", FieldKind.STRING))
                .build()
                .toJson();
    }
}
