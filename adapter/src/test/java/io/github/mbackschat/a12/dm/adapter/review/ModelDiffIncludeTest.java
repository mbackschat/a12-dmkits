package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
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
        String included = ModelDocument.read(plain).addInclude("prices", "pricing-v1").toJson();

        assertEquals(ChangeKind.ADDED, change(ModelDiff.between(plain, included, RESOLVER), "prices").change());
        assertEquals(ChangeKind.REMOVED, change(ModelDiff.between(included, plain, RESOLVER), "prices").change());

        String retargeted = ModelDocument.read(included).setIncludeReference("prices", "pricing-v2").toJson();
        ModelDiff diff = ModelDiff.between(included, retargeted, RESOLVER);
        ElementChange retarget = change(diff, "prices");
        assertEquals("pricing-v1", delta(retarget, "reference").before());
        assertEquals("pricing-v2", delta(retarget, "reference").after());
        assertEquals(1, diff.changes().size(), () -> "expanded provider content must stay distinct: " + diff.changes());
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

    @Test
    void includeAliasRenameAlsoSurfacesTheMountRewire() {
        EditSession baseSession = EditSession.read(plainStore(), RESOLVER);
        baseSession.addInclude("prices", "pricing-v1", "/Store", "Retail");
        String base = baseSession.toJson();
        String head = ModelDocument.read(base).renameInclude("prices", "tariffs").toJson();

        ModelDiff diff = ModelDiff.between(base, head, RESOLVER);
        ElementChange renamed = change(diff, "prices");
        assertEquals(ChangeKind.RENAMED, renamed.change());
        assertEquals("tariffs", delta(renamed, "path").after());
        ElementChange mount = diff.changes().stream()
                .filter(change -> change.element() == ElementKind.GROUP && change.id().equals("/Store/Retail"))
                .findFirst().orElseThrow(() -> new AssertionError("no mount rewire in " + diff.changes()));
        assertEquals("prices", delta(mount, "mount.alias").before());
        assertEquals("tariffs", delta(mount, "mount.alias").after());
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
