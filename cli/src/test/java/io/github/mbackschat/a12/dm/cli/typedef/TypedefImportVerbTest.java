package io.github.mbackschat.a12.dm.cli.typedef;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
