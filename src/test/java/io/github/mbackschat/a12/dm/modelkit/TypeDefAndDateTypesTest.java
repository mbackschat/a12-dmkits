package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * modelkit M3 — completes the field-type system: reusable <b>type definitions</b> (declared once on
 * the model, referenced by fields) and the rest of the date family (configurable {@code Date},
 * {@code DateRange}, {@code DateFragment}). Kernel is the oracle: typedef fields resolve to their
 * underlying kind after expansion, and the date variants classify correctly. Own domain (a registry).
 */
class TypeDefAndDateTypesTest {

    private static DocumentModelDraft registry() {
        return DocumentModelDraft.named("registry").locale("en_US")
                .typeDef("SkuCode", t -> t.string(s -> s.maxLength(16)
                        .pattern("[A-Z0-9-]+", "Uppercase letters, digits and dashes only.")))
                .rootGroup("Registry", r -> r
                        .typedefField("PrimarySku", "SkuCode", Requiredness.REQUIRED)
                        .typedefField("BackupSku", "SkuCode")
                        .dateField("EffectiveOn", d -> d.format("dd.MM.yyyy"))
                        .dateRangeField("CoverageYears", d -> d.format("yyyy").separator("/"))
                        .dateFragmentField("ReportingMonth", d -> d.fragmentFormat("yyyy-MM")))
                .build();
    }

    private static ModelIndex index(DocumentModelDraft draft) {
        try {
            return ModelIndex.ofSelfContained(draft.toJson());
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + draft.toJson(), e);
        }
    }

    @Test
    void typedefFieldsResolveToTheirUnderlyingKind() {
        ModelIndex index = index(registry());
        // a TypeDefType field resolves (after expansion) to the typedef's underlying type
        assertEquals(ValueKind.STRING, index.kindOf("/Registry/PrimarySku"));
        assertEquals(ValueKind.STRING, index.kindOf("/Registry/BackupSku"));
    }

    @Test
    void theDateFamilyClassifies() {
        ModelIndex index = index(registry());
        assertEquals(ValueKind.DATE, index.kindOf("/Registry/EffectiveOn"));
        assertEquals(ValueKind.DATE_RANGE, index.kindOf("/Registry/CoverageYears"));
        assertEquals(ValueKind.DATE_FRAGMENT, index.kindOf("/Registry/ReportingMonth"));
    }

    @Test
    void emitsTheTypeDefDeclarationReferenceAndDateSlots() {
        String json = registry().toJson();
        assertTrue(json.contains("\"typeDefinitions\""), json);
        assertTrue(json.contains("\"type\" : \"TypeDefType\""), json);
        assertTrue(json.contains("\"typeDefinitionId\" : \"SkuCode\""), json);
        assertTrue(json.contains("\"rangeSeparator\" : \"/\""), json);
        assertTrue(json.contains("\"formatOfFragment\" : \"yyyy-MM\""), json);
        assertTrue(json.contains("\"format\" : \"dd.MM.yyyy\""), json);
    }

    @Test
    void aFieldReferencingAnUndeclaredTypeDefIsRejected() {
        // referential integrity, caught fail-fast before the kernel
        assertThrows(IllegalStateException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.typedefField("F", "NopeNotDeclared"))
                .build().toJson());
    }
}
