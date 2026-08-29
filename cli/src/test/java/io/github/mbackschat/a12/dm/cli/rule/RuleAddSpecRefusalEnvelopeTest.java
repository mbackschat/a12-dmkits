package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * {@code rule add}'s own spec-shape refusals ride the {@code rejected} result envelope — the shape its peer
 * {@code computation add} already answered in, and the one an agent can parse.
 *
 * <p>The tests pin the whole observable contract, not just the message: stdout carries the envelope,
 * {@code outcome} is {@code rejected}, the exit code is 1 (a rejection, not the 2 a plain {@code fail} used to
 * return), and each diagnostic names the spec KEY that carried the mistake in {@code where.arg}. That last
 * assertion is what a wrong-key regression would break while every message-substring check still passed.
 */
class RuleAddSpecRefusalEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A missing required slot is answered by the SCHEMA gate, not by the command's own guard — measured, and
     * pinned here because the layering is what makes that guard a backstop rather than the refusal a caller
     * meets. The gate names every missing key at once; the command's {@code RK_INCOMPLETE_RULE} branch is only
     * reachable if {@code rule-add.schema.json} stops requiring one, so a schema edit that drops a
     * {@code required} entry lands here rather than silently changing which code the caller sees.
     */
    @Test
    void everyMissingRequiredSlotIsNamedByTheSchemaGate(@TempDir Path dir) throws Exception {
        CliTestSupport.Result result = run(dir, """
                { "field": "/Order/Items/Sku",
                  "messages": [ {"locale":"en_US","text":"x"} ] }""");

        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("rejected", envelope.path("outcome").asText(), result::out);
        assertEquals(2, envelope.path("diagnostics").size(), result::out);
        for (JsonNode diagnostic : envelope.path("diagnostics")) {
            assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), result::out);
            assertTrue(diagnostic.path("summary").asText().startsWith("missing required property"), result::out);
        }
    }

    /**
     * {@code messages: []} passes the schema gate — {@code minItems} is declared but not enforced by
     * {@code SpecKeys} — so this one really is the command's own guard, and the empty case would otherwise
     * reach the kernel with no message at all.
     */
    @Test
    void aSpecWithNoMessageIsRefusedAsAnEnvelope(@TempDir Path dir) throws Exception {
        JsonNode diagnostic = refuse(dir, """
                { "field": "/Order/Items/Sku", "condition": "FieldNotFilled(Sku)", "code": "X",
                  "messages": [] }""");

        assertEquals("RK_INCOMPLETE_RULE", diagnostic.path("code").asText());
        assertEquals("messages", diagnostic.path("where").path("arg").asText());
    }

    @Test
    void aNonPathFieldIsRefusedAsAnEnvelope(@TempDir Path dir) throws Exception {
        JsonNode diagnostic = refuse(dir, """
                { "field": "Sku", "condition": "FieldNotFilled(Sku)", "code": "X",
                  "messages": [ {"locale":"en_US","text":"x"} ] }""");

        assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText());
        assertEquals("field", diagnostic.path("where").path("arg").asText());
        assertTrue(diagnostic.path("summary").asText().contains("Sku"),
                "the refused value is echoed, so the caller sees what it read");
    }

    @Test
    void anErrorFieldOutsideTheGroupIsRefusedAsAnEnvelope(@TempDir Path dir) throws Exception {
        JsonNode diagnostic = refuse(dir, """
                { "field": "/Order/Items/Sku", "group": "/Order/Nope",
                  "condition": "FieldNotFilled(Sku)", "code": "X",
                  "messages": [ {"locale":"en_US","text":"x"} ] }""");

        assertEquals("RK_ERROR_FIELD_OUTSIDE_GROUP", diagnostic.path("code").asText());
        assertEquals("field", diagnostic.path("where").path("arg").asText());
        assertEquals("/Order/Items/Sku", diagnostic.path("where").path("path").asText(),
                "this value IS a well-formed entity path, so the model locus is named rather than left unset");
    }

    /** Runs {@code rule add} on a spec expected to be refused, and returns its single diagnostic. */
    private static JsonNode refuse(Path dir, String spec) throws IOException {
        CliTestSupport.Result result = run(dir, spec);
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("rejected", envelope.path("outcome").asText(), result::out);
        assertFalse(envelope.path("ok").asBoolean(), result::out);
        assertEquals(1, envelope.path("diagnostics").size(), result::out);
        return envelope.path("diagnostics").get(0);
    }

    /** Runs {@code rule add} and asserts only what every refusal shares: exit 1, the envelope on stdout. */
    private static CliTestSupport.Result run(Path dir, String spec) throws IOException {
        Path model = CliTestSupport.orderRuled(dir);
        Path specFile = dir.resolve("bad.json");
        Files.writeString(specFile, spec);

        CliTestSupport.Result result = CliTestSupport.run(
                "-m", model.toString(), "rule", "add", specFile.toString());

        assertEquals(1, result.code(), result::err);
        assertFalse(result.out().isBlank(),
                () -> "a spec refusal must be the envelope on stdout, not stderr prose: " + result.err());
        return result;
    }
}
