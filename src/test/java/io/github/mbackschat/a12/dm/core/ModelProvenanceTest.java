package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * G44's one genuinely new model read: include-origin and named-type provenance survive expansion without
 * contaminating the interpreter-ready DM-JSON.
 */
class ModelProvenanceTest {

    @Test
    void resolvesRelativeAndAbsoluteLogicAnchorsThroughTheSharedCorePathResolver() {
        assertEquals("/Order/Email", ModelIndex.resolvePath("/Order/EmailRequired", "../Email"));
        assertEquals("/Order/Email", ModelIndex.resolvePath("/Order/EmailRequired", "/Order/Email"));
    }

    @Test
    void retainsHeaderOriginsNestedMountsNamedTypesAndMixedDocumentOrder() {
        String leaf = DocumentModelDraft.named("LeafHeader").locale("en_US")
                .rootGroup("Leaf", root -> root.stringField("LeafCode", field -> {}))
                .build().toJson();
        String middle = DocumentModelDraft.named("MiddleHeader").locale("en_US")
                .include("leafAlias", "leaf-reference")
                .rootGroup("Middle", root -> root.mount("LeafMount", "leafAlias"))
                .build().toJson();
        String base = DocumentModelDraft.named("BaseHeader").locale("en_US")
                .include("middleAlias", "middle-reference")
                .typeDef("EmailType", type -> type.string(string -> string.minLength(5)))
                .rootGroup("Base", root -> root
                        .typedefField("Email", "EmailType")
                        .mount("MiddleMount", "middleAlias"))
                .build().toJson();

        ModelIndex index = ModelIndex.of(base, reference -> switch (reference) {
            case "middle-reference" -> middle;
            case "leaf-reference" -> leaf;
            default -> null;
        });

        assertEquals(List.of("/Base", "/Base/Email", "/Base/MiddleMount",
                        "/Base/MiddleMount/LeafMount", "/Base/MiddleMount/LeafMount/LeafCode"),
                index.elementPaths().toList());
        assertEquals("MiddleHeader", index.originOf("/Base/MiddleMount").orElseThrow(),
                "origin is the included header id, not its alias or resolver reference");
        assertEquals("LeafHeader", index.originOf("/Base/MiddleMount/LeafMount").orElseThrow());
        assertFalse(index.originOf("/Base/MiddleMount/LeafMount/LeafCode").isPresent(),
                "only the include boundary carries origin; the subtree is expanded inline");
        assertEquals("EmailType", index.typeReferenceOf("/Base/Email").orElseThrow());
        assertEquals(ValueKind.STRING, index.kindOf("/Base/Email"), "the effective type remains inlined");
    }

    @Test
    void retainsADanglingNamedTypeWithoutAddingPrivateMarkersToExpandedJson() {
        String model = """
                { "header": { "id": "m", "modelType": "document", "modelVersion": "28.4.0",
                    "locales": [{"code":"en_US"}], "modelReferences": [] },
                  "content": { "modelInfo": {"name":"m"}, "modelRoot": { "rootGroups": [
                    { "type":"Group", "id":"/G", "name":"G", "Group": { "repeatability":1, "elements": [
                      { "type":"Field", "id":"/G/F", "name":"F",
                        "Field": { "fieldType": { "type":"TypeDefType",
                          "TypeDefType": { "typeDefinitionId":"MissingType" } } } }
                    ] } } ] } } }
                """;
        ModelReader reader = ModelReader.expand(model, ReferenceResolvers.selfContained());
        ModelIndex index = ModelIndex.of(reader);

        assertEquals("MissingType", index.typeReferenceOf("/G/F").orElseThrow());
        assertFalse(reader.toJson().contains("\"origin\""));
        assertFalse(reader.toJson().contains("\"typeRef\""));
    }
}
