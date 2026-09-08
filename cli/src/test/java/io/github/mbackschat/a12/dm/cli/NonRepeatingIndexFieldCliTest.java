package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

class NonRepeatingIndexFieldCliTest {

    @Test
    void groupModifyPersistsANonRepeatingIndexField(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("single.json");
        Files.writeString(model, DocumentModelDraft.named("single").locale("en_US")
                .rootGroup("Single", group -> group.field("Key", FieldKind.STRING)).build().toJson());

        var result = CliTestSupport.run("-m", model.toString(), "group", "modify", "/Single",
                "--index-field", "Key");

        assertEquals(0, result.code(), result::out);
        assertEquals("Key", ModelDocument.read(Files.readString(model)).indexField("/Single").orElseThrow());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "check").code());
    }
}
