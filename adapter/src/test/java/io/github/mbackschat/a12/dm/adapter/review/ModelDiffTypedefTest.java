package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;

/** MR9 Set 4 — reusable type definitions are complete first-class diff elements. */
class ModelDiffTypedefTest {

    @Test
    void detectsTypeDefinitionAdditionAndRemoval() {
        String base = withoutTypeDefinition();
        String head = stringTypeDefinition("Sku", 12);

        assertEquals(ChangeKind.ADDED, change(ModelDiff.between(base, head), "Sku").change());
        assertEquals(ChangeKind.REMOVED, change(ModelDiff.between(head, base), "Sku").change());
    }

    @Test
    void detectsTypedConfigurationEnumValuesAndMetadata() {
        ElementChange config = change(ModelDiff.between(
                stringTypeDefinition("Sku", 12), stringTypeDefinition("Sku", 24)), "Sku");
        assertEquals("12", delta(config, "maxLength").before());
        assertEquals("24", delta(config, "maxLength").after());

        ElementChange enumeration = change(ModelDiff.between(
                enumTypeDefinition("Status", "OPEN", "CLOSED"),
                enumTypeDefinition("Status", "OPEN", "CLOSED", "ON_HOLD")), "Status");
        assertEquals(ModelDiff.Risk.HIGH, enumeration.risk());
        assertTrue(enumeration.reasons().contains("CONSTRAINT_LOOSENED"));

        ElementChange metadata = change(ModelDiff.between(
                describedTypeDefinition("old", "team-a"), describedTypeDefinition("new", "team-b")), "Sku");
        assertTrue(metadata.deltas().stream().anyMatch(delta -> delta.aspect().equals("internalDescription.en_US")));
        assertTrue(metadata.deltas().stream().anyMatch(delta -> delta.aspect().equals("annotation.owner")));
    }

    @Test
    void pairsOnlyAnExactTypeDefinitionRename() {
        String base = usedTypeDefinition("Sku", 12);
        EditSession exact = EditSession.read(base);
        exact.renameTypeDefinition("Sku", "ProductCode").validate();

        ElementChange renamed = change(ModelDiff.between(base, exact.toJson()), "Sku");
        assertEquals(ElementKind.TYPEDEF, renamed.element());
        assertEquals(ChangeKind.RENAMED, renamed.change());
        assertEquals("ProductCode", delta(renamed, "path").after());

        EditSession edited = EditSession.read(base);
        edited.renameTypeDefinition("Sku", "ProductCode")
                .setTypeDefinitionType("ProductCode", new FieldTypeSpec.StringSpec(null, 24, null, null))
                .validate();
        ModelDiff renameAndEdit = ModelDiff.between(base, edited.toJson());

        assertFalse(renameAndEdit.changes().stream().anyMatch(change -> change.element() == ElementKind.TYPEDEF
                && change.change() == ChangeKind.RENAMED));
        assertTrue(renameAndEdit.changes().stream().anyMatch(change -> change.element() == ElementKind.TYPEDEF
                && change.change() == ChangeKind.REMOVED));
        assertTrue(renameAndEdit.changes().stream().anyMatch(change -> change.element() == ElementKind.TYPEDEF
                && change.change() == ChangeKind.ADDED));
    }

    private static String withoutTypeDefinition() {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", catalog -> catalog.stringField("Name", text -> { }))
                .build().toJson();
    }

    private static String stringTypeDefinition(String id, int maxLength) {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .typeDef(id, type -> type.string(text -> text.maxLength(maxLength)))
                .rootGroup("Catalog", catalog -> catalog.stringField("Name", text -> { }))
                .build().toJson();
    }

    private static String usedTypeDefinition(String id, int maxLength) {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .typeDef(id, type -> type.string(text -> text.maxLength(maxLength)))
                .rootGroup("Catalog", catalog -> catalog.typedefField("ProductSku", id))
                .build().toJson();
    }

    private static String enumTypeDefinition(String id, String... values) {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .typeDef(id, type -> type.enumeration(enumeration ->
                        Arrays.stream(values).forEach(enumeration::value)))
                .rootGroup("Catalog", catalog -> catalog.stringField("Name", text -> { }))
                .build().toJson();
    }

    private static String describedTypeDefinition(String description, String owner) {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .typeDef("Sku", type -> type.string(text -> text.maxLength(12))
                        .internalDescription("en_US", description).annotation("owner", owner))
                .rootGroup("Catalog", catalog -> catalog.stringField("Name", text -> { }))
                .build().toJson();
    }

    private static ElementChange change(ModelDiff diff, String id) {
        return diff.changes().stream().filter(candidate -> candidate.element() == ElementKind.TYPEDEF
                        && candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no typedef change for " + id + " in " + diff.changes()));
    }

    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + aspect + "' delta in " + change.deltas()));
    }
}
