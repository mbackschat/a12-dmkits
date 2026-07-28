package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code --instance} reader must adopt A12's JSON I/O behaviour exactly, which for numbers means the
 * authored scale survives verbatim. A native JSON number is legal A12 (the Kernel's writer emits
 * {@code "Grams": 250} unquoted), and its reader stringifies with {@code asText()} over a mapper deliberately
 * configured for {@code BigDecimal} — so a DEFAULT Jackson mapper here would turn {@code 250.00} into
 * {@code "250.0"} and destroy the scale the formal check and every comparison read.
 *
 * <p>These cases fail on a default {@code ObjectMapper} and pass on the Kernel-configured one; integer-only
 * fixtures cannot see either failure, which is why the scale and precision cases are explicit.
 */
class DocInstanceNumberFidelityTest {

    @TempDir
    Path directory;

    private Map<String, Object> read(String json) throws IOException {
        Path file = directory.resolve("instance.json");
        Files.writeString(file, json);
        return DocInstance.readFields(file.toString());
    }

    @SuppressWarnings("unchecked")
    private static String valueIn(Map<String, Object> fields, String group, String field) {
        Object value = ((Map<String, Object>) fields.get(group)).get(field);
        // The document walker stores exactly this text; see InterpreterModelLoader.walkInstance.
        return value == null ? null : value.toString();
    }

    @Test
    void aNativeNumberKeepsItsAuthoredTrailingZeros() throws IOException {
        Map<String, Object> fields = read("{\"Order\":{\"Amount\":250.00,\"Rate\":5.50,\"Count\":7}}");

        assertEquals("250.00", valueIn(fields, "Order", "Amount"));
        assertEquals("5.50", valueIn(fields, "Order", "Rate"));
        assertEquals("7", valueIn(fields, "Order", "Count"));
    }

    @Test
    void aDecimalBeyondDoublePrecisionSurvivesIntact() throws IOException {
        Map<String, Object> fields = read("{\"Order\":{\"Amount\":1234567890123456789012.5}}");

        assertEquals("1234567890123456789012.5", valueIn(fields, "Order", "Amount"));
    }

    @Test
    void aQuotedNumberIsUnchangedSoBothSpellingsAgree() throws IOException {
        Map<String, Object> quoted = read("{\"Order\":{\"Amount\":\"250.00\"}}");
        Map<String, Object> native0 = read("{\"Order\":{\"Amount\":250.00}}");

        assertEquals("250.00", valueIn(quoted, "Order", "Amount"));
        assertEquals(valueIn(quoted, "Order", "Amount"), valueIn(native0, "Order", "Amount"));
    }

    @Test
    void commentsAreAcceptedBecauseA12AcceptsThem() throws IOException {
        Map<String, Object> fields = read("""
                {
                  // the Kernel's document mapper enables ALLOW_COMMENTS
                  "Order": {"Amount": 1.10 /* inline */}
                }
                """);

        assertEquals("1.10", valueIn(fields, "Order", "Amount"));
    }

    @Test
    void aNativeBooleanAndNullKeepTheirMeaning() throws IOException {
        Map<String, Object> fields = read("{\"Order\":{\"Express\":true,\"Note\":null}}");

        assertEquals("true", valueIn(fields, "Order", "Express"));
        assertEquals(null, valueIn(fields, "Order", "Note"));
    }

    @Test
    void repeatableRowsStayOrderedAndKeepTheirScales() throws IOException {
        Map<String, Object> fields = read("{\"Order\":{\"Items\":[{\"Qty\":1.0},{\"Qty\":2.50}]}}");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) ((Map<String, Object>) fields.get("Order")).get("Items");
        assertEquals(2, rows.size());
        assertEquals("1.0", rows.get(0).get("Qty").toString());
        assertEquals("2.50", rows.get(1).get("Qty").toString());
    }
}
