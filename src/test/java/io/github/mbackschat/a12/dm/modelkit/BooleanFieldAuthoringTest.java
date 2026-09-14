package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Authoring the two boolean kinds, and preserving the custom true/false values A12 stores on them.
 *
 * <p>Closes DEV-API-GAPS DG55: the draft could reach {@code BooleanType} and {@code ConfirmType} only through
 * the untyped {@code addField(name, FieldKind)} route, and both specs were empty records, so a model read in
 * with {@code notInDCustomTrueValue} / {@code notInDCustomFalseValue} wrote back without them.
 *
 * <p>The round-trip assertion is the one that matters: a member the WRITER emits but the READER drops, or the
 * reverse, is invisible to an authoring test that only inspects the JSON it just produced.
 */
class BooleanFieldAuthoringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fieldTypeOf(String json, String field) {
        try {
            JsonNode root = MAPPER.readTree(json);
            for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if (field.equals(element.path("name").asText())) {
                    return element.path("Field").path("fieldType");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException("no such field: " + field);
    }

    private static String subscription() {
        // tag::boolean-stored-values[]
        DocumentModelDraft draft = DocumentModelDraft.named("subscription").locale("en_US")
                .rootGroup("Subscription", g -> g
                        .stringField("Reference", s -> { })
                        .booleanField("Paperless", b -> { })
                        .booleanField("MarketingOptIn", b -> b.trueValue("J").falseValue("N"))
                        .confirmField("TermsAccepted", c -> { })
                        .confirmField("AutoRenew", c -> c.trueValue("Y").falseValue("N")))
                .build();
        // end::boolean-stored-values[]
        return draft.toJson();
    }

    @Test
    void bothBooleanKindsAreAuthorableThroughTheTypedBuilder() {
        String json = subscription();

        assertEquals("BooleanType", fieldTypeOf(json, "Paperless").path("type").asText());
        assertEquals("ConfirmType", fieldTypeOf(json, "TermsAccepted").path("type").asText());
    }

    @Test
    void aBareBooleanOrConfirmWritesNoCustomValues() {
        String json = subscription();

        assertTrue(fieldTypeOf(json, "Paperless").path("BooleanType").path("notInDCustomTrueValue").isMissingNode(),
                "a bare boolean must not invent a custom value");
        assertTrue(fieldTypeOf(json, "TermsAccepted").path("ConfirmType").path("notInDCustomFalseValue").isMissingNode(),
                "a bare confirm must not invent a custom value");
    }

    @Test
    void theCustomValuesAreWrittenOnBothKinds() {
        String json = subscription();

        assertEquals("J", fieldTypeOf(json, "MarketingOptIn").path("BooleanType").path("notInDCustomTrueValue").asText());
        assertEquals("N", fieldTypeOf(json, "MarketingOptIn").path("BooleanType").path("notInDCustomFalseValue").asText());
        assertEquals("Y", fieldTypeOf(json, "AutoRenew").path("ConfirmType").path("notInDCustomTrueValue").asText());
        assertEquals("N", fieldTypeOf(json, "AutoRenew").path("ConfirmType").path("notInDCustomFalseValue").asText());
    }

    /**
     * Read the authored model back and write it again — the half an authoring-only test cannot see.
     *
     * <p>A member the writer emits but the reader drops round-trips to nothing, and the authoring assertions
     * above stay green throughout: that asymmetry is what DG55 recorded and what this row locks.
     */
    @Test
    void theCustomValuesSurviveAReadAndRewrite() {
        String rewritten = ModelDocument.read(subscription()).toJson();

        assertEquals("J", fieldTypeOf(rewritten, "MarketingOptIn").path("BooleanType").path("notInDCustomTrueValue").asText());
        assertEquals("N", fieldTypeOf(rewritten, "AutoRenew").path("ConfirmType").path("notInDCustomFalseValue").asText());
        assertEquals("ConfirmType", fieldTypeOf(rewritten, "TermsAccepted").path("type").asText());
    }
}
