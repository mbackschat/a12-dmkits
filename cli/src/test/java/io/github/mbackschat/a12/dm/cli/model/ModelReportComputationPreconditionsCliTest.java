package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * MODEL-REVIEW — {@code model report}'s computation catalog emits the preconditions, in <b>parity</b> with
 * {@code computation read} (Set RP task 2). The report used to omit them, so a reviewer reading the whole
 * model couldn't see <em>when</em> a computed field is filled. The lock: for every computation, the report's
 * {@code commonPrecondition} + {@code alternatives} are byte-equal to what {@code computation read} emits.
 */
class ModelReportComputationPreconditionsCliTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void reportEmitsComputationPreconditions(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderPricingComputations(dir);
        JsonNode report = data(CliTestSupport.run("-m", model.toString(), "model", "report"));

        JsonNode shipping = computation(report, "/Order/ShippingFee");
        assertTrue(shipping.hasNonNull("commonPrecondition"),
                () -> "the report emits the computation's common precondition, got: " + shipping);
        assertEquals(2, shipping.path("alternatives").size(), "ShippingFee has two preconditioned alternatives");

        JsonNode discount = computation(report, "/Order/Discount");
        assertFalse(discount.has("commonPrecondition"), "Discount has no common precondition — the key is omitted");
        assertTrue(discount.path("alternatives").size() == 2
                        && all(discount.path("alternatives"), a -> a.hasNonNull("precondition") && a.hasNonNull("operation")),
                () -> "each Discount alternative carries its precondition + operation, got: " + discount);
    }

    @Test
    void reportPreconditionsMatchComputationRead(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderPricingComputations(dir);
        JsonNode report = data(CliTestSupport.run("-m", model.toString(), "model", "report"));

        for (JsonNode entry : report.path("computations")) {
            String name = entry.path("computation").asText();
            JsonNode read = data(CliTestSupport.run("-m", model.toString(), "computation", "read", name));
            assertEquals(read.path("commonPrecondition"), entry.path("commonPrecondition"),
                    () -> "commonPrecondition of " + name + " must match `computation read`");
            assertEquals(read.path("alternatives"), entry.path("alternatives"),
                    () -> "alternatives of " + name + " must match `computation read`");
        }
    }

    private static JsonNode data(CliTestSupport.Result res) throws Exception {
        assertEquals(0, res.code(), () -> "verb exited non-zero: " + res.err());
        return M.readTree(res.out()).path("data");
    }

    private static JsonNode computation(JsonNode reportData, String computedField) {
        for (JsonNode c : reportData.path("computations")) {
            if (computedField.equals(c.path("computedField").asText())) {
                return c;
            }
        }
        throw new AssertionError("no computation computing " + computedField + " in: " + reportData.path("computations"));
    }

    private static boolean all(JsonNode array, java.util.function.Predicate<JsonNode> p) {
        for (JsonNode n : array) {
            if (!p.test(n)) {
                return false;
            }
        }
        return true;
    }
}
