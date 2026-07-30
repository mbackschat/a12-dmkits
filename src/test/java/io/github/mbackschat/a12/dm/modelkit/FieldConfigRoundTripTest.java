package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4 — {@link DocumentModelDraft#readTypeConfig} (the inverse of {@code writeTypeConfig}, surfaced by {@code
 * field read}/{@code typedef read} via {@link ModelDocument#fieldConfigJson}) must report every slot it wrote,
 * so a config an agent SET round-trips on read. Found 2026-07-10 (six-component review): three written slots
 * were dropped — string {@code noValueValidation}, enum {@code categories}, and the enum invalid-value {@code
 * errorMessage} — while the Javadoc promised a round-trip. This locks all three back into the read block,
 * through the Jackson-free JSON-text read-back the CLI consumes (Set W).
 */
class FieldConfigRoundTripTest {

    private static ModelDocument policy() {
        String json = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("Blob", s -> s.noValueValidation().lineBreaks())
                        .numberField("Sequence", n -> n
                                .minLength(1).maxLength(4).maxIntegerDigits(3))
                        .enumField("Coverage", e -> e
                                .value("BASIC").value("PREMIUM")
                                .category("Tier", "entry", "top")
                                .invalidValueMessage("Unknown coverage level.")))
                .build().toJson();
        return ModelDocument.read(json);
    }

    /** The field's config block, read back as JSON text (the public seam) and parsed for the assertions. */
    private static JsonNode config(String fieldPath) {
        String json = policy().fieldConfigJson(fieldPath).orElseThrow();
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable config block: " + json, e);
        }
    }

    @Test
    void stringNoValueValidationRoundTripsOnRead() {
        JsonNode blob = config("/Policy/Blob");
        assertTrue(blob.path("string").path("noValueValidation").asBoolean(false),
                () -> "the written string noValueValidation must round-trip on read: " + blob);
    }

    @Test
    void numberLengthAndIntegerDigitBoundsRoundTripOnRead() {
        JsonNode sequence = config("/Policy/Sequence").path("number");

        assertEquals(1, sequence.path("minLength").asInt(), sequence::toPrettyString);
        assertEquals(4, sequence.path("maxLength").asInt(), sequence::toPrettyString);
        assertEquals(3, sequence.path("maxIntegerDigits").asInt(), sequence::toPrettyString);
    }

    @Test
    void enumCategoriesRoundTripOnRead() {
        JsonNode coverage = config("/Policy/Coverage");
        JsonNode categories = coverage.path("enum").path("categories");
        assertTrue(categories.isArray() && !categories.isEmpty(),
                () -> "the written enum categories must round-trip on read: " + coverage);
        assertEquals("Tier", categories.get(0).path("name").asText());
        assertEquals("entry", categories.get(0).path("values").get(0).asText());
    }

    @Test
    void enumInvalidValueMessageRoundTripsOnRead() {
        JsonNode coverage = config("/Policy/Coverage");
        assertEquals("Unknown coverage level.", coverage.path("enum").path("errorMessages").path("en_US").asText(),
                () -> "the written enum invalid-value message must round-trip on read: " + coverage);
    }
}
