package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code --custom-field-types-js <dir>} (G31, field types) — an imperative custom field type (a checksum) is
 * validated by the project's own {@code <Name>.js} via the same persistent Node worker, instead of being reported
 * {@code unsupported}. Uses the committed {@code payment-customtype} fixture (a {@code CustomFieldType "Iban"}
 * field). The node-requiring cases SKIP where {@code node} is absent; the conflict case needs no node.
 */
class CustomFieldTypeJsVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String IBAN_VALIDATE = """
            export function validate(value) { return value.startsWith('DE') && value.length >= 15; }
            """;

    private static Path jsDir(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve("Iban.js"), body);
        return dir;
    }

    private static Path doc(Path dir, String iban) throws Exception {
        Path d = dir.resolve("doc-" + iban.hashCode() + ".json");
        Files.writeString(d, "{ \"fields\": { \"Payment\": { \"Iban\": \"" + iban + "\" } } }");
        return d;
    }

    private static boolean fired(String out, String code) throws Exception {
        for (JsonNode c : MAPPER.readTree(out).get("data").get("fired")) {
            if (c.asText().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static String unsupported(String out) throws Exception {
        JsonNode u = MAPPER.readTree(out).get("data").get("unsupported");
        return u == null ? "" : u.toString();
    }

    @Test
    void jsFieldTypeValidatesACustomTypedCell(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = CliTestSupport.paymentCustomType(dir);
        Path js = jsDir(dir, IBAN_VALIDATE);
        var bad = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "NOT-AN-IBAN").toString(), "--custom-field-types-js", js.toString());
        assertEquals(0, bad.code(), bad::err);
        assertTrue(fired(bad.out(), "customFieldTypeInvalid"),
                () -> "a bad IBAN must fire customFieldTypeInvalid via the JS validator: " + bad.out());

        var ok = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "DE89370400440532013000").toString(), "--custom-field-types-js", js.toString());
        assertEquals(0, ok.code(), ok::err);
        assertFalse(fired(ok.out(), "customFieldTypeInvalid"), () -> "a valid IBAN must pass: " + ok.out());
        assertEquals("", unsupported(ok.out()), () -> "a registered JS type is consulted, not unsupported: " + ok.out());
    }

    @Test
    void jsFieldTypeSurfacesItsProjectErrorKey(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = CliTestSupport.paymentCustomType(dir);
        // A validator returning { valid, errorKey } carries its PROJECT-SPECIFIC code to the cell (IF150), instead
        // of the fixed customFieldTypeInvalid — matching the kernel's ICustomFieldValidator getErrorKey().
        Path js = jsDir(dir, "export function validate(value) { "
                + "return value.startsWith('DE') ? { valid: true } : { valid: false, errorKey: 'IBAN_BAD' }; }\n");
        var bad = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "NOT-AN-IBAN").toString(), "--custom-field-types-js", js.toString());
        assertEquals(0, bad.code(), bad::err);
        assertTrue(fired(bad.out(), "IBAN_BAD"),
                () -> "a JS validator's project error key must reach the cell: " + bad.out());
        assertFalse(fired(bad.out(), "customFieldTypeInvalid"),
                () -> "the fixed constant is not used when the JS validator supplies a key: " + bad.out());
    }

    @Test
    void aThrowingJsFieldTypeDegradesToUnsupported(@TempDir Path dir) throws Exception {
        assumeTrue(nodeAvailable(), "node not on PATH");
        Path model = CliTestSupport.paymentCustomType(dir);
        Path js = jsDir(dir, "export function validate() { throw new Error('boom'); }\n");
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc(dir, "DE89370400440532013000").toString(), "--custom-field-types-js", js.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(unsupported(res.out()).contains("/Payment[1]/Iban"),
                () -> "a JS validator that throws must degrade the cell to unsupported, not crash the run: " + res.out());
        assertFalse(fired(res.out(), "customFieldTypeInvalid"),
                () -> "a non-evaluable cell is unsupported, not a (false) format error: " + res.out());
    }
}
