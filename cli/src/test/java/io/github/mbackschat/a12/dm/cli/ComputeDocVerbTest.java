package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * The {@code model compute} verb runs interpreter computation over a Document instance and
 * report what each computed field becomes (value / cleared / error). Read-only: it prints outcomes, never
 * writes the document. Uses the subscription fixture with {@code EffectiveFeeComp = [BaseFee]}.
 */
class ComputeDocVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path customModel(Path dir) throws Exception {
        String base = DocumentModelDraft.named("parcel-custom-computation").locale("en_US")
                .rootGroup("Parcel", p -> p.customField("Source", f -> f.type("SourceType"))
                        .customField("Copy", f -> f.type("TargetType"))
                        .numberField("Size", f -> f.fractionDigits(0)))
                .build().toJson();
        var bound = BoundModel.ofJson(base);
        var source = bound.custom("/Parcel/Source");
        var copy = bound.custom("/Parcel/Copy");
        var size = bound.num("/Parcel/Size");
        String model = EditSession.read(base)
                .addComputation(m -> m.computation(copy).name("CopyIdentifier").calc(source.asString())
                        .message(Dsl.en("Check the copied identifier.")).build())
                .addComputation(m -> m.computation(size).name("CopiedSize").calc(copy.length())
                        .message(Dsl.en("Check the identifier size.")).build()).toJson();
        Path path = dir.resolve("custom.dm.json");
        Files.writeString(path, model);
        return path;
    }

    private static Path customDocument(Path dir) throws Exception {
        Path path = dir.resolve("custom-document.json");
        Files.writeString(path, """
                {"Parcel":{"Source":"A\\r\\r\\nB","Copy":"stale","Size":99}}
                """);
        return path;
    }

    @Test
    void computationReportsMissingSourceAndTargetHandlersSeparately(@TempDir Path dir) throws Exception {
        Path model = customModel(dir);
        Path document = customDocument(dir);
        String original = Files.readString(document);
        Path registry = dir.resolve("types.json");
        for (String included : java.util.List.of("SourceType", "TargetType")) {
            Files.writeString(registry, MAPPER.writeValueAsString(java.util.Map.of(included, java.util.Map.of())));
            var result = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", document.toString(),
                    "--predefined-types", registry.toString(), "--strict-custom");
            assertEquals(2, result.code(), result::out);
            JsonNode out = MAPPER.readTree(result.out());
            assertComputeSchema(out.path("data"));
            assertEquals("refused", out.path("outcome").asText(), result::out);
            String missing = included.equals("SourceType") ? "Parcel/Copy" : "Parcel/Source";
            assertTrue(out.path("data").path("unsupported").toString().contains(missing), result::out);
            assertEquals(original, Files.readString(document));
        }
        Files.writeString(registry, "{\"SourceType\":{},\"TargetType\":{}}");
        var result = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", document.toString(),
                "--predefined-types", registry.toString(), "--strict-custom");
        assertEquals(0, result.code(), result::out);
        assertEquals("4", computedFor(result.out(), "Parcel/Size").path("value").asText());
    }

    @Test
    void customCallbacksRetainRawInputReservedCascadeAndSingleInvocation(@TempDir Path dir) throws Exception {
        Path model = customModel(dir);
        Path document = customDocument(dir);
        Files.writeString(document, Files.readString(document).replace("stale", ""));
        Path js = Files.createDirectory(dir.resolve("validators"));
        for (String source : java.util.List.of("ACCEPT", "REJECT", "testIDNummer")) {
            for (String target : java.util.List.of("ACCEPT", "REJECT", "testIDNummer")) {
                Files.writeString(js.resolve("SourceType.js"), validator("A\r\r\nB", source));
                Files.writeString(js.resolve("TargetType.js"), validator("A\r\nB", target));
                var result = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", document.toString(),
                        "--custom-field-types-js", js.toString(), "--strict-custom");
                assertEquals(0, result.code(), () -> source + "/" + target + ": " + result.out() + result.err());
                JsonNode copy = computedFor(result.out(), "Parcel/Copy");
                JsonNode size = computedFor(result.out(), "Parcel/Size");
                assertEquals(source.equals("REJECT") ? "cleared" : target.equals("ACCEPT") ? "value" : "error",
                        copy.path("outcome").asText(), result::out);
                boolean readable = !source.equals("REJECT") && !target.equals("REJECT");
                assertEquals(readable ? "value" : "cleared", size.path("outcome").asText(), result::out);
                if (readable) assertEquals("4", size.path("value").asText(), result::out);
                if (!source.equals("REJECT")) assertEquals("A\r\nB", copy.path("value").asText(), result::out);
                assertTrue(MAPPER.readTree(result.out()).path("data").path("unsupported").isMissingNode(), result::out);
            }
        }
    }

    private static String validator(String expected, String answer) throws Exception {
        return "let calls = 0;\nexport function validate(value) {\n"
                + "if (++calls !== 1 || value !== " + MAPPER.writeValueAsString(expected)
                + ") throw new Error('callback input or invocation count differed');\n"
                + "return " + (answer.equals("ACCEPT") ? "true" : "{valid:false,errorKey:" + MAPPER.writeValueAsString(answer) + "}")
                + ";\n}\n";
    }

    @Test
    void aFailingComputedTargetHandlerRemainsVisibleToStrictMode(@TempDir Path dir) throws Exception {
        Path model = customModel(dir);
        Path document = customDocument(dir);
        Path js = Files.createDirectory(dir.resolve("validators"));
        Files.writeString(js.resolve("SourceType.js"), validator("A\r\r\nB", "ACCEPT"));
        Files.writeString(js.resolve("TargetType.js"), "export function validate() { throw new Error('target unavailable'); }");
        var result = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", document.toString(),
                "--custom-field-types-js", js.toString(), "--strict-custom");
        assertEquals(2, result.code(), result::out);
        assertComputeSchema(MAPPER.readTree(result.out()).path("data"));
        JsonNode unsupported = MAPPER.readTree(result.out()).path("data").path("unsupported");
        assertEquals(1, unsupported.size(), result::out);
        assertEquals("element", unsupported.get(0).path("subject").asText(), result::out);
        assertTrue(unsupported.get(0).path("reason").asText().contains("target unavailable"), result::out);
    }

    @Test
    void aLaterHandlerFailureRetainsEarlierResultsAndSupportObservations(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("parcel-supported-prefix").locale("en_US")
                .rootGroup("Parcel", p -> p.stringField("Prefix", f -> {})
                        .customField("Source", f -> f.type("SourceType"))
                        .customField("Copy", f -> f.type("TargetType"))).build().toJson();
        var bound = BoundModel.ofJson(base);
        var prefix = bound.str("/Parcel/Prefix");
        var source = bound.custom("/Parcel/Source");
        var copy = bound.custom("/Parcel/Copy");
        String json = EditSession.read(base)
                .addComputation(m -> m.computation(prefix).name("PrefixValue")
                        .calc(new io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral("Parcel:"))
                        .message(Dsl.en("Check the prefix.")).build())
                .addComputation(m -> m.computation(copy).name("CopyIdentifier")
                        .commonPrecondition(prefix.eq("Parcel:")).calc(source.asString())
                        .message(Dsl.en("Check the copy.")).build()).toJson();
        Path model = dir.resolve("mixed.dm.json");
        Files.writeString(model, json);
        Path document = dir.resolve("mixed-document.json");
        Files.writeString(document, "{\"Parcel\":{\"Source\":\"ABCD\"}}");
        Path js = Files.createDirectory(dir.resolve("validators"));
        Files.writeString(js.resolve("TargetType.js"), "export function validate() { throw new Error('target unavailable'); }");
        var result = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", document.toString(),
                "--custom-field-types-js", js.toString(), "--strict-custom");
        assertEquals(2, result.code(), result::out);
        JsonNode data = MAPPER.readTree(result.out()).path("data");
        assertEquals(2, data.path("unsupported").size(), result::out);
        assertTrue(data.path("unsupported").toString().contains("Parcel/Source"), result::out);
        assertTrue(data.path("unsupported").toString().contains("target unavailable"), result::out);
        assertEquals("Parcel:", computedFor(result.out(), "Parcel/Prefix").path("value").asText());
        assertComputeSchema(data);
    }

    private static void assertComputeSchema(JsonNode data) throws Exception {
        try (var input = ComputeDocVerbTest.class.getResourceAsStream("/schema/compute-doc.schema.json")) {
            var factory = com.networknt.schema.JsonSchemaFactory.builder(
                            com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012))
                    .schemaLoaders(loaders -> loaders.values(java.util.List::clear)
                            .add(com.networknt.schema.resource.DisallowSchemaLoader.getInstance())).build();
            var schema = factory.getSchema(MAPPER.readTree(input), com.networknt.schema.SchemaValidatorsConfig.builder().build());
            assertEquals(java.util.Set.of(), schema.validate(data));
        }
    }

    private static JsonNode computedFor(String out, String fieldPath) throws Exception {
        for (JsonNode c : MAPPER.readTree(out).get("data").get("computed")) {
            if (c.get("field").asText().equals(fieldPath)) {
                return c;
            }
        }
        return null;
    }

    @Test
    void computesTheValueFromItsOperand(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, """
                { "Subscription": { "Billing": { "BaseFee": "50" } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "compute", "--instance", doc.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode cw = computedFor(res.out(), "Subscription/Billing/EffectiveFee");
        assertTrue(cw != null && cw.get("outcome").asText().equals("value") && cw.get("value").asText().equals("50"),
                () -> "EffectiveFee computes to BaseFee (50): " + res.out());
    }

    @Test
    void anEmptyNumberOperandComputesZero(@TempDir Path dir) throws Exception {
        // KERNEL FACT (type-specific): in a COMPUTATION, an empty NUMBER operand is substituted with 0
        // (so EffectiveFee = [BaseFee] computes "0" when BaseFee is absent) — unlike an empty DATE operand,
        // which computes NO value (DateRangeComputationLawsTest). The empty→0 rule extends to computation.
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, """
                { "Subscription": { "Billing": { } } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "compute", "--instance", doc.toString());
        assertEquals(0, res.code(), res::err);
        JsonNode cw = computedFor(res.out(), "Subscription/Billing/EffectiveFee");
        assertTrue(cw != null && cw.get("outcome").asText().equals("value") && cw.get("value").asText().equals("0"),
                () -> "an empty number operand is substituted with 0 in a computation: " + res.out());
    }

    @Test
    void aGuardedNumberComputationClearsWhereUnguardedComputesZero(@TempDir Path dir) throws Exception {
        // The premise behind the computation judge's TYPE-DEPENDENT empty-operand rule: for a NUMBER operand
        // a presence guard is NOT redundant. Unguarded EffectiveFee=[BaseFee] computes 0 on an empty BaseFee
        // (the other test); the SAME computation guarded by FieldFilled(BaseFee) computes NOTHING (cleared) —
        // so guarded != unguarded for numbers (unlike a DATE operand, where the guard is redundant).
        Path base = CliTestSupport.modelWithRule(dir);
        Path spec = dir.resolve("guarded.json");
        Files.writeString(spec, """
                { "computedField": "/Subscription/Billing/EffectiveFee", "commonPrecondition": "FieldFilled(BaseFee)",
                  "alternatives": [ { "operation": "[BaseFee]" } ],
                  "messages": [ {"locale":"en_US","text":"x"}, {"locale":"de_DE","text":"y"} ] }""");
        Path guarded = dir.resolve("guarded.dm.json");
        var added = CliTestSupport.run("-m", base.toString(), "computation", "add",
                spec.toString(), "-o", guarded.toString());
        assertEquals(0, added.code(), added::err);

        Path doc = dir.resolve("empty.json");
        Files.writeString(doc, """
                { "Subscription": { "Billing": { } } }""");
        var res = CliTestSupport.runKernel("-m", guarded.toString(), "model", "compute", "--instance", doc.toString());
        assertEquals(0, res.code(), res::err);
        assertNull(computedFor(res.out(), "Subscription/Billing/EffectiveFee"),
                () -> "a guarded number computation computes nothing on an empty operand: " + res.out());
    }

    @Test
    void unknownPathInTheDocumentFails(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.modelWithRuleAndComputation(dir);
        Path doc = dir.resolve("doc.json");
        Files.writeString(doc, """
                { "Subscription": { "Nope": "x" } }""");
        var res = CliTestSupport.runKernel("-m", model.toString(), "model", "compute", "--instance", doc.toString());
        assertNotEquals(0, res.code(), "an unknown path is an error");
        assertTrue(res.err().contains("Nope"), () -> "names the bad path: " + res.err());
    }
}
