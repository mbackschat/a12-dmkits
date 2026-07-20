package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

class ModelProfilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void extractsTheBareCurrentProfileThroughAStringOnlyPublicBoundary() throws Exception {
        String modelJson = DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", root -> root
                        .field("ParticipantName", FieldKind.STRING)
                        .numberField("Score", field -> field.fractionDigits(2)))
                .build()
                .toJson();

        // tag::profile-extract[]
        String profileJson = ModelProfiles.extract(modelJson);
        // end::profile-extract[]

        var profile = JSON.readTree(profileJson);
        assertEquals(
                java.util.Set.of("structure", "rules", "computations", "wiring"),
                propertyNames(profile));
        assertFalse(profile.has("profileVersion"));
        assertFalse(profileJson.contains("Survey"));
        assertFalse(profileJson.contains("ParticipantName"));
        assertEquals(
                ModelProfileJson.write(ModelProfileExtractor.extract(modelJson)),
                profileJson);
    }

    @Test
    void resolvesMountedModelsThroughTheKernelFreeResolverOverload() {
        String included = DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", root -> root.field("Currency", FieldKind.STRING))
                .build()
                .toJson();
        String base = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", root -> root.field("StoreName", FieldKind.STRING))
                .build()
                .toJson();
        EditSession session = EditSession.read(base);
        session.addInclude("pricelist", "pricelist", "/Catalog", "Retail");
        ModelResolver resolver = id -> "pricelist".equals(id) ? included : null;

        ModelProfile profile = ModelProfileJson.read(ModelProfiles.extract(session.toJson(), resolver));

        assertEquals(2L, profile.structure().elements().counts().get("GROUP"));
        assertEquals(2L, profile.structure().elements().counts().get("FIELD"));
    }

    private static java.util.Set<String> propertyNames(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
