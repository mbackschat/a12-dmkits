package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * Tier-1 refactor verb {@code typedef rename} (CLI-SPEC §6): rewrites the declaration AND every using
 * field's {@code typeDefinitionId} (structural — no condition DSL), and runs the safety gate first — a
 * new id that is already taken is <b>refused</b> ({@code RK_NAME_EXISTS}, exit 2, nothing written).
 */
class TypedefRenameVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path storeModel(Path dir) throws Exception {
        Path f = dir.resolve("store.dm.json");
        Files.writeString(f, DocumentModelDraft.named("store").locale("en_US")
                .typeDef("ProductCode", t -> t.string(s -> s.maxLength(12)))
                .rootGroup("Store", s -> s
                        .typedefField("PrimarySku", "ProductCode")
                        .field("StoreName", FieldKind.STRING))
                .build()
                .toJson());
        return f;
    }

    @Test
    void renamesTheTypedefAndRepointsItsFields(@TempDir Path dir) throws Exception {
        Path model = storeModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "rename", "ProductCode", "--to", "ArticleCode");
        assertEquals(0, res.code(), res.err());

        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("ArticleCode", j.path("changed").path("to").asText());
        assertTrue(j.path("changed").path("repointedFields").toString().contains("/Store/PrimarySku"));

        String written = Files.readString(model);
        assertTrue(written.contains("ArticleCode"));
        assertFalse(written.contains("ProductCode"), "the old id is gone");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code(), "still kernel-valid");
    }

    @Test
    void refusesACollidingNewId(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("store2.dm.json");
        Files.writeString(model, DocumentModelDraft.named("store").locale("en_US")
                .typeDef("ProductCode", t -> t.string(s -> s.maxLength(12)))
                .typeDef("ArticleCode", t -> t.string(s -> s.maxLength(20)))
                .rootGroup("Store", s -> s.typedefField("PrimarySku", "ProductCode"))
                .build()
                .toJson());

        var res = CliTestSupport.run("-m", model.toString(), "typedef", "rename", "ProductCode", "--to", "ArticleCode");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_NAME_EXISTS"));
        assertTrue(Files.readString(model).contains("\"ProductCode\""), "the model is untouched");
    }
}
