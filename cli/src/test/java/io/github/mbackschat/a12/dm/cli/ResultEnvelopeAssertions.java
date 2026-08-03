package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Test-only validator for the complete public result frame, including its external diagnostic reference. */
final class ResultEnvelopeAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode RESULT = schema("result");
    private static final JsonNode DIAGNOSTIC = schema("diagnostic");

    private ResultEnvelopeAssertions() {
    }

    static void assertConforms(JsonNode envelope, String context) {
        assertAgainst(envelope, RESULT, context);
        String outcome = envelope.path("outcome").asText();
        if (List.of("preview", "read", "refused", "rejected", "error").contains(outcome)) {
            assertFalse(envelope.path("written").asBoolean(), () -> context + ": " + envelope);
        }
        if ("applied".equals(outcome)) {
            assertTrue(envelope.path("written").asBoolean(), () -> context + ": " + envelope);
        }
        if (envelope.has("engine")) {
            assertTrue(envelope.hasNonNull("engineVersion"), () -> context + " lacks engineVersion: " + envelope);
            assertTrue(envelope.hasNonNull("toolVersion"), () -> context + " lacks toolVersion: " + envelope);
            assertFalse(envelope.has("valid"), () -> context + " runtime observation claims validity: " + envelope);
        }
        if (envelope.has("valid")) {
            assertTrue(envelope.has("verification"), () -> context + " verdict lacks verification: " + envelope);
        }
        if ("batch".equals(envelope.path("target").asText()) && "completed".equals(outcome)) {
            assertEquals(envelope.path("data").path("allSucceeded").asBoolean(), envelope.path("ok").asBoolean(),
                    () -> context + " batch aggregate success drifted: " + envelope);
        }
    }

    private static void assertAgainst(JsonNode value, JsonNode schema, String at) {
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asText();
            assertTrue(ref.endsWith("/diagnostic.schema.json"), () -> at + " has unsupported $ref " + ref);
            assertAgainst(value, DIAGNOSTIC, at);
            return;
        }
        if (schema.has("type")) assertType(value, schema.path("type").asText(), at);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.path("enum")) found |= allowed.equals(value);
            assertTrue(found, () -> at + " value " + value + " is outside enum " + schema.path("enum"));
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                assertTrue(value.has(required.asText()), () -> at + " lacks required key " + required.asText());
            }
            JsonNode properties = schema.path("properties");
            for (Iterator<String> names = value.fieldNames(); names.hasNext();) {
                String name = names.next();
                if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").asBoolean()) {
                    assertTrue(properties.has(name), () -> at + " has undeclared key " + name);
                }
                if (properties.has(name)) assertAgainst(value.path(name), properties.path(name), at + "." + name);
            }
        }
        if (value.isArray() && schema.path("items").isObject()) {
            int index = 0;
            for (JsonNode item : value) assertAgainst(item, schema.path("items"), at + "[" + index++ + "]");
        }
    }

    private static void assertType(JsonNode value, String type, String at) {
        boolean matches = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "null" -> value.isNull();
            default -> throw new AssertionError("unsupported schema type " + type + " at " + at);
        };
        assertTrue(matches, () -> at + " must be " + type + ", got " + value.getNodeType());
    }

    private static JsonNode schema(String name) {
        try (InputStream input = ResultEnvelopeAssertions.class.getResourceAsStream(
                "/schema/" + name + ".schema.json")) {
            if (input == null) throw new IllegalStateException("missing schema " + name);
            return MAPPER.readTree(input);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load schema " + name, e);
        }
    }
}
