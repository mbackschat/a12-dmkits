package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * Type-def-only model + import (FEATURE-GAPS). A <b>type-def-only</b> model carries only reusable
 * {@code typeDefinitions} and no structure — a normal {@code modelType: "document"} model with an empty
 * root, which the kernel accepts standalone. A consumer pulls those types in with a {@code modelReferences}
 * entry of {@code purpose: "typeDefinitions"} (the kernel constant {@code PURPOSE_4_ONLY_TYPE_DEFS}); the
 * imported type-defs merge into the consumer's namespace by id, so a field references one with a plain
 * {@code typeDefinitionId}.
 */
class TypeDefModelImportTest {

    /** The provider: a type-def-only model carrying a {@code Sku} string type and a {@code Money} number type. */
    private static String catalogTypes() {
        return DocumentModelDraft.typeDefModel("catalogtypes").locale("en_US")
                .typeDef("Sku", t -> t.string(s -> s.maxLength(12)))
                .typeDef("Money", t -> t.number(n -> n.fractionDigits(2)))
                .build().toJson();
    }

    private static ModelResolver resolver(String providerJson) {
        return reference -> {
            if ("catalogtypes".equals(reference)) {
                return providerJson;
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    @Test
    void aTypeDefOnlyModelIsKernelValidStandalone() throws Exception {
        String provider = catalogTypes();
        ModelLoader.loadSelfContained(provider);   // kernel-valid with no root groups
        ModelDocument doc = ModelDocument.read(provider);
        assertTrue(doc.groupPaths().isEmpty(), "a type-def-only model has no groups");
        assertEquals(List.of("Sku", "Money"), doc.typeDefinitionIds(),
                "it carries only the declared type definitions");
    }

    @Test
    void aConsumerImportsAndUsesTheTypeDefsThroughTheKernel_authoredFromScratch() throws Exception {
        ModelResolver resolver = resolver(catalogTypes());
        // author the consumer from scratch: import + a field typed by an imported id (no local declaration)
        String consumer = DocumentModelDraft.named("catalog").locale("en_US")
                .importTypeDefs("catalogtypes")
                .rootGroup("Catalog", c -> c
                        .typedefField("ProductSku", "Sku")
                        .typedefField("Price", "Money"))
                .build().toJson();
        // the kernel resolves the imported types at expand and the consumer is valid
        ModelLoader.loadAndCheck(consumer, resolver);
        ModelDocument doc = ModelDocument.read(consumer);
        assertEquals(List.of("catalogtypes"), doc.typeDefImports(),
                "the import is declared as purpose=typeDefinitions, not an include");
        assertTrue(doc.includes().isEmpty(), "an import is not an include");
    }

    @Test
    void aConsumerImportsThroughTheEditPath() throws Exception {
        ModelResolver resolver = resolver(catalogTypes());
        String base = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c.field("StoreName", FieldKind.STRING))
                .build().toJson();
        String consumer = ModelDocument.read(base)
                .addImport("catalogtypes")
                .addTypedefField("/Catalog", "ProductSku", "Sku")
                .validate(resolver)
                .toJson();
        assertTrue(ModelDocument.read(consumer).typeDefImports().contains("catalogtypes"),
                "the edit path declares the import; the kernel validated the imported-typed field");
    }

    @Test
    void anIncludedModelCanContributeATypeDefinitionToTheConsumerNamespace() throws Exception {
        String component = DocumentModelDraft.named("catalog-component").locale("en_US")
                .typeDef("ComponentCode", type -> type.string(string -> string.maxLength(10)))
                .rootGroup("Component", group -> group.field("Description", FieldKind.STRING))
                .build()
                .toJson();
        String consumer = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", catalog -> catalog
                        .typedefField("Code", "ComponentCode")
                        .mount("Details", "catalog-component"))
                .build()
                .toJson();
        ModelResolver resolver = reference -> "catalog-component".equals(reference) ? component : null;

        ModelLoader.loadAndCheck(consumer, resolver);
        assertEquals(
                ValueKind.STRING,
                ModelIndex.of(consumer, resolver).kindOf("/Catalog/Code"));
    }
}
