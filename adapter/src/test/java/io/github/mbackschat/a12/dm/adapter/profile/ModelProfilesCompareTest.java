package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonFixture.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ModelProfilesCompareTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void comparesBareAndEnvelopeInputsThroughAStringOnlyPublicBoundary() throws Exception {
        String targetProfileJson = ModelProfileJson.write(profile(false));
        String actualProfileJson = envelope(profile(true)).toString();

        // tag::profile-compare[]
        String comparisonJson = ModelProfiles.compare(targetProfileJson, actualProfileJson);
        // end::profile-compare[]

        JsonNode comparison = JSON.readTree(comparisonJson);
        assertEquals(Set.of("matches", "axes"), propertyNames(comparison));
        assertTrue(comparison.path("matches").booleanValue());
        assertFalse(comparison.has("profileVersion"));
        assertEquals(
                Set.of("path", "policy", "target", "actual", "verdict", "rule"),
                propertyNames(comparison.path("axes").get(0)));
        assertEquals("structure.elements", comparison.path("axes").get(0).path("path").textValue());
        assertEquals(
                comparisonJson,
                ModelProfiles.compare(
                        ModelProfileJson.write(profile(true)),
                        ModelProfileJson.write(profile(false))));
    }

    @Test
    void reportsEveryFailedAxisWithTypedTargetAndActualValues() throws Exception {
        String target = ModelProfileJson.write(profile(false));
        ObjectNode actual = (ObjectNode) JSON.readTree(target);
        ((ObjectNode) actual.path("structure").path("topology")).put("maxGroupDepth", 3);

        JsonNode comparison = JSON.readTree(ModelProfiles.compare(target, actual.toString()));
        JsonNode failed = findAxis(comparison, "structure.topology.maxGroupDepth");

        assertFalse(comparison.path("matches").booleanValue());
        assertEquals("EXACT", failed.path("policy").textValue());
        assertEquals(2, failed.path("target").intValue());
        assertEquals(3, failed.path("actual").intValue());
        assertEquals("FAIL", failed.path("verdict").textValue());
        assertEquals("actual must equal target", failed.path("rule").textValue());
    }

    private static ObjectNode envelope(ModelProfile profile) throws Exception {
        var envelope = JSON.createObjectNode();
        envelope.put("target", "profile");
        envelope.put("op", "extract");
        envelope.put("outcome", "read");
        envelope.put("ok", true);
        envelope.put("valid", true);
        envelope.put("summary", "profile extracted");
        envelope.set("data", JSON.readTree(ModelProfileJson.write(profile)));
        envelope.putArray("diagnostics");
        envelope.put("written", false);
        return envelope;
    }

    private static JsonNode findAxis(JsonNode comparison, String path) {
        for (JsonNode axis : comparison.path("axes")) {
            if (path.equals(axis.path("path").textValue())) {
                return axis;
            }
        }
        throw new AssertionError("missing comparison axis " + path);
    }

    private static Set<String> propertyNames(JsonNode node) {
        var names = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
