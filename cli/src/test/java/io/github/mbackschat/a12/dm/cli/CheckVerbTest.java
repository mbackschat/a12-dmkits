package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code check} verb — the authoring feedback loop the N6 baseline probe found missing: submit a
 * candidate rule (condition + error field + code) and get the real kernel's accept/reject. Thin
 * wrapper over core {@code RuleValidator.check}.
 */
class CheckVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) throws IOException {
        Path f = dir.resolve("m.dm.json");
        Files.writeString(f, CliTestSupport.subscriptionJson());
        return f;
    }

    @Test
    void ruleErrorFieldInsideAnIncludeMountIsNamedAsAMount(@TempDir Path dir) throws Exception {
        // probe 04: anchoring a rule's error field inside an include MOUNT got the misleading "no group at
        // path" (RK_NO_SUCH_GROUP) — but the group DOES exist, as a mount. It must now say so
        // (RK_MOUNT_NOT_EDITABLE) and point at anchoring on a native field.
        Path base = model(dir);   // subscription (has /Subscription)
        Path catalog = dir.resolve("catalog.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "catalog", "--locale", "en_US",
                "--root", "Catalog", "-o", catalog.toString()).code());
        // match the subscription's locales (en_US + de_DE) so the mount composes cleanly
        assertEquals(0, CliTestSupport.run("-m", catalog.toString(), "config", "modify", "--add-locale", "de_DE").code());
        assertEquals(0, CliTestSupport.run("-m", catalog.toString(), "field", "add",
                "--group", "/Catalog", "--name", "Sku", "--kind", "STRING").code());
        assertEquals(0, CliTestSupport.run("-m", base.toString(), "include", "add",
                "--reference", "catalog", "--parent", "/Subscription", "--name", "Catalog",
                "-w", dir.toString()).code(), "mount the catalog under /Subscription");
        var res = CliTestSupport.run("-m", base.toString(), "rule", "check",
                "--field", "/Subscription/Catalog/Sku", "--condition", "FieldFilled(/Subscription/Catalog/Sku)",
                "--code", "X", "-w", dir.toString());
        JsonNode j = MAPPER.readTree(res.out());
        boolean mountNamed = false;
        for (JsonNode d : j.path("diagnostics")) {
            mountNamed |= "RK_MOUNT_NOT_EDITABLE".equals(d.path("code").asText());
        }
        assertTrue(mountNamed, () -> "names the include mount (not a missing group): " + res.out());
        assertFalse(res.out().contains("no group at path"), () -> "must not claim the mount group is absent: " + res.out());
    }

    @Test
    void aLowercaseBooleanLiteralGetsTheCasingFix(@TempDir Path dir) throws Exception {
        // 05/06 probe: `== true` (JSON casing) fails MVK_UNEXPECTED_TOKEN, which the kernel raises with no
        // message; the rule check diagnostic must now carry the syntax fix naming the capitalized True/False
        // (the DSL boolean literal), so the agent isn't left guessing at a message-less reject.
        Path m = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "check",
                "--field", "/Order/BackorderQuantity",
                "--condition", "[/Order/ExpressShipping] == true And FieldNotFilled(/Order/BackorderQuantity)",
                "--code", "X");
        JsonNode j = MAPPER.readTree(res.out());
        boolean casingFix = false;
        for (JsonNode d : j.path("diagnostics")) {
            if ("MVK_UNEXPECTED_TOKEN".equals(d.path("code").asText())) {
                String fix = d.path("fix").asText("");
                casingFix = fix.contains("True") && fix.contains("False");
                assertTrue(fix.contains("`And`/`and`/`AND`"), res::out);
            }
        }
        assertTrue(casingFix, () -> "the MVK_UNEXPECTED_TOKEN reject carries the boolean-casing fix: " + res.out());
    }

    @Test
    void aSecondaryOperatorRejectionCarriesItsExecutableCorrective(@TempDir Path dir) throws Exception {
        Path m = CliTestSupport.order(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "check",
                "--field", "/Order/PickupTime",
                "--condition", "HoursFromTime(Today) > HoursFromTime(PickupTime)", "--code", "X");
        JsonNode diagnostic = MAPPER.readTree(res.out()).path("diagnostics").get(0);

        assertEquals("MVK_WRONG_DATE_FORMAT_FOR_OP", diagnostic.path("code").asText(), res::out);
        assertTrue(diagnostic.path("fix").asText().contains("time or date-time"), res::out);
        assertTrue(diagnostic.path("explain").asText().contains("hour-of-day"), res::out);
    }

    @Test
    void invalidRulePatternGetsTheTwoStageCorrective(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "check",
                "--field", "/Subscription/Billing/InvoiceEmail",
                "--condition", "[InvoiceEmail] PatternMatched \"(?<=a)b\"", "--code", "X");
        assertEquals(1, res.code());
        JsonNode diagnostics = MAPPER.readTree(res.out()).path("diagnostics");
        assertTrue(hasPatternCorrective(diagnostics), () -> "rule check pattern corrective: " + res.out());
    }

    private static boolean hasPatternCorrective(JsonNode diagnostics) {
        for (JsonNode diagnostic : diagnostics) {
            if ("MVK_INVALID_PATTERN".equals(diagnostic.path("code").asText())
                    && diagnostic.path("fix").asText().contains("A12K-30")
                    && diagnostic.path("explain").asText().contains("Java Pattern")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void fieldThatIsAFilePathGetsATailoredError(@TempDir Path dir) throws Exception {
        // the recurring N6 slip: the <model.json> file path pasted into --field. The ModelPaths guard
        // now names the exact confusion instead of a generic "not a field".
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "check",
                "--field", m.toString(), "--condition", "FieldFilled(MonthlyFee)", "--code", "X");
        assertNotEquals(0, res.code(), "a file path in --field must fail");
        assertTrue(res.err().contains("looks like a FILE path") && res.err().contains("--field"),
                () -> "the error should name the file-vs-entity confusion: " + res.err());
    }

    @Test
    void filePathFieldSuggestsTheFieldTheConditionReferences(@TempDir Path dir) throws Exception {
        // Beyond "you passed a file": name the concrete field the --condition references, so a weaker
        // agent has a value to copy, not just a rule to re-read. BaseFee is unique in the model.
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "rule", "check",
                "--field", m.toString(), "--condition", "[BaseFee] > 100", "--code", "FEE_CAP");
        assertNotEquals(0, res.code(), "a file path in --field must fail");
        assertTrue(res.err().contains("Did you mean") && res.err().contains("/Subscription/Billing/BaseFee"),
                () -> "should suggest the field the condition references: " + res.err());
    }

    @Test
    void acceptsAValidCandidateRule(@TempDir Path dir) throws Exception {
        // --field is the error field's absolute path; --group defaults to its parent (iterate per add-on).
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "[MonthlyFee] > 1000", "--code", "FEE_CAP");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("valid").asBoolean(), "the kernel accepts a well-formed candidate");
        assertTrue(j.get("diagnostics").isEmpty(), "with no diagnostics");
    }

    @Test
    void rejectsABadCandidateWithKernelDiagnostics(@TempDir Path dir) throws Exception {
        Path input = model(dir);
        for (var row : Map.of(
                "@@@ not a condition @@@", "MVK_UNEXPECTED_TOKEN",
                "FieldFilled(MonthlyFee", "MVK_EXPECTED_TOKEN_NOT_FOUND",
                "FieldFilled(MonthlyFee) And", "MVK_INCOMPLETE_INPUT").entrySet()) {
            var res = CliTestSupport.run("-m", input.toString(), "rule", "check",
                    "--field", "/Subscription/Addons/MonthlyFee", "--condition", row.getKey(), "--code", "X");
            assertEquals(1, res.code(), "a rejected candidate exits non-zero");
            JsonNode j = MAPPER.readTree(res.out());
            assertFalse(j.get("valid").asBoolean(), "valid is false");
            var codes = new java.util.HashSet<String>();
            j.get("diagnostics").forEach(d -> {
                String code = d.path("code").asText();
                if (code.startsWith("MVK_")) codes.add(code);
            });
            assertEquals(java.util.Set.of(row.getValue()), codes, res::out);
        }
    }

    @Test
    void warnsOnInvertedRequirednessInDiagnostics(@TempDir Path dir) throws Exception {
        // The requirement written positively — FieldFilled on the error field — is kernel-valid but
        // inverted polarity (it fires when the fee IS present). check accepts it (valid stays true)
        // yet surfaces the RK_INVERTED_REQUIREDNESS lint warning in the diagnostics — the same silent
        // gap the kernel can't catch and that `add` warns on, now caught at the check stage too.
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Subscription/Addons/MonthlyFee",
                "--condition", "FieldFilled(MonthlyFee)", "--code", "FEE_PRESENT");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("valid").asBoolean(), "the kernel accepts a well-formed (if inverted) candidate");
        assertTrue(j.get("diagnostics").toString().contains("RK_INVERTED_REQUIREDNESS"),
                () -> "the lint backstop surfaces the inverted polarity: " + res.out());
    }

    @Test
    void missingRequiredFlagFails(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Subscription/Addons/MonthlyFee", "--code", "X"); // no --condition
        assertNotEquals(0, res.code(), "a missing required flag is an error");
        assertTrue(res.err().toLowerCase().contains("condition") || res.err().toLowerCase().contains("usage"),
                () -> "names the gap: " + res.err());
    }
}
