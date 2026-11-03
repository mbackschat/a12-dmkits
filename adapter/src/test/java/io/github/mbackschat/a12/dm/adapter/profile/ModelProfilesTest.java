package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

class ModelProfilesTest {

    @Test
    void extractsAnIdentifierFreeProfileThroughThePublicStringBoundary() {
        String modelJson = model("survey", "Survey", "ParticipantName");
        // tag::profile-extract[]
        String profileJson = ModelProfiles.extract(modelJson);
        // end::profile-extract[]
        var profile = ModelProfileJson.read(profileJson);

        assertEquals(1, profile.structure().fieldCount());
        assertFalse(profileJson.contains("Survey"));
        assertFalse(profileJson.contains("ParticipantName"));
    }

    @Test
    void resolvesIncludedModelsThroughTheKernelFreeResolver() {
        String included = model("pricelist", "Pricelist", "Currency");
        EditSession session = EditSession.read(model("catalog", "Catalog", "StoreName"));
        session.mount("/Catalog", "Retail", "pricelist");
        ModelResolver resolver = id -> "pricelist".equals(id) ? included : null;

        var profile = ModelProfileJson.read(ModelProfiles.extract(session.toJson(), resolver));

        assertEquals(2, profile.structure().groupCount());
        assertEquals(2, profile.structure().fieldCount());
    }

    private static String model(String id, String group, String field) {
        return DocumentModelDraft.named(id).locale("en_US")
                .rootGroup(group, root -> root.field(field, FieldKind.STRING))
                .build()
                .toJson();
    }
}
