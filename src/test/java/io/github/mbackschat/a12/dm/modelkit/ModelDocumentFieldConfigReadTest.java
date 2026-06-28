package io.github.mbackschat.a12.dm.modelkit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed field-config read-back accessors on {@link ModelDocument} — the peers of {@link
 * ModelDocument#enumValues} that complete the read-back surface (DEV-API-GAPS §2). A consumer (the
 * JSON-Schema exporter) reads a field's declared constraints — pattern, length, value bounds, scale —
 * without navigating the raw {@code fieldConfig} schema-block or knowing its quirks (number bounds are
 * text under a {@code number} sub-block; lengths/pattern under {@code string}).
 */
class ModelDocumentFieldConfigReadTest {

    private static ModelDocument permit() {
        String json = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p
                        .stringField("Code", s -> s.minLength(3).maxLength(12)
                                .pattern("[A-Z][A-Z0-9]+", "Code must be a letter then letters or digits."))
                        .numberField("Fee", n -> n.fractionDigits(2).min("0").max("10000"))
                        .field("Name", FieldKind.STRING))
                .build().toJson();
        return ModelDocument.read(json);
    }

    @Test
    void readsStringPatternAndLengths() {
        ModelDocument doc = permit();
        assertEquals(Optional.of("[A-Z][A-Z0-9]+"), doc.fieldPattern("/Permit/Code"));
        assertEquals(OptionalInt.of(3), doc.fieldMinLength("/Permit/Code"));
        assertEquals(OptionalInt.of(12), doc.fieldMaxLength("/Permit/Code"));
    }

    @Test
    void readsNumberBoundsAndScale() {
        ModelDocument doc = permit();
        assertEquals(Optional.of(new BigDecimal("0")), doc.fieldMinValue("/Permit/Fee"));
        assertEquals(Optional.of(new BigDecimal("10000")), doc.fieldMaxValue("/Permit/Fee"));
        assertEquals(OptionalInt.of(2), doc.fieldMaxFractionalDigits("/Permit/Fee"));
    }

    private static ModelDocument typedefPermit() {
        String json = DocumentModelDraft.named("permit").locale("en_US")
                .typeDef("CodeType", t -> t.string(s -> s.minLength(3).maxLength(12).pattern("[A-Z]+", "letters only")))
                .typeDef("AmountType", t -> t.number(n -> n.fractionDigits(2).min("0").max("999")))
                .rootGroup("Permit", p -> p
                        .typedefField("Code", "CodeType", Requiredness.OPTIONAL)
                        .typedefField("Amount", "AmountType", Requiredness.OPTIONAL))
                .build().toJson();
        return ModelDocument.read(json);
    }

    @Test
    void readsTypeDefinitionConstraintsAsTheTypedPeerOfFieldAccessors() {
        ModelDocument doc = typedefPermit();
        assertEquals(Optional.of("[A-Z]+"), doc.typeDefinitionPattern("CodeType"));
        assertEquals(OptionalInt.of(3), doc.typeDefinitionMinLength("CodeType"));
        assertEquals(OptionalInt.of(12), doc.typeDefinitionMaxLength("CodeType"));
        assertEquals(Optional.of(new BigDecimal("0")), doc.typeDefinitionMinValue("AmountType"));
        assertEquals(Optional.of(new BigDecimal("999")), doc.typeDefinitionMaxValue("AmountType"));
        assertEquals(OptionalInt.of(2), doc.typeDefinitionMaxFractionalDigits("AmountType"));
    }

    @Test
    void absentConstraintsAndMissingFieldsReadAsEmpty() {
        ModelDocument doc = permit();
        // a bare STRING carries no surfaceable config
        assertTrue(doc.fieldPattern("/Permit/Name").isEmpty());
        assertTrue(doc.fieldMinLength("/Permit/Name").isEmpty());
        // cross-kind reads are empty, not wrong: a STRING has no number scale; a NUMBER has no string pattern
        assertTrue(doc.fieldMaxFractionalDigits("/Permit/Name").isEmpty());
        assertTrue(doc.fieldPattern("/Permit/Fee").isEmpty());
        assertTrue(doc.fieldMinValue("/Permit/Code").isEmpty());
        // a non-existent field is empty, never a throw
        assertTrue(doc.fieldPattern("/Permit/Nope").isEmpty());
        assertTrue(doc.fieldMaxFractionalDigits("/Permit/Nope").isEmpty());
    }
}
