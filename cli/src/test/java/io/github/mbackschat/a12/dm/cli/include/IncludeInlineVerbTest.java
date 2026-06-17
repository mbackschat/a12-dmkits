package io.github.mbackschat.a12.dm.cli.include;

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
 * The inverse of {@code group extract}: {@code include inline <alias>} folds a mounted include's content
 * back into the base as a real group and drops the reference (CLI-SPEC §6). Exercised as a round-trip —
 * extract then inline — so the base ends self-contained again, kernel-valid.
 */
class IncludeInlineVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void extractThenInlineFoldsTheSubModelBackIntoTheBase(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("order.dm.json");
        Files.writeString(model, DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Ref", FieldKind.STRING)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING)))
                .build()
                .toJson());

        assertEquals(0, CliTestSupport.run("-m", model.toString(),
                "group", "extract", "/Order/Shipping", "--reference", "shipping").code());
        assertTrue(Files.readString(model).contains("\"modelAlias\""), "extracted → mounted");

        var res = CliTestSupport.run("-m", model.toString(), "include", "inline", "shipping");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("shipping", j.path("changed").path("inlined").asText());

        String baseAfter = Files.readString(model);
        assertFalse(baseAfter.contains("\"modelAlias\""), "the mount became a real group again");
        assertTrue(baseAfter.contains("\"name\" : \"City\""), "Shipping's fields are inline in the base again");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code(), "self-contained + valid");
    }

    @Test
    void inliningAnUnknownAliasFails(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("order.dm.json");
        Files.writeString(model, DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.field("Ref", FieldKind.STRING))
                .build()
                .toJson());
        assertEquals(2, CliTestSupport.run("-m", model.toString(), "include", "inline", "nope").code());
    }
}
