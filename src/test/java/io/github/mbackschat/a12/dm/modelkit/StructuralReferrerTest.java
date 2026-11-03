package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Structural referrer queries (refactor task #2, CLI-SPEC §6): which fields point at a type definition
 * (the {@code typeDefinitionId} pointer) and which mounts name an included model ({@code includeConfig.reference}).
 * These are the *find* leg the structural refactors rely on — typedef rename repoints exactly the
 * fields {@link ModelDocument#fieldsUsingTypeDefinition} returns. They are pure structure reads (no
 * kernel resolution), distinct from the adapter's rule-reference {@code whereUsed}.
 */
class StructuralReferrerTest {

    private static ModelDocument model() {
        return ModelDocument.read(DocumentModelDraft.named("store").locale("en_US")
                .typeDef("ProductCode", t -> t.string(s -> s.maxLength(12)))
                .rootGroup("Store", s -> s
                        .typedefField("PrimarySku", "ProductCode")
                        .typedefField("BackupSku", "ProductCode")
                        .field("StoreName", FieldKind.STRING)
                        .mount("Inventory", "catalog"))
                .build()
                .toJson());
    }

    @Test
    void fieldsUsingATypeDefinitionAreFoundInDocumentOrder() {
        assertEquals(List.of("/Store/PrimarySku", "/Store/BackupSku"),
                model().fieldsUsingTypeDefinition("ProductCode"));
    }

    @Test
    void anUnusedTypeDefinitionHasNoReferrers() {
        assertTrue(model().fieldsUsingTypeDefinition("Absent").isEmpty());
    }

    @Test
    void mountsCarryingAnIncludeAliasAreFound() {
        assertEquals(List.of("/Store/Inventory"), model().mountsOfInclude("catalog"));
    }

    @Test
    void anUnmountedAliasHasNoReferrers() {
        assertTrue(model().mountsOfInclude("absent").isEmpty());
    }
}
