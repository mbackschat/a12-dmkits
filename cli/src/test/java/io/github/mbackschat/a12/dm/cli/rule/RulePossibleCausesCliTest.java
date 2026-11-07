package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * A rule's possible causes through the CLI: the spec names entities by absolute path, the model stores the
 * relative path A12 admits from the rule's group, and {@code rule read} answers in the spec's own form
 * (KERNEL-FINDINGS KF682). The kernel is the oracle for each stored entry: it refuses any other spelling.
 */
class RulePossibleCausesCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RULE = "/Subscription/Billing/INVOICE_EMAIL";
    private static final String CAUSES = """
            "possibleCauses": {"ifFilled": ["/Subscription/Billing/BaseFee"],
                               "ifNotFilled": ["/Subscription/Billing/InvoiceEmail", "/Subscription/TermsAccepted"]},""";

    @Test
    void addStoresA12sEntriesAndReadAnswersInTheSpecsForm(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path spec = write(dir, "spec.json", spec(CAUSES));

        var add = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());

        assertEquals(0, add.code(), add::out);
        JsonNode stored = MAPPER.readTree(Files.readString(model)).findParent("filterForPossiblyCausingEntities")
                .path("filterForPossiblyCausingEntities");
        assertEquals(MAPPER.readTree("""
                {"entitiesEnabledAsPossibleCauseIfFilled": [{"relativePath": "BaseFee"}],
                 "entitiesEnabledAsPossibleCauseIfNotFilled":
                   [{"relativePath": "InvoiceEmail"}, {"relativePath": "../TermsAccepted"}]}"""), stored);
        var read = CliTestSupport.run("-m", model.toString(), "rule", "read", RULE);
        assertEquals(0, read.code(), read::err);
        assertEquals(MAPPER.readTree("{" + CAUSES.substring(0, CAUSES.length() - 1) + "}").path("possibleCauses"),
                MAPPER.readTree(read.out()).path("data").path("possibleCauses"));
    }

    @Test
    void aRuleWithoutCausesReadsWithoutTheKey(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "rule", "add",
                write(dir, "spec.json", spec("")).toString()).code());

        var read = CliTestSupport.run("-m", model.toString(), "rule", "read", RULE);

        assertTrue(MAPPER.readTree(read.out()).path("data").path("possibleCauses").isMissingNode(), read::out);
    }

    @Test
    void aCauseTheConditionDoesNotReadIsTheKernelsRejectionAndWritesNothing(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        String before = Files.readString(model);
        Path spec = write(dir, "spec.json", spec("""
                "possibleCauses": {"ifFilled": ["/Subscription/Tier"]},"""));

        var add = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());

        assertEquals(1, add.code(), add::out);
        JsonNode diagnostics = MAPPER.readTree(add.out()).path("diagnostics");
        assertEquals(List.of("MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED"), diagnostics.findValuesAsText("code"));
        assertTrue(diagnostics.get(0).path("fix").asText().startsWith("Read the entity in the condition"), add::out);
        assertEquals(before, Files.readString(model));
    }

    @Test
    void aRelativeCauseIsRefusedBeforeTheKernelIsAsked(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        String before = Files.readString(model);
        Path spec = write(dir, "spec.json", spec("""
                "possibleCauses": {"ifFilled": ["BaseFee"]},"""));

        var add = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());

        assertEquals(1, add.code(), add::out);
        JsonNode diagnostic = MAPPER.readTree(add.out()).path("diagnostics").get(0);
        assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), add::out);
        assertTrue(diagnostic.path("fix").asText().contains("/Subscription/Billing/BaseFee"), add::out);
        assertEquals(before, Files.readString(model));
    }

    @Test
    void applyStoresTheSameEntries(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        ObjectNode op = (ObjectNode) MAPPER.readTree(spec(CAUSES));
        op.put("target", "rule").put("op", "add");
        Path ops = write(dir, "ops.json", MAPPER.createArrayNode().add(op).toString());

        var apply = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(0, apply.code(), apply::out);
        assertTrue(Files.readString(model).contains("\"relativePath\" : \"../TermsAccepted\""));
    }

    private static String spec(String causes) {
        return """
                {"field": "/Subscription/Billing/BaseFee", %s
                 "condition": "FieldFilled(BaseFee) And (FieldNotFilled(InvoiceEmail) Or FieldNotFilled(/Subscription/TermsAccepted))",
                 "code": "INVOICE_EMAIL",
                 "messages": [{"locale": "en_US", "text": "A base fee needs an invoice email and accepted terms."},
                              {"locale": "de_DE", "text": "Eine Grundgebühr braucht eine Rechnungsadresse und akzeptierte AGB."}]}
                """.formatted(causes);
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
