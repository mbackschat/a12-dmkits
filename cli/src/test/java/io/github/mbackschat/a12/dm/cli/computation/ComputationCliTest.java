package io.github.mbackschat.a12.dm.cli.computation;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The CLI computation write/modify verbs: {@code add} a computation from a computation-spec and
 * {@code modify --computation} to re-express one — the authoring + modify half of the computation loop
 * over the CLI, working on self-contained and multi-file (include-resolving) models alike.
 */
class ComputationCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EFFECTIVE_FEE_SPEC = """
            { "computedField": "/Subscription/Billing/EffectiveFee",
              "alternatives": [ { "operation": "[BaseFee]" } ],
              "messages": [ {"locale":"en_US","text":"EffectiveFee equals BaseFee."},
                            {"locale":"de_DE","text":"Effektivgebühr = Basisgebühr."} ] }
            """;

    private static Path write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    @Test
    void addsAComputationFromASpec(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.subscriptionJson());
        Path spec = write(dir, "comp.json", EFFECTIVE_FEE_SPEC);

        var res = CliTestSupport.run("add", model.toString(), spec.toString());
        assertEquals(0, res.code(), res::err);
        // the persisted DM-JSON carries the computation element
        assertTrue(res.out().contains("\"Computation\"") && res.out().contains("EffectiveFeeComp"),
                () -> "persisted model should hold the computation: " + res.out());

        // and it round-trips: read the persisted model and the computation is typed
        Path persisted = write(dir, "persisted.dm.json", res.out());
        var read = CliTestSupport.run("read", persisted.toString(), "--all");
        assertEquals(0, read.code(), read::err);
        JsonNode j = MAPPER.readTree(read.out());
        assertTrue(j.path("computations").path("/Subscription/Billing/EffectiveFeeComp").path("converted").asBoolean(),
                () -> "the added computation reads as Converted: " + read.out());
    }

    @Test
    void rejectsAComputationSpecMissingAnOperation(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.subscriptionJson());
        Path spec = write(dir, "bad.json", """
                { "computedField": "/Subscription/Billing/EffectiveFee",
                  "alternatives": [ { } ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"y"} ] }
                """);
        var res = CliTestSupport.run("add", model.toString(), spec.toString());
        assertNotEquals(0, res.code(), "an alternative without an operation is malformed");
        assertTrue(res.err().contains("operation"), () -> "names the gap: " + res.err());
    }

    @Test
    void modifyDryRunReExpressesAComputation(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.modelWithRuleAndComputationJson());
        Path spec = write(dir, "modified.json", """
                { "computedField": "/Subscription/Billing/EffectiveFee", "name": "EffectiveFeeComp",
                  "alternatives": [ { "operation": "[BaseFee] * 2" } ],
                  "messages": [ {"locale":"en_US","text":"EffectiveFee is double BaseFee."},
                                {"locale":"de_DE","text":"Effektivgebühr ist doppelte Basisgebühr."} ] }
                """);
        String before = Files.readString(model);
        var res = CliTestSupport.run("modify", model.toString(),
                "--computation", CliTestSupport.COMPUTATION_FULL_NAME, "--spec", spec.toString(), "--dry-run");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.get("changed").asBoolean(), () -> "the re-expression is a change: " + res.out());
        assertTrue(j.get("after").asText().contains("2"), () -> "after carries the markup: " + res.out());
        assertEquals(before, Files.readString(model), "--dry-run writes nothing");
    }

    @Test
    void modifySpecMustMatchTheComputation(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.modelWithRuleAndComputationJson());
        Path spec = write(dir, "spec.json", EFFECTIVE_FEE_SPEC);   // derives EffectiveFeeComp
        var res = CliTestSupport.run("modify", model.toString(),
                "--computation", "/Subscription/Billing/SomeOtherComp", "--spec", spec.toString(), "--dry-run");
        assertNotEquals(0, res.code(), "the spec must re-express the same computation named by --computation");
    }

    @Test
    void modifyNeedsExactlyOneMode(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.modelWithRuleAndComputationJson());
        var both = CliTestSupport.run("modify", model.toString(),
                "--rule", CliTestSupport.RULE_FULL_NAME, "--computation", CliTestSupport.COMPUTATION_FULL_NAME, "--dry-run");
        assertNotEquals(0, both.code(), "--rule and --computation are mutually exclusive");
        var neither = CliTestSupport.run("modify", model.toString());
        assertNotEquals(0, neither.code(), "modify needs a mode");
    }

    @Test
    void addsAComputationOverAMultiFileModel(@TempDir Path dir) throws Exception {
        // an invoice that includes a pricelist; the computation is on owned number fields
        String pricelist = DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p.numberField("ListPrice", n -> n.fractionDigits(2)))
                .build().toJson();
        String invoice = DocumentModelDraft.named("invoice").locale("en_US")
                .include("pricelist", "pricelist")
                .rootGroup("Invoice", i -> i
                        .numberField("Subtotal", n -> { })
                        .numberField("Tax", n -> { })
                        .numberField("Total", n -> { })
                        .mount("Prices", "pricelist"))
                .build().toJson();
        write(dir, "pricelist.dm.json", pricelist);
        Path model = write(dir, "invoice.dm.json", invoice);
        Path spec = write(dir, "total.json", """
                { "computedField": "/Invoice/Total",
                  "alternatives": [ { "operation": "[Subtotal] + [Tax]" } ],
                  "messages": [ {"locale":"en_US","text":"Total is subtotal plus tax."} ] }
                """);

        // sibling pricelist resolves by default → the including model loads + the computation validates
        var res = CliTestSupport.run("add", model.toString(), spec.toString());
        assertEquals(0, res.code(), () -> "add a computation over a multi-file model: " + res.err() + res.out());
        assertTrue(res.out().contains("TotalComp"), () -> "the computation is persisted: " + res.out());
    }
}
