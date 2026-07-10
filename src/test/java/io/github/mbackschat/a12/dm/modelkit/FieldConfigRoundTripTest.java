package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4 — {@link DocumentModelDraft#readTypeConfig} (the inverse of {@code writeTypeConfig}, surfaced by {@code
 * field read}/{@code typedef read} via {@link ModelDocument#fieldConfig}) must report every slot it wrote, so a
 * config an agent SET round-trips on read. Found 2026-07-10 (six-component review): three written slots were
 * dropped — string {@code noValueValidation}, enum {@code categories}, and the enum invalid-value {@code
 * errorMessage} — while the Javadoc promised a round-trip. This locks all three back into the read block.
 */
class FieldConfigRoundTripTest {

    private static ModelDocument policy() {
        String json = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("Blob", s -> s.noValueValidation().lineBreaks())
                        .enumField("Coverage", e -> e
                                .value("BASIC").value("PREMIUM")
                                .category("Tier", "entry", "top")
                                .invalidValueMessage("Unknown coverage level.")))
                .build().toJson();
        return ModelDocument.read(json);
    }

    @Test
    void stringNoValueValidationRoundTripsOnRead() {
        ObjectNode blob = policy().fieldConfig("/Policy/Blob").orElseThrow();
        assertTrue(blob.path("string").path("noValueValidation").asBoolean(false),
                () -> "the written string noValueValidation must round-trip on read: " + blob);
    }

    @Test
    void enumCategoriesRoundTripOnRead() {
        ObjectNode coverage = policy().fieldConfig("/Policy/Coverage").orElseThrow();
        JsonNode categories = coverage.path("enum").path("categories");
        assertTrue(categories.isArray() && !categories.isEmpty(),
                () -> "the written enum categories must round-trip on read: " + coverage);
        assertEquals("Tier", categories.get(0).path("name").asText());
        assertEquals("entry", categories.get(0).path("values").get(0).asText());
    }

    @Test
    void enumInvalidValueMessageRoundTripsOnRead() {
        ObjectNode coverage = policy().fieldConfig("/Policy/Coverage").orElseThrow();
        assertEquals("Unknown coverage level.", coverage.path("enum").path("errorMessages").path("en_US").asText(),
                () -> "the written enum invalid-value message must round-trip on read: " + coverage);
    }
}
