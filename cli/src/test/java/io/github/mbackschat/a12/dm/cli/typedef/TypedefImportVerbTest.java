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

/**
 * {@code typedef import} — declare that the model imports another model's type definitions
 * ({@code purpose: "typeDefinitions"}). The type-def-providing model is resolved + kernel-validated via
 * {@code -w/--workspace}; unlike {@code include add}, the import is not mounted.
 */
class TypedefImportVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** An en_US consumer + a sibling type-def-only provider model in the same dir (resolved by header id). */
    private static Path orderWithTypeProvider(Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // en_US-only fixture
        Files.writeString(dir.resolve("catalogtypes.dm.json"),
                DocumentModelDraft.typeDefModel("catalogtypes").locale("en_US")
                        .typeDef("Sku", t -> t.string(s -> s.maxLength(12)))
                        .build().toJson());
        return model;
    }

    @Test
    void importsAnotherModelsTypeDefinitions(@TempDir Path dir) throws Exception {
        Path model = orderWithTypeProvider(dir);
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "import",
                "--reference", "catalogtypes", "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("catalogtypes", j.path("changed").path("imported").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("\"purpose\" : \"typeDefinitions\""),
                "the import is declared with purpose=typeDefinitions");
    }

    @Test
    void unresolvedImportIsRejected(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // no provider written
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "import",
                "--reference", "nope", "-w", dir.toString());
        assertTrue(res.code() != 0, "an unresolved type-def import is rejected by the kernel");
    }

    // typedef unimport — the inverse (the editor's "REMOVE IMPORT"); §7 long-tail item.

    @Test
    void unimportRemovesADeclaredImport(@TempDir Path dir) throws Exception {
        Path model = orderWithTypeProvider(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "typedef", "import",
                "--reference", "catalogtypes", "-w", dir.toString()).code());
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "unimport",
                "--reference", "catalogtypes", "-w", dir.toString());
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
        assertFalse(Files.readString(model).contains("\"purpose\" : \"typeDefinitions\""),
                "the import declaration is gone");
    }

    @Test
    void typedefReadSurfacesTheImportSource(@TempDir Path dir) throws Exception {
        // provenance: `typedef read` lists local ids in `typedefs` and the imported-from model ids in
        // `importedFrom` (the editor's Source dimension), so an agent sees types also come from elsewhere
        Path model = orderWithTypeProvider(dir);
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "typedef", "import",
                "--reference", "catalogtypes", "-w", dir.toString()).code());
        var read = CliTestSupport.run("-m", model.toString(), "typedef", "read");
        assertEquals(0, read.code(), read::err);
        JsonNode importedFrom = M.readTree(read.out()).path("data").path("importedFrom");
        assertTrue(importedFrom.isArray() && importedFrom.toString().contains("catalogtypes"),
                () -> "the import source is surfaced: " + read.out());
    }

    @Test
    void unimportingAnAbsentImportIsRefused(@TempDir Path dir) throws Exception {
        Path model = orderWithTypeProvider(dir);   // no import declared yet
        var res = CliTestSupport.run("-m", model.toString(), "typedef", "unimport",
                "--reference", "catalogtypes", "-w", dir.toString());
        assertEquals(2, res.code(), () -> "no such import → spec error: " + res.out() + res.err());
        assertTrue(res.err().contains("catalogtypes"), () -> "names the missing import: " + res.err());
    }
}
