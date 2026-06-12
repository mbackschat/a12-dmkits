package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * Tier-1 refactor: <b>typedef rename</b> (task #3, CLI-SPEC §6). Renaming a type definition rewrites
 * its declaration AND every using field's {@code typeDefinitionId} in step — purely structural (a
 * typedef never appears in condition DSL), so it dodges the parked reference-rewrite engine. The
 * kernel re-validate confirms the repoint left nothing dangling.
 */
class TypeDefRenameTest {

    private static String storeJson() {
        return DocumentModelDraft.named("store").locale("en_US")
                .typeDef("ProductCode", t -> t.string(s -> s.maxLength(12)))
                .rootGroup("Store", s -> s
                        .typedefField("PrimarySku", "ProductCode")
                        .typedefField("BackupSku", "ProductCode")
                        .field("StoreName", FieldKind.STRING))
                .build()
                .toJson();
    }

    @Test
    void renameRepointsEveryUsingFieldAndTheDeclaration() {
        ModelDocument doc = ModelDocument.read(storeJson()).renameTypeDefinition("ProductCode", "ArticleCode");

        assertEquals(List.of("/Store/PrimarySku", "/Store/BackupSku"),
                doc.fieldsUsingTypeDefinition("ArticleCode"), "every using field repointed");
        assertTrue(doc.fieldsUsingTypeDefinition("ProductCode").isEmpty(), "no field left on the old id");
        assertTrue(doc.typeDefinitionIds().contains("ArticleCode"));
        assertFalse(doc.typeDefinitionIds().contains("ProductCode"), "the old declaration is gone");
    }

    @Test
    void renamingAnAbsentTypeDefinitionFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(storeJson()).renameTypeDefinition("Nonexistent", "Whatever"));
    }

    @Test
    void theRenamedModelStillValidatesThroughTheKernel() {
        EditSession session = EditSession.read(storeJson());
        session.typeDef("ProductCode").rename("ArticleCode");

        String result = session.validate().toJson();   // kernel gate: a dangling typedef ref would throw
        assertEquals(List.of("/Store/PrimarySku", "/Store/BackupSku"),
                ModelDocument.read(result).fieldsUsingTypeDefinition("ArticleCode"));
    }
}
