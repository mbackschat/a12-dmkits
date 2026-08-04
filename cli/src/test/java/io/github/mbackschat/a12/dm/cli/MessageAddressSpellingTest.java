package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>One message can legitimately publish two address spellings, and {@code schema result} has to say so.</b>
 *
 * <p>A consumer reported the pair as a defect: {@code "field": "Probe/Rows/Key"} beside
 * {@code "referenced": ["/Probe[1]/Rows[0]/Key"]} reads as two conventions on two channels, and a coordinate-free
 * address then looks like a <em>declaration</em> pointer when it actually names concrete row 1 — the opposite of
 * what a cardinality or anchoring claim rests on. The mechanism is neither of those readings: it is ONE contract
 * decided PER ADDRESS. The error field hit a single cell, so it has a canonical A12 string; the operand is the
 * whole column, an address A12 gives no pointer string at all, so it passes through as the interpreter house form.
 *
 * <p>So this test pins both halves of the answer. First the behaviour, on a live run: the same message really does
 * carry an exact canonical address beside a set-valued house-form one, and the exact-operand case really does come
 * back canonical — which is what makes "per channel" the wrong explanation. Then the publication: the envelope
 * schema an agent reads before parsing messages must state the discriminator, because the fact was already
 * documented in {@code schema model eval} and in CLI-SPEC and still could not be reached from where the confusion
 * happens.
 */
class MessageAddressSpellingTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** Two rows sharing a Sku, so an all-rows uniqueness aggregate fires with a whole-column operand. */
    private static Path duplicateRows(Path dir) throws Exception {
        Path file = dir.resolve("duplicate-skus.json");
        Files.writeString(file, """
                { "Order": { "CustomerName": "Acme", "Items": [
                    { "Sku": "LIAB", "Count": 1 },
                    { "Sku": "LIAB", "Count": 9 } ] } }""");
        return file;
    }

    private static JsonNode evalMessage(Path model, Path instance, String condition, String code) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", instance.toString(), "--condition", condition,
                "--field", "/Order/Items/Sku", "--code", code);
        assertEquals(0, res.code(), () -> res.out() + res.err());
        for (JsonNode message : M.readTree(res.out()).path("data").path("messages")) {
            if (code.equals(message.path("code").asText())) {
                return message;
            }
        }
        throw new AssertionError("the candidate did not fire, so there is no message to inspect: " + res.out());
    }

    @Test
    void oneMessageMixesTheTwoSpellingsBecauseTheRuleIsPerAddress(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        JsonNode message = evalMessage(model, duplicateRows(dir),
                "FieldValuesNotUnique(/Order/Items*/Sku)", "SKU_DUPLICATE");

        String field = message.path("field").asText();
        assertEquals("Order/Items/Sku", field,
                () -> "the error field hit one cell, so it is canonical A12 with [1] omitted — repetition 1 of "
                        + "every level, NOT the declaration it happens to spell like: " + message);
        List<String> referenced = M.convertValue(message.path("referenced"),
                M.getTypeFactory().constructCollectionType(List.class, String.class));
        assertEquals(List.of("/Order[1]/Items[0]/Sku"), referenced,
                () -> "the operand is the whole column — a set of cells, which A12 gives no pointer string — so it "
                        + "passes through as the house form beside the canonical field: " + message);
        assertFalse(field.startsWith("/"), "the discriminator the schema tells a consumer to test");
        assertTrue(referenced.get(0).startsWith("/"), "and the other branch of it");
    }

    /**
     * The control that falsifies the "each channel has its own convention" reading: give the same channel an
     * operand that names one cell and it comes back canonical, in the same spelling as {@code field}.
     */
    @Test
    void anExactOperandIsCanonicalOnTheSameChannel(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        JsonNode message = evalMessage(model, duplicateRows(dir),
                "FieldFilled(/Order/Items/Count) And [/Order/Items/Count] > 5", "COUNT_CAP");

        assertEquals("Order/Items[2]/Sku", message.path("field").asText(), message::toString);
        List<String> referenced = M.convertValue(message.path("referenced"),
                M.getTypeFactory().constructCollectionType(List.class, String.class));
        assertEquals(List.of("Order/Items[2]/Count"), referenced,
                () -> "an exact operand is canonical on the very channel that was house-form above, so the "
                        + "spelling is a property of the ADDRESS, not of the channel: " + message);
    }

    /**
     * The publication half. Asserted on the two {@code format} keywords rather than on prose wording: they are the
     * machine-readable marks the runtime schemas actually carry, so this cannot pass on a paraphrase that never
     * names the contract, and cannot fail on a rewording that keeps it.
     */
    @Test
    void schemaResultPublishesTheDiscriminator() throws Exception {
        String description;
        try (InputStream in = getClass().getResourceAsStream("/schema/result.schema.json")) {
            description = M.readTree(in).path("description").asText();
        }
        assertTrue(description.contains("a12-pointer"),
                () -> "`schema result` must name the always-canonical mark: " + description);
        assertTrue(description.contains("a12-message-address"),
                () -> "…and the weaker message-address mark, which is the one that carries two spellings: "
                        + description);
        assertTrue(description.contains("[1]"),
                () -> "…and that canonical form OMITS [1], the fact that decides whether a coordinate-free "
                        + "address is row 1 or a declaration: " + description);

        CliTestSupport.Result served = CliTestSupport.run("schema", "result");
        assertEquals(0, served.code(), served::err);
        assertEquals(description, M.readTree(served.out()).path("description").asText(),
                "the verb serves the same file this test read, so the claim is reachable from the tool");
    }
}
