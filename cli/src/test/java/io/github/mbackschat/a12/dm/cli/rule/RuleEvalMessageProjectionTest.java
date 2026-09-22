package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * A fired {@code rule eval} publishes the firing MESSAGE's channels, not only its rendered line — reported by a
 * consumer who could observe {@code fired}/{@code verdict}/the code but not the message {@code type}.
 *
 * <p><b>Why the type is not presentation.</b> For {@code FieldValuesNotUnique} it is the only observable that
 * separates a filtered from an unfiltered firing <em>on identical data</em>: a reached {@code Having} on the
 * starred operand escalates the firing to {@code OMISSION_ERROR} where the unfiltered form is
 * {@code VALUE_ERROR}. Measuring it therefore used to require persisting the rule and running {@code model eval}
 * — exactly the model-dirtying cycle candidate mode exists to avoid.
 *
 * <p>The kernel leg is asserted too ({@link CliTestSupport#runKernel}): the type and the operand addresses are
 * kernel facts, so a stored rule's projection is held to the kernel's own message rather than only to itself.
 */
class RuleEvalMessageProjectionTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The unfiltered uniqueness candidate over the whole Sku column. */
    private static final String UNFILTERED = "FieldValuesNotUnique(/Order/Items*/Sku)";

    /** The same predicate, one reached filter apart — every row has a Count, so nothing is dropped. */
    private static final String FILTERED =
            "FieldValuesNotUnique(/Order/Items*/Sku Having [/Order/Items/Count] > 0)";

    /** Two Items rows sharing a Sku — the duplicate the predicate reports. */
    private static Path duplicateRows(Path dir) throws Exception {
        Path file = dir.resolve("duplicate-skus.json");
        Files.writeString(file, """
                { "Order": { "CustomerName": "Acme", "Items": [
                    { "Sku": "LIAB", "Count": 1 },
                    { "Sku": "LIAB", "Count": 2 } ] } }""");
        return file;
    }

    /** One address channel as an order-independent set — see the kernel-parity test for why order is not matched. */
    private static Set<String> addresses(JsonNode array) {
        Set<String> out = new java.util.TreeSet<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static JsonNode evalCandidate(Path model, Path instance, String condition) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "--condition", condition, "--field", "/Order/Items/Sku", "--code", "SKU_DUPLICATE",
                "--instance", instance.toString());
        assertEquals(0, res.code(), () -> res.out() + res.err());
        return M.readTree(res.out()).path("data");
    }

    @Test
    void aFiredCandidateReportsItsMessageType(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        JsonNode data = evalCandidate(model, duplicateRows(dir), UNFILTERED);

        assertEquals("fired", data.path("verdict").asText(), data::toString);
        assertEquals("VALUE_ERROR", data.path("type").asText(), data::toString);
        assertEquals("SKU_DUPLICATE", data.path("code").asText(), data::toString);
        assertTrue(data.path("referenced").isArray() && !data.path("referenced").isEmpty(),
                () -> "the operands the candidate read: " + data);
        assertEquals("ERROR", data.path("severity").asText(), data::toString);
    }

    /**
     * The acceptance pair: identical model, identical data, one filter apart. If only one of these two runs is
     * observable the verb cannot answer the question it exists for.
     */
    @Test
    void aReachedHavingFlipsTheReportedTypeToOmission(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = duplicateRows(dir);

        JsonNode unfiltered = evalCandidate(model, instance, UNFILTERED);
        JsonNode filtered = evalCandidate(model, instance, FILTERED);

        assertEquals("fired", unfiltered.path("verdict").asText(), unfiltered::toString);
        assertEquals("fired", filtered.path("verdict").asText(), filtered::toString);
        assertEquals("VALUE_ERROR", unfiltered.path("type").asText(), unfiltered::toString);
        assertEquals("OMISSION_ERROR", filtered.path("type").asText(),
                () -> "a reached Having escalates the firing to an omission error: " + filtered);
    }

    @Test
    void aStoredRuleProjectsTheKernelsOwnMessageChannels(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path instance = dir.resolve("violation.json");
        Files.writeString(instance,
                "{ \"Order\": { \"OrderDate\": \"2026-01-10\", \"DeliveryDate\": \"2026-01-01\" } }");

        CliTestSupport.Result interpreter = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "/Order/DeliveryNotBeforeOrder", "--instance", instance.toString());
        CliTestSupport.Result kernel = CliTestSupport.runKernel("-m", model.toString(), "rule", "eval",
                "/Order/DeliveryNotBeforeOrder", "--instance", instance.toString());
        assertEquals(0, interpreter.code(), interpreter::err);
        assertEquals(0, kernel.code(), kernel::err);

        JsonNode interpreted = M.readTree(interpreter.out()).path("data");
        JsonNode oracle = M.readTree(kernel.out()).path("data");
        for (String channel : new String[]{"code", "field", "severity", "type", "message"}) {
            assertEquals(oracle.path(channel), interpreted.path(channel),
                    () -> "channel '" + channel + "' must match the kernel's own message: interpreter="
                            + interpreted + " kernel=" + oracle);
        }
        // The operand LIST matches as a set, which is how the whole `:adapter` differential estate compares this
        // channel (`referenced().toSet()`/`.sorted()`): the kernel's enumeration order is not a matched contract,
        // so asserting it here would invent a promise no other lock makes.
        assertEquals(addresses(oracle.path("referenced")), addresses(interpreted.path("referenced")),
                () -> "the operands the kernel says the rule read: interpreter=" + interpreted + " kernel=" + oracle);
        assertEquals("VALUE_ERROR", interpreted.path("type").asText(), interpreted::toString);
    }

    @Test
    void anUnfiredVerdictPublishesNoMessageChannels(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path clean = dir.resolve("clean.json");
        Files.writeString(clean,
                "{ \"Order\": { \"OrderDate\": \"2026-01-01\", \"DeliveryDate\": \"2026-01-10\" } }");

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(), "rule", "eval",
                "/Order/DeliveryNotBeforeOrder", "--instance", clean.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode data = M.readTree(res.out()).path("data");

        assertEquals("passed", data.path("verdict").asText(), data::toString);
        for (String channel : new String[]{"code", "field", "severity", "type", "message", "referenced"}) {
            assertFalse(data.has(channel),
                    () -> "nothing fired, so '" + channel + "' would be a fabricated observation: " + data);
        }
    }
}
