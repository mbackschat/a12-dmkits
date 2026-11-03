package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/** Authored includes/imports stay distinct from their expanded model content. */
class ModelDiffIncludeTest {

    private static final ModelResolver RESOLVER = reference -> switch (reference) {
        case "pricing-v1", "pricing-v2" -> DocumentModelDraft.named(reference).locale("en_US")
                .rootGroup("Pricing", pricing -> pricing.numberField("Amount", number -> number.fractionDigits(2)))
                .build().toJson();
        case "catalog-types" -> DocumentModelDraft.typeDefModel(reference).locale("en_US")
                .typeDef("Sku", type -> type.string(text -> text.maxLength(12))).build().toJson();
        default -> null;
    };

    @Test
    void detectsIncludeAdditionRemovalAndRetargetWithoutInventingExpandedChanges() {
        String plain = plainStore();
        String included = ModelDocument.read(plain).mount("/Store", "Retail", "pricing-v1").toJson();

        assertEquals(ChangeKind.ADDED, change(ModelDiff.between(plain, included, RESOLVER), "pricing-v1").change());
        assertEquals(ChangeKind.REMOVED, change(ModelDiff.between(included, plain, RESOLVER), "pricing-v1").change());

        String retargeted = ModelDocument.read(included).setIncludeReference("pricing-v1", "pricing-v2").toJson();
        ModelDiff diff = ModelDiff.between(included, retargeted, RESOLVER);
        ElementChange mount = diff.changes().stream()
                .filter(change -> change.element() == ElementKind.GROUP && change.id().equals("/Store/Retail"))
                .findFirst().orElseThrow(() -> new AssertionError("no mount retarget in " + diff.changes()));
        assertEquals("pricing-v1", delta(mount, "mount.reference").before());
        assertEquals("pricing-v2", delta(mount, "mount.reference").after());
        assertTrue(diff.changes().stream().allMatch(change -> change.element() == ElementKind.INCLUDE
                        || change == mount),
                () -> "expanded provider content must stay distinct: " + diff.changes());
    }

    @Test
    void detectsTypeDefinitionImportAdditionAndRemoval() {
        String plain = plainStore();
        String imported = ModelDocument.read(plain).addImport("catalog-types").toJson();

        ElementChange added = change(ModelDiff.between(plain, imported, RESOLVER), "catalog-types");
        assertEquals("TYPE_DEFINITION_IMPORT", delta(added, "purpose").after());
        assertEquals(ChangeKind.REMOVED,
                change(ModelDiff.between(imported, plain, RESOLVER), "catalog-types").change());
    }

    private static String plainStore() {
        return DocumentModelDraft.named("store").locale("en_US")
                .rootGroup("Store", store -> store.field("Name", FieldKind.STRING))
                .build().toJson();
    }

    private static ElementChange change(ModelDiff diff, String id) {
        return diff.changes().stream().filter(candidate -> candidate.element() == ElementKind.INCLUDE
                        && candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no include change for " + id + " in " + diff.changes()));
    }

    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + aspect + "' delta in " + change.deltas()));
    }
}
