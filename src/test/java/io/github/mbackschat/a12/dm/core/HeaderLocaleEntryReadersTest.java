package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Every reader of a header {@code locales[]} entry reads a bare string as it reads an object carrying {@code code}.
 *
 * A12 admits both shapes and applies the same demands to each (KERNEL-FINDINGS KF290, locked by
 * {@code rulekit.validate.laws.MessageSlotRawShapeLawsTest}); a reader knowing only the object shape read a bare
 * entry as the empty code. One class holds all the readers, so a new reader joins the guard rather than the bug.
 *
 * Own domain (an insurance policy with a reference).
 */
class HeaderLocaleEntryReadersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BARE = bare("de_DE", "fr_CH");

    @Test
    void theIndexReadsABareEntry() {
        assertEquals(List.of("de_DE", "fr_CH"), ModelIndex.of(BARE, ReferenceResolvers.selfContained()).locales());
    }

    @Test
    void theModelDocumentReadsABareEntry() {
        assertEquals(List.of("de_DE", "fr_CH"), ModelDocument.read(BARE).locales());
    }

    @Test
    void addingADeclaredBareLocaleIsANoOp() {
        assertEquals(List.of("de_DE", "fr_CH"), ModelDocument.read(BARE).addLocale("fr_CH").locales());
    }

    @Test
    void aBareLocaleIsRemovable() {
        assertEquals(List.of("de_DE"), ModelDocument.read(BARE).removeLocale("fr_CH").locales());
    }

    @Test
    void theLastBareLocaleIsNotRemovable() {
        assertThrows(IllegalArgumentException.class, () -> ModelDocument.read(bare("de_DE")).removeLocale("de_DE"));
    }

    @Test
    void theValidatorDeclaresABareEntry() {
        assertEquals(List.of("de_DE", "fr_CH"), new RuleValidator().declaredLocales(BARE));
    }

    private static String bare(String... codes) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(DocumentModelDraft.named("policy-bare-locales").locale("en_US")
                    .rootGroup("Policy", p -> p.stringField("Ref", f -> { }))
                    .build().toJson());
            ArrayNode locales = ((ObjectNode) root.path("header")).putArray("locales");
            for (String code : codes) {
                locales.add(code);
            }
            return root.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the fixture must parse", e);
        }
    }
}
