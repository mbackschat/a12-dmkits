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

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The {@code eval-doc} verb — run the real kernel's runtime validation over a document INSTANCE and report
 * which rules fire on the data (the runtime counterpart of {@code check}'s static accept/reject). Uses the
 * committed {@code order-ruled} fixture (rules DELIVERY_BEFORE_ORDER / POSTAL_CODE_MALFORMED) and the
 * candidate path (inject-and-run, not persisted).
 */
class EvalDocVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path doc(Path dir, String json) throws Exception {
        Path f = dir.resolve("doc-" + Integer.toHexString(json.hashCode()) + ".json");
        Files.writeString(f, json);
        return f;
    }

    private static boolean firedContains(String out, String code) throws Exception {
        for (JsonNode c : MAPPER.readTree(out).get("data").get("fired")) {
            if (c.asText().equals(code)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void firesWhenDeliveryIsBeforeOrder(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path d = doc(dir, """
                { "fields": { "Order": { "OrderDate": "2024-02-01", "DeliveryDate": "2024-01-01" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString());
        assertEquals(0, res.code(), res::err);
        assertTrue(firedContains(res.out(), "DELIVERY_BEFORE_ORDER"),
                () -> "delivery before order fires the rule: " + res.out());
        // the firing message attributes to the rule (rule path present, the formal-error discriminator)
        assertTrue(MAPPER.readTree(res.out()).get("data").get("messages").toString().contains("/Order/DeliveryNotBeforeOrder"),
                () -> "the message names its rule: " + res.out());
    }

    @Test
    void doesNotFireWhenDeliveryIsAfterOrder(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path d = doc(dir, """
                { "fields": { "Order": { "OrderDate": "2024-01-01", "DeliveryDate": "2024-02-01" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString());
        assertEquals(0, res.code(), res::err);
        assertFalse(firedContains(res.out(), "DELIVERY_BEFORE_ORDER"),
                () -> "a compliant delivery date does not fire: " + res.out());
    }

    @Test
    void patternRuleFiresOnAMalformedPostalCodeOnly(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path bad = doc(dir, """
                { "fields": { "Order": { "BillingAddress": { "PostalCode": "1234" } } } }""");
        Path ok = doc(dir, """
                { "fields": { "Order": { "BillingAddress": { "PostalCode": "12345" } } } }""");
        assertTrue(firedContains(CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", bad.toString()).out(),
                "POSTAL_CODE_MALFORMED"), "a 4-digit postal code violates the 5-digit pattern");
        assertFalse(firedContains(CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", ok.toString()).out(),
                "POSTAL_CODE_MALFORMED"), "a 5-digit postal code complies");
    }

    @Test
    void candidateRuleReportsWhetherItFired(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path over = doc(dir, """
                { "fields": { "Order": { "Quantity": "150" } } }""");
        Path under = doc(dir, """
                { "fields": { "Order": { "Quantity": "50" } } }""");
        var fired = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", over.toString(),
                "--field", "/Order/Quantity", "--condition", "[Quantity] > 100", "--code", "QTY_CAP");
        assertEquals(0, fired.code(), fired::err);
        assertTrue(MAPPER.readTree(fired.out()).get("data").get("rule").get("fired").asBoolean(),
                () -> "the candidate fires on Quantity 150: " + fired.out());
        var notFired = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", under.toString(),
                "--field", "/Order/Quantity", "--condition", "[Quantity] > 100", "--code", "QTY_CAP");
        assertFalse(MAPPER.readTree(notFired.out()).get("data").get("rule").get("fired").asBoolean(),
                () -> "the candidate does not fire on Quantity 50: " + notFired.out());
    }

    @Test
    void targetedExistingRuleReportsFired(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path d = doc(dir, """
                { "fields": { "Order": { "OrderDate": "2024-02-01", "DeliveryDate": "2024-01-01" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString(),
                "--rule", "/Order/DeliveryNotBeforeOrder");
        assertEquals(0, res.code(), res::err);
        JsonNode rule = MAPPER.readTree(res.out()).get("data").get("rule");
        assertEquals("/Order/DeliveryNotBeforeOrder", rule.get("name").asText());
        assertTrue(rule.get("fired").asBoolean(), () -> "the named rule fired: " + res.out());
    }

    @Test
    void multiRowAttributesMessagesToTheOffendingRow(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        // two Items rows (Sku supplied so the mandatory-field check is quiet); only row 2 (Count 80) violates "> 50"
        Path d = doc(dir, """
                { "fields": { "Order": { "Items": [ { "Sku": "A", "Count": "10" }, { "Sku": "B", "Count": "80" } ] } } }""");
        // a per-row rule reaching THROUGH the repeatable Items is scoped to the PARENT group (/Order),
        // not Items itself (a rule whose group IS the repeatable group is rejected MVK_INVALID_ENTITY)
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString(),
                "--field", "/Order/Items/Count", "--group", "/Order",
                "--condition", "FieldFilled(Items/Count) And [Items/Count] > 50", "--code", "COUNT_CAP");
        assertEquals(0, res.code(), res::err);
        // look only at the candidate's own messages — it fires on row 2 ([1, 2, 1]), not row 1 ([1, 1, 1])
        StringBuilder countCap = new StringBuilder();
        for (JsonNode m : MAPPER.readTree(res.out()).get("data").get("messages")) {
            if (m.get("code").asText().equals("COUNT_CAP")) {
                countCap.append(m.get("field").asText()).append('\n');
            }
        }
        assertTrue(countCap.toString().contains("[1, 2, 1]"), () -> "the candidate fires on row 2: " + res.out());
        assertFalse(countCap.toString().contains("[1, 1, 1]"), () -> "the candidate does not fire on row 1: " + res.out());
    }

    @Test
    void aFormalErrorIsReportedAsAKernelCheck(@TempDir Path dir) throws Exception {
        // Quantity is a scale-0 number; "1.5" has a decimal separator → a kernel FORMAL error. It is
        // distinguishable from an author-rule violation: a formal check's `rule` is the sentinel
        // "formalePruefung" (no leading '/'), where an author rule's is its slash-path.
        Path model = CliTestSupport.orderRuled(dir);
        Path d = doc(dir, """
                { "fields": { "Order": { "Quantity": "1.5" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode messages = MAPPER.readTree(res.out()).get("data").get("messages");
        assertTrue(messages.size() > 0, () -> "a malformed value raises a kernel message: " + res.out());
        assertTrue(java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                        .anyMatch(m -> m.has("rule") && !m.get("rule").asText().startsWith("/")),
                () -> "a formal/kernel check is reported with a non-author rule path: " + res.out());
    }

    @Test
    void ruleAndConditionAreMutuallyExclusive(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path d = doc(dir, """
                { "fields": { "Order": { "Quantity": "1" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString(),
                "--rule", "/Order/DeliveryNotBeforeOrder", "--condition", "[Quantity] > 0", "--field", "/Order/Quantity");
        assertNotEquals(0, res.code(), "--rule and a candidate are mutually exclusive");
    }

    @Test
    void unknownRuleFails(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path d = doc(dir, """
                { "fields": { "Order": { "Quantity": "1" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString(), "--rule", "/Order/Nope");
        assertNotEquals(0, res.code(), "an unknown rule is an error");
        assertTrue(res.err().contains("/Order/Nope"), () -> "names the bad rule: " + res.err());
    }

    @Test
    void unknownPathInTheDocumentFails(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path d = doc(dir, """
                { "fields": { "Order": { "Nope": "x" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString());
        assertNotEquals(0, res.code(), "an unknown path in the document is an error");
        assertTrue(res.err().contains("/Order/Nope"), () -> "names the bad path: " + res.err());
    }

    @Test
    void respectsIncludeDirForAMultiFileModel(@TempDir Path dir) throws Exception {
        // a storefront that includes a pricelist living in a separate lib/ tree → needs -w/--workspace to load
        String pricelist = DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p.numberField("ListPrice", n -> n.fractionDigits(2)))
                .build().toJson();
        String storefront = DocumentModelDraft.named("storefront").locale("en_US")
                .include("pricelist", "pricelist")
                .rootGroup("Storefront", s -> s.field("StoreName", FieldKind.STRING).mount("Prices", "pricelist"))
                .build().toJson();
        Path lib = Files.createDirectories(dir.resolve("lib"));
        Files.writeString(lib.resolve("pricelist.dm.json"), pricelist);
        Path apps = Files.createDirectories(dir.resolve("apps"));
        Path model = apps.resolve("storefront.dm.json");
        Files.writeString(model, storefront);
        Path d = doc(dir, """
                { "fields": { "Storefront": { "StoreName": "Acme" } } }""");

        var withDir = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--doc", d.toString(),
                "-w", lib.toString());
        assertEquals(0, withDir.code(), () -> "-w/--workspace resolves the multi-file model: " + withDir.err());
        assertTrue(MAPPER.readTree(withDir.out()).get("data").has("fired"), () -> "emits a result: " + withDir.out());
    }
}
