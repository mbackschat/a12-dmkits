package io.github.mbackschat.a12.dm.cli.computation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

/** End-to-end RV04-6 lock for add/read/modify/diff/explain/report tolerance metadata. */
class ComputationToleranceCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPUTATION = "/Subscription/Billing/EffectiveFeeComp";

    @Test
    void addAuthorsEveryClosedBandAndReadExposesIt(@TempDir Path dir) throws Exception {
        for (ToleranceBand band : ToleranceBand.values()) {
            Path model = write(dir, "model-" + band.name() + ".json", CliTestSupport.subscriptionJson());
            Path spec = write(dir, "spec-" + band.name() + ".json", spec("[BaseFee]", band));

            var add = CliTestSupport.run("-m", model.toString(), "computation", "add", spec.toString());
            assertEquals(0, add.code(), add::err);
            assertTrue(Files.readString(model).contains("\"toleranceRangeOp\" : \"" + band.wire() + "\""));

            JsonNode read = data(CliTestSupport.run(
                    "-m", model.toString(), "computation", "read", COMPUTATION));
            assertEquals(band.wire(), read.path("alternatives").get(0).path("toleranceRangeOp").asText());
        }
    }

    @Test
    void modifyDiffExplainAndReportPreserveTheBand(@TempDir Path dir) throws Exception {
        ToleranceBand beforeBand = ToleranceBand.TR1;
        ToleranceBand band = ToleranceBand.TR2;
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path initial = write(dir, "initial.json", spec("[BaseFee]", beforeBand));
        assertEquals(0, CliTestSupport.run(
                "-m", model.toString(), "computation", "add", initial.toString()).code());

        Path modified = write(dir, "modified.json", spec("[BaseFee]", band));
        var modify = CliTestSupport.run(
                "-m", model.toString(), "computation", "modify", COMPUTATION, "--spec", modified.toString());
        assertEquals(0, modify.code(), modify::err);
        JsonNode changed = MAPPER.readTree(modify.out()).path("changed");
        assertTrue(changed.path("changed").asBoolean(), () -> "a band change is semantic: " + modify.out());
        assertTrue(changed.path("before").asText().contains(beforeBand.wire())
                        && changed.path("after").asText().contains(band.wire()),
                () -> "the semantic diff must expose both tolerance bands: " + modify.out());

        JsonNode read = data(CliTestSupport.run("-m", model.toString(), "computation", "read", COMPUTATION));
        assertEquals(band.wire(), read.path("alternatives").get(0).path("toleranceRangeOp").asText());

        JsonNode explain = data(CliTestSupport.run("-m", model.toString(), "computation", "explain", COMPUTATION));
        assertEquals(band.wire(), explain.path("alternatives").get(0).path("toleranceRangeOp").asText());

        JsonNode report = data(CliTestSupport.run("-m", model.toString(), "model", "report"));
        JsonNode reportComputation = report.path("computations").findValue("toleranceRangeOp");
        assertEquals(band.wire(), reportComputation.asText(), () -> "model report hides the band: " + report);
    }

    @Test
    void applyCarriesTheSameTypedAlternativeMetadata(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        Path operations = write(dir, "ops.json", """
                [ {"target":"computation","op":"add",
                   "computedField":"/Subscription/Billing/EffectiveFee",
                   "alternatives":[
                     {"operation":"[BaseFee]","toleranceRangeOp":"DiffersWithToleranceRange5"}
                   ],
                   "messages":[
                     {"locale":"en_US","text":"Effective fee follows base."},
                     {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                   ]} ]
                """);

        var apply = CliTestSupport.run("-m", model.toString(), "apply", operations.toString());
        assertEquals(0, apply.code(), apply::err);
        assertTrue(MAPPER.readTree(apply.out()).path("data").path("committed").asBoolean(), apply::out);
        assertTrue(Files.readString(model).contains("\"toleranceRangeOp\" : \"DiffersWithToleranceRange5\""));
    }

    private static String spec(String operation, ToleranceBand band) {
        return """
                { "computedField": "/Subscription/Billing/EffectiveFee",
                  "alternatives": [
                    { "operation": "%s", "toleranceRangeOp": "%s" }
                  ],
                  "messages": [
                    {"locale":"en_US","text":"Effective fee follows base."},
                    {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                  ] }
                """.formatted(operation, band.wire());
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
