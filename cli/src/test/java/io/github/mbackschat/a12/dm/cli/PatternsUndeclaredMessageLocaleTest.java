package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code patterns --message} for a locale the model does not declare is refused as {@code rule check} refuses it
 * ({@code RK_UNDECLARED_MESSAGE_LOCALE}), never dropped. The scaffold writes one message per declared locale, so the
 * caller's text used to vanish while the verdict and the write reported success over the placeholder. The control
 * gives the declared locale, whose text is what gets stored.
 */
class PatternsUndeclaredMessageLocaleTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static CliTestSupport.Result scaffold(Path model, String message) {
        return CliTestSupport.run("-m", model.toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate",
                "--message", message, "--apply");
    }

    @Test
    void anUndeclaredMessageLocaleIsRefusedAndNothingIsWritten(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.order(dir);
        String before = Files.readString(model);

        CliTestSupport.Result result = scaffold(model, "en_GB=Delivery must not precede the order.");

        JsonNode envelope = M.readTree(result.out());
        assertEquals(2, result.code(), result::out);
        assertEquals("refused", envelope.path("outcome").asText(), result::out);
        JsonNode diagnostic = envelope.path("diagnostics").path(0);
        assertEquals("RK_UNDECLARED_MESSAGE_LOCALE", diagnostic.path("code").asText(), result::out);
        assertEquals("--message", diagnostic.path("where").path("arg").asText(), result::out);
        assertTrue(diagnostic.path("summary").asText().contains("en_GB"), result::out);
        assertEquals(before, Files.readString(model));
    }

    @Test
    void aDeclaredMessageLocaleIsStoredAsGiven(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.order(dir);

        CliTestSupport.Result result = scaffold(model, "en_US=Delivery must not precede the order.");

        assertEquals(0, result.code(), result::out);
        assertTrue(Files.readString(model).contains("Delivery must not precede the order."));
    }
}
