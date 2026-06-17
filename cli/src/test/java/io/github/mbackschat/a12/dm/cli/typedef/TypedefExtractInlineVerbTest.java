package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

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
 * Tier-1 refactor verbs {@code typedef extract} (factor identical fields into one definition + repoint)
 * and {@code typedef inline} (the inverse). Both are structural. The extract gate refuses a taken id
 * ({@code RK_NAME_EXISTS}) and fields that do not share one concrete type ({@code RK_TYPE_MISMATCH}).
 */
class TypedefExtractInlineVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path storeModel(Path dir) throws Exception {
        Path f = dir.resolve("store.dm.json");
        Files.writeString(f, DocumentModelDraft.named("store").locale("en_US")
                .rootGroup("Store", s -> s
                        .numberField("Price", n -> n.fractionDigits(2))
                        .numberField("Cost", n -> n.fractionDigits(2))
                        .field("Name", FieldKind.STRING))
                .build()
                .toJson());
        return f;
    }

    @Test
    void extractFactorsIdenticalFieldsAndInlinePutsThemBack(@TempDir Path dir) throws Exception {
        Path model = storeModel(dir);

        var ext = CliTestSupport.run("-m", model.toString(), "typedef", "extract",
                "--from", "/Store/Price,/Store/Cost", "--id", "Money");
        assertEquals(0, ext.code(), ext.err());
        JsonNode je = M.readTree(ext.out());
        assertEquals("applied", je.get("outcome").asText());
        assertEquals("Money", je.path("changed").path("created").asText());
        assertTrue(je.path("changed").path("fromFields").toString().contains("/Store/Price"));
        assertTrue(Files.readString(model).contains("\"Money\""));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code(), "kernel-valid");

        var inl = CliTestSupport.run("-m", model.toString(), "typedef", "inline", "Money");
        assertEquals(0, inl.code(), inl.err());
        assertEquals("applied", M.readTree(inl.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("\"Money\""), "the definition is gone again");
    }

    @Test
    void extractRefusesACollidingId(@TempDir Path dir) throws Exception {
        Path model = storeModel(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(),
                "typedef", "add", "--id", "Money", "--kind", "NUMBER").code());

        var res = CliTestSupport.run("-m", model.toString(), "typedef", "extract",
                "--from", "/Store/Price,/Store/Cost", "--id", "Money");
        assertEquals(2, res.code());
        assertTrue(M.readTree(res.out()).path("diagnostics").toString().contains("RK_NAME_EXISTS"));
    }

    @Test
    void extractRefusesFieldsThatDoNotShareAConcreteType(@TempDir Path dir) throws Exception {
        Path model = storeModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "extract",
                "--from", "/Store/Price,/Store/Name", "--id", "Money");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_TYPE_MISMATCH"));
        assertFalse(Files.readString(model).contains("\"Money\""), "untouched");
    }
}
