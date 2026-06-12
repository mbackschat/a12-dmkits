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
 * Tier-1 refactor: <b>extract fields → typedef</b> and its inverse <b>inline typedef</b> (task #4,
 * CLI-SPEC §6). Both are purely structural (no condition DSL): extract factors a set of fields that
 * share an identical concrete type into one reusable definition and repoints them; inline expands the
 * definition back into each using field and drops it. The kernel re-validate is the backstop.
 */
class TypeDefExtractInlineTest {

    private static String storeJson() {
        return DocumentModelDraft.named("store").locale("en_US")
                .rootGroup("Store", s -> s
                        .numberField("Price", n -> n.fractionDigits(2))
                        .numberField("Cost", n -> n.fractionDigits(2))
                        .field("Name", FieldKind.STRING))
                .build()
                .toJson();
    }

    @Test
    void extractFactorsIdenticalFieldsIntoOneTypedefAndRepointsThem() {
        ModelDocument doc = ModelDocument.read(storeJson())
                .extractTypeDefinition("Money", List.of("/Store/Price", "/Store/Cost"));

        assertTrue(doc.typeDefinitionIds().contains("Money"));
        assertEquals(List.of("/Store/Price", "/Store/Cost"), doc.fieldsUsingTypeDefinition("Money"));
    }

    @Test
    void extractRefusesFieldsThatDoNotShareAConcreteType() {
        // Price (number) vs Name (string) — no single concrete type to factor out
        assertThrows(IllegalArgumentException.class, () -> ModelDocument.read(storeJson())
                .extractTypeDefinition("Money", List.of("/Store/Price", "/Store/Name")));
    }

    @Test
    void inlineExpandsTheTypedefBackIntoEachFieldAndRemovesIt() {
        ModelDocument doc = ModelDocument.read(storeJson())
                .extractTypeDefinition("Money", List.of("/Store/Price", "/Store/Cost"))
                .inlineTypeDefinition("Money");

        assertFalse(doc.typeDefinitionIds().contains("Money"), "the definition is gone");
        assertTrue(doc.fieldsUsingTypeDefinition("Money").isEmpty(), "no field points at it any more");
        assertEquals("NumberType", doc.fieldType("/Store/Price").orElseThrow(), "the concrete type is back");
        assertEquals("NumberType", doc.fieldType("/Store/Cost").orElseThrow());
    }

    @Test
    void extractThenInlineRoundTripsThroughTheKernel() {
        String extracted = EditSession.read(storeJson())
                .extractTypeDefinition("Money", List.of("/Store/Price", "/Store/Cost"))
                .validate().toJson();
        assertTrue(ModelDocument.read(extracted).typeDefinitionIds().contains("Money"));

        String inlined = EditSession.read(extracted).inlineTypeDefinition("Money").validate().toJson();
        assertTrue(ModelDocument.read(inlined).typeDefinitionIds().isEmpty());
    }
}
