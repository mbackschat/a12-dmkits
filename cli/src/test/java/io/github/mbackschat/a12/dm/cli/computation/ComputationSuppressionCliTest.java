package io.github.mbackschat.a12.dm.cli.computation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * End-to-end lock for A12's computation preamble, {@code additionalParserInformation}: the CLI stores the text
 * unchanged and lets A12 judge it ({@code validate.laws.ComputationParserInformationLawsTest}).
 */
class ComputationSuppressionCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPUTATION = "/Subscription/Billing/EffectiveFeeComp";
    private static final String SUPPRESSION = "MVK_INVALID_COMPARE_DEC_PLACES";
    private static final String PREAMBLE = "@SuppressWarning(" + SUPPRESSION + ")";
    private static final String STORED = "\"additionalParserInformation\" : \"" + PREAMBLE + "\"";

    @Test
    void addPersistsAndEveryReviewSurfaceExposesTheSuppression(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        String before = Files.readString(model);
        Path unsuppressed = write(dir, "unsuppressed.json", spec(false));
        var rejected = CliTestSupport.run(
                "-m", model.toString(), "computation", "add", unsuppressed.toString(), "--dry-run");
        assertEquals(1, rejected.code(), rejected::err);
        assertTrue(MAPPER.readTree(rejected.out()).path("diagnostics").findValuesAsText("code")
                .contains(SUPPRESSION), rejected::out);
        assertEquals(before, Files.readString(model), "the unsuppressed static rejection must not write");

        Path spec = write(dir, "spec.json", spec(true));

        var add = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, add.code(), add::err);
        assertTrue(Files.readString(model).contains(STORED));

        JsonNode read = data(CliTestSupport.run("-m", model.toString(), "computation", "read", COMPUTATION));
        assertEquals(PREAMBLE, read.path("additionalParserInformation").asText());

        JsonNode explain = data(CliTestSupport.run("-m", model.toString(), "computation", "explain", COMPUTATION));
        assertEquals(PREAMBLE, explain.path("additionalParserInformation").asText());

        JsonNode report = data(CliTestSupport.run("-m", model.toString(), "model", "report"));
        JsonNode reported = report.path("computations").get(0);
        assertEquals(PREAMBLE, reported.path("additionalParserInformation").asText());
    }

    @Test
    void modifyTreatsThePreambleAsPartOfTheWholeReExpression(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path suppressed = write(dir, "suppressed.json", spec("[BaseFee]", true));
        assertEquals(0, CliTestSupport.run(
                "-m", model.toString(), "computation", "add", suppressed.toString()).code());

        Path unsuppressed = write(dir, "unsuppressed.json", spec("[BaseFee]", false));
        var modify = CliTestSupport.run(
                "-m", model.toString(), "computation", "modify", COMPUTATION,
                "--spec", unsuppressed.toString(), "--dry-run");
        assertEquals(0, modify.code(), modify::err);
        JsonNode changed = MAPPER.readTree(modify.out()).path("changed");
        assertTrue(changed.path("changed").asBoolean(), modify::out);
        assertTrue(changed.path("before").asText().contains(SUPPRESSION), modify::out);
        assertFalse(changed.path("after").asText().contains(SUPPRESSION), modify::out);
        assertTrue(Files.readString(model).contains(STORED),
                "--dry-run must not mutate the model");

        var committed = CliTestSupport.run(
                "-m", model.toString(), "computation", "modify", COMPUTATION,
                "--spec", unsuppressed.toString());
        assertEquals(0, committed.code(), committed::err);
        JsonNode read = data(CliTestSupport.run("-m", model.toString(), "computation", "read", COMPUTATION));
        assertTrue(read.path("additionalParserInformation").isMissingNode(),
                "omission in a whole re-expression removes the preamble");
    }

    @Test
    void applyAuthorsTheSameOwnerShapedPreamble(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path operations = write(dir, "ops.json", """
                [ {"target":"computation","op":"add",
                   "computedField":"/Subscription/Billing/EffectiveFee",
                   "alternatives":[{"operation":"[BaseFee]"}],
                   "messages":[
                     {"locale":"en_US","text":"Effective fee follows base."},
                     {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                   ]},
                  {"target":"computation","op":"modify",
                   "computationPathInModel":"/Subscription/Billing/EffectiveFeeComp",
                   "computedField":"/Subscription/Billing/EffectiveFee",
                   "alternatives":[{"operation":"[BaseFee] / 3"}],
                   "additionalParserInformation":"@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)",
                   "messages":[
                     {"locale":"en_US","text":"Effective fee follows base."},
                     {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                   ]} ]
                """);

        var apply = CliTestSupport.run("-m", model.toString(), "apply", operations.toString());
        assertEquals(0, apply.code(), apply::err);
        assertTrue(Files.readString(model).contains(STORED));
    }

    /** A12 judges the stored text, so its own class reaches the caller and nothing is written. */
    @Test
    void aPreambleA12RefusesIsAWriteSafeKernelRejection(@TempDir Path dir) throws Exception {
        assertRefusedUnwritten(dir, "unsupported", PREAMBLE.replace(SUPPRESSION, "MVK_INVALID_DATE_TYPE"),
                "MVK_INVALID_SUPPRESSED_WARNING");
        assertRefusedUnwritten(dir, "german", "@UnterdrueckeWarnung(" + SUPPRESSION + ")", "MVK_UNEXPECTED_TOKEN");
    }

    /** Kernel 31.3.1 reads a comma-separated list, the supported code twice included (KERNEL-FINDINGS KF329). */
    @Test
    void aListIsAdmittedAndStoredVerbatim(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        String list = "@SuppressWarning(" + SUPPRESSION + ", " + SUPPRESSION + ")";
        Path spec = write(dir, "spec.json", spec(true).replace(PREAMBLE, list));

        var add = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());

        assertEquals(0, add.code(), add::out);
        assertTrue(Files.readString(model).contains("\"additionalParserInformation\" : \"" + list + "\""));
    }

    private static void assertRefusedUnwritten(Path dir, String name, String preamble, String code) throws Exception {
        Path model = write(dir, name + ".json", CliTestSupport.subscriptionJson());
        String before = Files.readString(model);
        Path spec = write(dir, name + "-spec.json", spec(true).replace(PREAMBLE, preamble));

        var add = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());

        assertEquals(1, add.code(), add::err);
        assertTrue(MAPPER.readTree(add.out()).path("diagnostics").findValuesAsText("code").contains(code), add::out);
        assertEquals(before, Files.readString(model), "a refused preamble must not mutate the model");
    }

    @Test
    void modifyAndTargetSpecificSpecsCannotSilentlyDropKeys(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path initial = write(dir, "initial.json", spec("[BaseFee]", false));
        assertEquals(0, CliTestSupport.run(
                "-m", model.toString(), "computation", "add", initial.toString()).code());
        String before = Files.readString(model);

        Path typo = write(dir, "typo.json", spec("[BaseFee]", false)
                .replace("\"alternatives\"", "\"errorCodesToSuppress\": [\"" + SUPPRESSION
                        + "\"], \"alternatives\""));
        var modify = CliTestSupport.run(
                "-m", model.toString(), "computation", "modify", COMPUTATION, "--spec", typo.toString());
        assertEquals(1, modify.code(), modify::err);
        assertEquals("RK_UNKNOWN_ARG",
                MAPPER.readTree(modify.out()).path("diagnostics").get(0).path("code").asText());
        assertEquals(before, Files.readString(model), "an unknown modify key must not mutate the model");

        Path ruleOnly = write(dir, "rule-only.json", spec("[BaseFee]", false)
                .replace("\"alternatives\"", "\"allowDifferingDecimals\": true, \"alternatives\""));
        var computationAdd = CliTestSupport.run(
                "-m", model.toString(), "computation", "add", ruleOnly.toString(), "--dry-run");
        assertEquals(1, computationAdd.code(), computationAdd::err);
        assertEquals("RK_UNKNOWN_ARG",
                MAPPER.readTree(computationAdd.out()).path("diagnostics").get(0).path("code").asText());

        Path computationOnly = write(dir, "computation-only.json", """
                {"field":"/Subscription/AutoRenew","condition":"FieldNotFilled(AutoRenew)",
                 "code":"AUTO_RENEW_REQUIRED",
                 "additionalParserInformation":"@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)",
                 "messages":[
                   {"locale":"en_US","text":"Choose auto renew."},
                   {"locale":"de_DE","text":"Bitte wählen Sie, ob sich das Abonnement automatisch verlängern soll."}
                 ]}
                """);
        var ruleAdd = CliTestSupport.run(
                "-m", model.toString(), "rule", "add", computationOnly.toString(), "--dry-run");
        assertEquals(1, ruleAdd.code(), ruleAdd::err);
        assertEquals("RK_UNKNOWN_ARG",
                MAPPER.readTree(ruleAdd.out()).path("diagnostics").get(0).path("code").asText());
    }

    private static String spec(boolean suppressed) {
        return spec("[BaseFee] / 3", suppressed);
    }

    private static String spec(String operation, boolean suppressed) {
        String suppression = suppressed
                ? "\n  \"additionalParserInformation\": \"" + PREAMBLE + "\","
                : "";
        return """
                { "computedField": "/Subscription/Billing/EffectiveFee",%s
                  "alternatives": [ { "operation": "%s" } ],
                  "messages": [
                    {"locale":"en_US","text":"Effective fee follows base."},
                    {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                  ] }
                """.formatted(suppression, operation);
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static JsonNode data(CliTestSupport.Result result) throws Exception {
        assertEquals(0, result.code(), result::err);
        return MAPPER.readTree(result.out()).path("data");
    }
}
