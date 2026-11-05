package io.github.mbackschat.a12.dm.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * A verb that matches a locale must match it as A12 reads it: {@code en-US} and {@code en_US} name one locale
 * (KERNEL-FINDINGS KF290). The header here is written with A12's hyphen spelling, which A12 admits, and each verb is
 * given the other spelling; the verb outcome, which passes the kernel gate, is the oracle. Own domain (orders).
 */
class LocaleSpellingVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void anAttachmentServesAModelWhoseLocalesAreHyphenated(@TempDir Path dir) throws Exception {
        Path model = hyphenated(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "attachment", "--parent", "/Order", "--name", "Invoice");
        assertEquals(0, res.code(), res.out());
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
    }

    @Test
    void removingALocaleMatchesItsOtherSpelling(@TempDir Path dir) throws Exception {
        Path model = hyphenated(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--remove-locale", "de_DE");
        assertEquals(0, res.code(), res.out());
        assertEquals("de_DE", M.readTree(res.out()).path("changed").path("removedLocales").path(0).asText(), res.out());
        assertEquals(java.util.List.of("en-US"), ModelDocument.read(Files.readString(model)).locales());
    }

    @Test
    void addingADeclaredLocaleInItsOtherSpellingChangesNothing(@TempDir Path dir) throws Exception {
        Path model = hyphenated(dir);
        var res = CliTestSupport.run("-m", model.toString(), "config", "modify", "--add-locale", "en_US");
        assertEquals(0, res.code(), res.out());
        JsonNode changed = M.readTree(res.out()).path("changed");
        assertFalse(changed.has("addedLocales"), res.out());
        assertEquals(java.util.List.of("en-US", "de-DE"), ModelDocument.read(Files.readString(model)).locales());
    }

    /** An order model whose header declares {@code en-US} and {@code de-DE}, as a hand-written model may; only the
     *  header is respelled, the {@code en_US} condition language written after it is not. */
    private static Path hyphenated(Path dir) throws Exception {
        String json = DocumentModelDraft.named("order").locale("en_US").locale("de_DE")
                .rootGroup("Order", g -> g.field("Reference", FieldKind.STRING)).build().toJson()
                .replaceFirst("\"code\" : \"en_US\"", "\"code\" : \"en-US\"").replace("\"code\" : \"de_DE\"", "\"code\" : \"de-DE\"");
        Path model = dir.resolve("order.dm.json");
        Files.writeString(model, json);
        assertEquals(java.util.List.of("en-US", "de-DE"), ModelDocument.read(json).locales(), "the fixture is hyphenated");
        return model;
    }
}
