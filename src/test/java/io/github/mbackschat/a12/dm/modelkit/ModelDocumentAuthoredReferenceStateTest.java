package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Typed authored read-back for reusable type definitions and header model references. */
class ModelDocumentAuthoredReferenceStateTest {

    @Test
    void readsCompleteTypeDefinitionState() {
        String json = DocumentModelDraft.named("catalog").locale("en_US")
                .typeDef("Sku", type -> type.string(text -> text.minLength(3).maxLength(12))
                        .internalDescription("en_US", "Stock identifier")
                        .externalDescription("en_US", "SKU")
                        .annotation("owner", "catalog"))
                .rootGroup("Catalog", catalog -> catalog.typedefField("ProductSku", "Sku"))
                .build().toJson();

        ModelDocument.TypeDefinitionState state = ModelDocument.read(json).typeDefinitions().get("Sku");
        FieldTypeSpec.StringSpec type = assertInstanceOf(FieldTypeSpec.StringSpec.class, state.type());

        assertEquals(3, type.minLength());
        assertEquals(12, type.maxLength());
        assertEquals(Map.of("en_US", "Stock identifier"), state.meta().internalDescription());
        assertEquals("catalog", state.meta().annotations().getFirst().value());
    }

    @Test
    void readsIncludeAndTypeDefinitionImportAliasesAndReferences() {
        String json = DocumentModelDraft.named("store").locale("en_US")
                .include("prices", "pricing-v2")
                .importTypeDefs("catalog-types")
                .typeDef("Sku", type -> type.string(text -> text.maxLength(12)))
                .rootGroup("Store", store -> store.stringField("Name", text -> { }))
                .build().toJson();

        // tag::authored-references[]
        ModelDocument document = ModelDocument.read(json);
        ModelDocument.TypeDefinitionState sku = document.typeDefinitions().get("Sku");
        Map<ModelDocument.ReferencePurpose, Map<String, String>> references =
                document.authoredReferences();
        // end::authored-references[]

        assertEquals("StringType", sku.type().jsonType());
        assertEquals(Map.of("prices", "pricing-v2"), references.get(ModelDocument.ReferencePurpose.INCLUDE));
        assertEquals(Map.of("catalog-types", "catalog-types"),
                references.get(ModelDocument.ReferencePurpose.TYPE_DEFINITION_IMPORT));
    }
}
