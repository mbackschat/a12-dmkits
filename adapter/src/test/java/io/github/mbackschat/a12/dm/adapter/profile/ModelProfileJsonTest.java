package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonFixture.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ModelProfileJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void roundTripsTheCompleteCurrentProfileShape() throws Exception {
        ModelProfile expected = profile(false);

        String serialized = ModelProfileJson.write(expected);

        assertEquals(expected, ModelProfileJson.read(serialized));
        JsonNode root = JSON.readTree(serialized);
        assertEquals(
                java.util.Set.of("structure", "rules", "computations", "wiring"),
                propertyNames(root));
        assertFalse(serialized.contains("profileVersion"));
        assertFalse(serialized.contains("\"path\""));
        assertFalse(serialized.contains("\"name\""));
        assertTrue(serialized.endsWith("\n"));
    }

    @Test
    void canonicalizesEveryMapIndependentlyOfInsertionOrComparatorOrder() {
        String forward = ModelProfileJson.write(profile(false));
        String reverse = ModelProfileJson.write(profile(true));

        assertEquals(forward, reverse);
        assertTrue(forward.indexOf("\"AGGREGATE\"") < forward.indexOf("\"UNIQUENESS\""));
        assertTrue(
                forward.indexOf("\"MODEL_LEVEL\"")
                        < forward.indexOf("\"PER_REPEATABLE_ROW\""));
    }

    @Test
    void acceptsTheBareArtifactAndItsNormalResultEnvelopeEqually() throws Exception {
        ModelProfile expected = profile(false);
        ObjectNode data = mutableProfile();
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("target", "profile");
        envelope.put("op", "extract");
        envelope.put("outcome", "read");
        envelope.put("ok", true);
        envelope.put("valid", true);
        envelope.put("summary", "profile extracted");
        envelope.set("data", data);
        envelope.putArray("diagnostics");
        envelope.put("written", false);

        assertEquals(expected, ModelProfileJson.read(envelope.toString()));
        assertEquals(
                ModelProfileJson.read(ModelProfileJson.write(expected)),
                ModelProfileJson.read(envelope.toString()));
    }

    @Test
    void rejectsAnUnknownFieldAtItsExactNestedLocation() throws Exception {
        ObjectNode root = mutableProfile();
        ((ObjectNode) root.path("rules").path("runtime").path("evaluation").path("scans"))
                .put("legacyInteractionIndex", 7);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(root.toString()));

        assertTrue(error.getMessage().contains(
                "$.rules.runtime.evaluation.scans.legacyInteractionIndex: unknown field"));
    }

    @Test
    void rejectsNegativeValuesBeforeReturningAProfile() throws Exception {
        ObjectNode root = mutableProfile();
        ((ObjectNode) root.path("structure").path("topology")).put("maxGroupDepth", -1);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(root.toString()));

        assertTrue(error.getMessage().contains("$.structure.topology"));
        assertTrue(error.getMessage().contains("maximum group depth must be non-negative"));
    }

    @Test
    void rejectsInconsistentRelatedTotalsBeforeReturningAProfile() throws Exception {
        ObjectNode root = mutableProfile();
        ((ObjectNode) root.path("rules").path("runtime").path("scope").path("classes"))
                .put("MODEL_LEVEL", 99);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(root.toString()));

        assertTrue(error.getMessage().contains("$.rules.runtime.scope"));
        assertTrue(error.getMessage().contains("scope-class total"));
        assertTrue(error.getMessage().contains("does not equal scope-capacity count"));
    }

    @Test
    void rejectsUnknownResultEnvelopeFieldsInsteadOfTreatingThemAsProfileData() throws Exception {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("target", "profile");
        envelope.put("op", "extract");
        envelope.put("outcome", "read");
        envelope.put("ok", true);
        envelope.put("summary", "profile extracted");
        envelope.set("data", mutableProfile());
        envelope.putArray("diagnostics");
        envelope.put("written", false);
        envelope.put("profileVersion", 2);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(envelope.toString()));

        assertTrue(error.getMessage().contains("$.profileVersion: unknown field"));
    }

    @Test
    void rejectsDuplicateJsonProperties() {
        String serialized = ModelProfileJson.write(profile(false));
        String duplicate = serialized.replace(
                "\"structure\" : {",
                "\"structure\" : {},\n  \"structure\" : {");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ModelProfileJson.read(duplicate));

        assertTrue(error.getMessage().contains("Duplicate field 'structure'"));
    }

    private static ObjectNode mutableProfile() throws Exception {
        return (ObjectNode) JSON.readTree(ModelProfileJson.write(profile(false)));
    }

    private static java.util.Set<String> propertyNames(JsonNode node) {
        var names = new java.util.TreeSet<String>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
