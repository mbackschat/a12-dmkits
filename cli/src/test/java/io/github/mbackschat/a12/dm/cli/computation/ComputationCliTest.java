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
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

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

        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, res.code(), res::err);
        // the persisted DM-JSON (written in place) carries the computation element
        String persistedModel = Files.readString(model);
        assertTrue(persistedModel.contains("\"Computation\"") && persistedModel.contains("EffectiveFeeComp"),
                () -> "persisted model should hold the computation: " + persistedModel);

        // and it round-trips: read the persisted model and the computation is typed
        var read = CliTestSupport.run("-m", model.toString(), "model", "read");
        assertEquals(0, read.code(), read::err);
        JsonNode data = MAPPER.readTree(read.out()).path("data");
        assertTrue(data.path("computations").path("/Subscription/Billing/EffectiveFeeComp").path("converted").asBoolean(),
                () -> "the added computation reads as Converted: " + read.out());
    }

    @Test
    void addsAndReadsCustomTextConcatenationsAtBothTextTargets(@TempDir Path dir) throws Exception {
        String structure = DocumentModelDraft.named("parcel-derived-text").locale("en_US")
                .rootGroup("Parcel", p -> p.customField("Identifier", f -> f.type("ParcelIdentifier"))
                        .stringField("Label", f -> {})
                        .customField("Copy", f -> f.type("CopiedIdentifier")))
                .build().toJson();
        var bound = BoundModel.ofJson(structure);
        var source = bound.custom("/Parcel/Identifier");
        Path model = write(dir, "parcel.dm.json", structure);
        for (var target : java.util.List.of(bound.str("/Parcel/Label"), bound.custom("/Parcel/Copy"))) {
            var computation = bound.computation(target).name("Derive" + target.field().path().segments().getLast())
                    .calc(source.plus("-verified").expression())
                    .message(Dsl.en("Derived parcel text.")).build();
            var spec = MAPPER.createObjectNode().put("computedField", target.field().path().value())
                    .put("name", computation.name());
            spec.putArray("alternatives").addObject()
                    .put("operation", computation.draft().alternatives().getFirst().operation());
            spec.set("messages", MAPPER.valueToTree(computation.messages()));
            var added = CliTestSupport.run("-m", model.toString(), "computation", "add",
                    write(dir, "computation.json", spec.toString()).toString());
            assertEquals(0, added.code(), added::out);
            var read = CliTestSupport.run("-m", model.toString(), "model", "read");
            assertEquals(0, read.code(), read::out);
            assertTrue(MAPPER.readTree(read.out()).path("data").path("computations")
                    .path(computation.groupPath() + "/" + computation.name()).path("converted").asBoolean(), read::out);
        }
    }

    @Test
    void rejectsAComputationSpecMissingAnOperation(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.subscriptionJson());
        Path spec = write(dir, "bad.json", """
                { "computedField": "/Subscription/Billing/EffectiveFee",
                  "alternatives": [ { } ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"y"} ] }
                """);
        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertNotEquals(0, res.code(), "an alternative without an operation is malformed");
        assertTrue(res.out().contains("RK_INVALID_VALUE") && res.out().contains("alternatives[]/operation"),
                () -> "the schema rejection names the nested required property: " + res.out());
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
        var res = CliTestSupport.run("-m", model.toString(), "computation", "modify",
                CliTestSupport.COMPUTATION_FULL_NAME, "--spec", spec.toString(), "--dry-run");
        assertEquals(0, res.code(), res::err);
        // the before/after diff rides the envelope's `changed`
        JsonNode changed = MAPPER.readTree(res.out()).get("changed");
        assertTrue(changed.get("changed").asBoolean(), () -> "the re-expression is a change: " + res.out());
        assertTrue(changed.get("after").asText().contains("2"), () -> "after carries the markup: " + res.out());
        assertEquals(before, Files.readString(model), "--dry-run writes nothing");
    }

    @Test
    void modifySpecMustMatchTheComputation(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.modelWithRuleAndComputationJson());
        // a well-formed spec whose computedField is a DIFFERENT field than the targeted (existing) computation
        Path spec = write(dir, "spec.json", """
                { "computedField": "/Subscription/Billing/BaseFee",
                  "alternatives": [ { "operation": "[EffectiveFee]" } ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"y"} ] }
                """);
        var res = CliTestSupport.run("-m", model.toString(), "computation", "modify",
                CliTestSupport.COMPUTATION_FULL_NAME, "--spec", spec.toString(), "--dry-run");
        assertNotEquals(0, res.code(), "the spec must re-express the same computation named positionally");
    }

    @Test
    void modifyNeedsASpec(@TempDir Path dir) throws Exception {
        Path model = write(dir, "m.dm.json", CliTestSupport.modelWithRuleAndComputationJson());
        var noSpec = CliTestSupport.run("-m", model.toString(), "computation", "modify",
                CliTestSupport.COMPUTATION_FULL_NAME);
        assertNotEquals(0, noSpec.code(), "computation modify needs --spec");
    }

    @Test
    void addsAComputationOverAMultiFileModel(@TempDir Path dir) throws Exception {
        // an invoice that includes a pricelist; the computation is on owned number fields
        String pricelist = DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p.numberField("ListPrice", n -> n.fractionDigits(2)))
                .build().toJson();
        String invoice = DocumentModelDraft.named("invoice").locale("en_US")
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
        var res = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, res.code(), () -> "add a computation over a multi-file model: " + res.err() + res.out());
        assertTrue(res.out().contains("TotalComp"), () -> "the computation is persisted: " + res.out());
    }
}
