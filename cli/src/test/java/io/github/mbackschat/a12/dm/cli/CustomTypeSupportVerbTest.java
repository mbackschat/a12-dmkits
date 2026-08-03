package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code --predefined-types} (G30) — the CLI surface for the interpreter's declarative custom-field-type
 * registry. A {@code CustomFieldType} field is reported {@code unsupported} (and so silently NOT validated)
 * unless its type is supplied; with {@code --predefined-types} the format (length/pattern) is enforced, so a
 * bad value fires {@code customFieldTypeInvalid}. Uses the committed {@code payment-customtype} fixture
 * (a {@code CustomFieldType "Iban"} field). Refines the interpreter — the sole runtime eval engine.
 */
class CustomTypeSupportVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A declarative IBAN format: country (2 letters) + check digits (2) + BBAN (11–30 alphanumerics). */
    private static final String IBAN_TYPES = """
            { "Iban": { "minLength": 15, "maxLength": 34, "pattern": "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}" } }""";

    private static Path write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private static boolean fired(String out, String code) throws Exception {
        for (JsonNode c : MAPPER.readTree(out).get("data").get("fired")) {
            if (c.asText().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code data.unsupported[].name} set the engine surfaced (empty if the key is absent). */
    private static String unsupported(String out) throws Exception {
        JsonNode u = MAPPER.readTree(out).get("data").get("unsupported");
        return u == null ? "" : u.toString();
    }

    @Test
    void predefinedTypesValidatesACustomTypedFieldThatWouldOtherwiseBeSkipped(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path bad = write(dir, "bad.json", """
                { "Payment": { "AccountHolder": "Acme", "Iban": "NOT-AN-IBAN" } }""");
        Path types = write(dir, "types.json", IBAN_TYPES);
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", bad.toString(), "--predefined-types", types.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(fired(res.out(), "customFieldTypeInvalid"),
                () -> "a bad IBAN must fire customFieldTypeInvalid once the type is supplied: " + res.out());
    }

    @Test
    void aValidCustomTypedValuePasses(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path good = write(dir, "good.json", """
                { "Payment": { "AccountHolder": "Acme", "Iban": "DE89370400440532013000" } }""");
        Path types = write(dir, "types.json", IBAN_TYPES);
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", good.toString(), "--predefined-types", types.toString());
        assertEquals(0, res.code(), res::err);
        assertFalse(fired(res.out(), "customFieldTypeInvalid"),
                () -> "a valid IBAN must not fire: " + res.out());
    }

    @Test
    void withoutPredefinedTypesTheCustomTypedFieldIsNotValidated(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path bad = write(dir, "bad.json", """
                { "Payment": { "AccountHolder": "Acme", "Iban": "NOT-AN-IBAN" } }""");
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", bad.toString());
        assertEquals(0, res.code(), res::err);
        assertFalse(fired(res.out(), "customFieldTypeInvalid"),
                () -> "without --predefined-types an unregistered custom type is skipped, not fired: " + res.out());
    }

    // ── G29: visible-ignore (data.unsupported) + --strict-custom ──

    @Test
    void evalSurfacesAnUnsupportedCustomTypedCell(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path bad = write(dir, "bad.json", """
                { "Payment": { "AccountHolder": "Acme", "Iban": "NOT-AN-IBAN" } }""");
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", bad.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(unsupported(res.out()).contains("Payment/Iban"),
                () -> "the unregistered custom-typed cell must be surfaced under data.unsupported, not silently skipped: " + res.out());
    }

    @Test
    void predefinedTypesClearsTheUnsupportedSurface(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path good = write(dir, "good.json", """
                { "Payment": { "AccountHolder": "Acme", "Iban": "DE89370400440532013000" } }""");
        Path types = write(dir, "types.json", IBAN_TYPES);
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", good.toString(), "--predefined-types", types.toString());
        assertEquals(0, res.code(), res::err);
        assertEquals("", unsupported(res.out()),
                () -> "once the type is supplied there is nothing unsupported: " + res.out());
    }

    @Test
    void evalSurfacesAnUnsupportedCustomConditionRule(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // /Order/EligibilityCheck uses CustomCondition ExternalEligibility
        Path d = write(dir, "doc.json", """
                { "Order": { "CustomerName": "Acme" } }""");   // filled, so the CustomCondition is reached
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval", "--instance", d.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(unsupported(res.out()).contains("/Order/EligibilityCheck"),
                () -> "a CustomCondition rule the engine can't evaluate must be surfaced, not read as a clean pass: " + res.out());
    }

    @Test
    void strictCustomRefusesWhenSomethingIsUnsupported(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path bad = write(dir, "bad.json", """
                { "Payment": { "AccountHolder": "Acme", "Iban": "NOT-AN-IBAN" } }""");
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", bad.toString(), "--strict-custom");
        assertEquals(2, res.code(), () -> "--strict-custom must refuse (exit 2) on an unsupported construct: " + res.out());
        assertTrue(res.out().contains("--strict-custom"),
                () -> "the refusal must name --strict-custom: " + res.out());
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("DM_INTERPRETER", envelope.path("engine").asText(),
                () -> "a post-evaluation refusal keeps the runtime receipt: " + res.out());
        assertTrue(envelope.hasNonNull("engineVersion") && envelope.hasNonNull("toolVersion"), res::out);
    }

    @Test
    void strictCustomPassesWhenEverythingIsSupported(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path good = write(dir, "good.json", """
                { "Payment": { "AccountHolder": "Acme", "Iban": "DE89370400440532013000" } }""");
        Path types = write(dir, "types.json", IBAN_TYPES);
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", good.toString(), "--predefined-types", types.toString(), "--strict-custom");
        assertEquals(0, res.code(), () -> "--strict-custom passes when nothing is unsupported: " + res.out() + res.err());
    }

    @Test
    void ruleTestReportsAnUnsupportedCustomConditionRule(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path d = write(dir, "doc.json", """
                { "Order": { "CustomerName": "Acme" } }""");
        var res = CliTestSupport.run("-m", model.toString(), "rule", "eval", "/Order/EligibilityCheck",
                "--instance", d.toString());
        assertEquals(0, res.code(), res::err);
        assertEquals("unsupported", MAPPER.readTree(res.out()).get("data").get("verdict").asText(),
                () -> "rule eval reports a CustomCondition rule as 'unsupported', not a crash or a false pass: " + res.out());
    }

    @Test
    void ruleStrictCustomRefusalKeepsTheRuntimeReceipt(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path document = write(dir, "doc.json", """
                { "Order": { "CustomerName": "Acme" } }""");

        var res = CliTestSupport.run("-m", model.toString(), "rule", "eval", "/Order/EligibilityCheck",
                "--instance", document.toString(), "--strict-custom");

        assertEquals(2, res.code(), res::out);
        JsonNode envelope = MAPPER.readTree(res.out());
        assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), res::out);
        assertTrue(envelope.hasNonNull("engineVersion") && envelope.hasNonNull("toolVersion"), res::out);
    }

    @Test
    void aMalformedPredefinedTypesFileFailsCleanly(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.paymentCustomType(dir);
        Path bad = write(dir, "bad.json", """
                { "Payment": { "Iban": "x" } }""");
        Path types = write(dir, "types.json", """
                { "Iban": { "bogusKey": 1 } }""");
        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", bad.toString(), "--predefined-types", types.toString());
        assertNotEquals(0, res.code());
        assertTrue((res.out() + res.err()).contains("unknown key"),
                () -> "an unknown key in --predefined-types must fail with a clear message: " + res.out() + res.err());
    }
}
