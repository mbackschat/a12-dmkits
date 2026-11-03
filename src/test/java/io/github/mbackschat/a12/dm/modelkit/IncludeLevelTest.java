package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;

import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * DG6 — model-modularity completeness: an include mount can be authored at {@link IncludeLevel#MODEL_ROOT}
 * (the kernel's optional group {@code includeLevel}, default SINGLE_RG), and a foreign model's
 * {@code transitiveInclude} references are distinguished from direct includes on read (A12K-3234).
 */
class IncludeLevelTest {

    private static String addressModel() {
        return DocumentModelDraft.named("address").locale("en_US")
                .rootGroup("Address", a -> a.field("Street", FieldKind.STRING).field("City", FieldKind.STRING))
                .build().toJson();
    }

    private static ModelResolver resolver() {
        return reference -> {
            if ("address".equals(reference)) {
                return addressModel();
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    @Test
    void modelRootMountEmitsIncludeLevel_singleRgOmitsIt() {
        String modelRoot = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.mount("ShipTo", "address", MountOptions.single().asModelRoot()))
                .build().toJson();
        assertTrue(modelRoot.contains("\"includeLevel\" : \"MODEL_ROOT\""),
                "a MODEL_ROOT mount carries the includeLevel attribute");

        String singleRg = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.mount("ShipTo", "address"))
                .build().toJson();
        assertFalse(singleRg.contains("includeLevel"),
                "SINGLE_RG is the kernel default — omit the attribute (don't write it)");
    }

    @Test
    void modelRootMountIsAcceptedByTheKernel() throws Exception {
        String model = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.mount("ShipTo", "address", MountOptions.single().asModelRoot()))
                .build().toJson();
        // loading runs expand + checkConsistency — a throw would mean the kernel rejects includeLevel
        ModelLoader.loadAndCheck(model, resolver());
    }

    @Test
    void transitiveIncludeIsDistinguishedFromDirectIncludeOnRead() {
        // a foreign/expanded model carrying both a direct include and a kernel-added transitive one
        String json = """
                { "header": { "id": "host", "modelType": "document", "modelVersion": "29.4.0",
                    "locales": ["en_US"],
                    "modelReferences": [
                      { "alias": "addr", "modelType": "document", "purpose": "include", "reference": "address" },
                      { "alias": "geo", "modelType": "document", "purpose": "transitiveInclude", "reference": "geo" }
                    ] },
                  "rootGroup": { "type": "Group", "id": "/Host", "name": "Host", "Group": {} } }""";
        ModelDocument doc = ModelDocument.read(json);

        List<ModelDocument.Include> direct = doc.includes();
        assertEquals(1, direct.size());
        assertEquals("address", direct.get(0).reference());

        List<ModelDocument.Include> transitive = doc.transitiveIncludes();
        assertEquals(1, transitive.size());
        assertEquals("geo", transitive.get(0).reference());
    }
}
