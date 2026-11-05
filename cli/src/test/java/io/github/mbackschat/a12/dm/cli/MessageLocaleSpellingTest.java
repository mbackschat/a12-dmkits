package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code --message} spelled {@code en-US} names the model's declared {@code en_US}: A12 reads both as one locale
 * (KERNEL-FINDINGS KF290) and writes it {@code en_US} (KF676). So neither {@code rule check} nor {@code patterns}
 * may refuse it as undeclared, and the stored text sits under {@code en_US}, the spelling A12 itself would write.
 * The refused-spelling control is {@code PatternsUndeclaredMessageLocaleTest}.
 */
class MessageLocaleSpellingTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void ruleCheckAcceptsTheDeclaredLocaleInItsOtherSpelling(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.order(dir);

        CliTestSupport.Result result = CliTestSupport.run("-m", model.toString(), "rule", "check",
                "--field", "/Order/DeliveryDate", "--condition", "FieldNotFilled(/Order/DeliveryDate)",
                "--code", "DELIVERY", "--message", "en-US=Enter a delivery date.");

        assertEquals(0, result.code(), result::out);
        for (JsonNode diagnostic : M.readTree(result.out()).path("diagnostics")) {
            assertFalse("RK_UNDECLARED_MESSAGE_LOCALE".equals(diagnostic.path("code").asText()), result::out);
        }
    }

    @Test
    void patternsStoresTheTextUnderTheDeclaredLocalesKey(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.order(dir);

        CliTestSupport.Result result = CliTestSupport.run("-m", model.toString(), "patterns", "date-order",
                "--arg", "earlier=/Order/OrderDate", "--arg", "later=/Order/DeliveryDate",
                "--message", "en-US=Delivery must not precede the order.", "--apply");

        assertEquals(0, result.code(), result::out);
        String stored = Files.readString(model);
        assertTrue(stored.contains("Delivery must not precede the order."), stored);
        assertFalse(stored.contains("en-US"), stored);
    }
}
