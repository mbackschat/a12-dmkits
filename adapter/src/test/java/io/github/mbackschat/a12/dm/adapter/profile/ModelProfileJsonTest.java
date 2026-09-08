package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

class ModelProfileJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void roundTripsBareProfilesAndExtractionEnvelopes() throws Exception {
        var profile = SynthesisProfile.of(simpleModel());
        String bare = ModelProfileJson.write(profile);
        ObjectNode envelope = JSON.createObjectNode();
        envelope.set("data", JSON.readTree(bare));

        assertEquals(profile, ModelProfileJson.read(bare));
        assertEquals(profile, ModelProfileJson.read(envelope.toString()));
        assertTrue(bare.endsWith("\n"));
    }

    @Test
    void rejectsNegativeProfileValuesBeforeSynthesis() throws Exception {
        ObjectNode profile = (ObjectNode) JSON.readTree(
                ModelProfileJson.write(SynthesisProfile.of(simpleModel())));
        ((ObjectNode) profile.path("structure")).put("fieldCount", -1);

        var error = assertThrows(
                ProfileInputException.class,
                () -> ModelProfileJson.read(profile.toString()));

        assertTrue(error.getMessage().contains("$.structure.fieldCount"));
    }

    private static String simpleModel() {
        return DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", root -> root
                        .field("Name", FieldKind.STRING)
                        .numberField("Score", field -> field.fractionDigits(2)))
                .build()
                .toJson();
    }
}
