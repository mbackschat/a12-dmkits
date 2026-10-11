package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** The black-box public contract, executed against the exact packaged launcher selected by Gradle. */
@Execution(ExecutionMode.CONCURRENT)
class DmtoolPublicContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUNTIME = Set.of("model eval", "model compute", "rule eval");

    @Test
    void packagedVersionIdentifiesItsBaseRevisionAndDivergence(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Properties build = packagedVersionProperties();
        String revision = build.getProperty("sourceRevision");
        String state = build.getProperty("sourceState");

        PackagedDmtool.Result human = dmtool.run("--version");
        assertEquals(0, human.code(), human::err);
        assertTrue(human.out().contains("source: " + revision + " (" + state.toLowerCase() + ")"), human::out);

        PackagedDmtool.Result machine = dmtool.run("manifest");
        assertEquals(0, machine.code(), machine::err);
        JsonNode source = MAPPER.readTree(machine.out()).path("version").path("source");
        assertEquals(revision, source.path("revision").asText(), machine::out);
        assertEquals(state, source.path("state").asText(), machine::out);
    }

    @Test
    void packagedOperatorListMapsCatalogIdsToAuthoredKeywords(@TempDir Path dir) throws Exception {
        PackagedDmtool.Result result = new PackagedDmtool(dir).run("operators");
        assertEquals(0, result.code(), result::err);
        JsonNode validType = java.util.stream.StreamSupport.stream(
                        MAPPER.readTree(result.out()).path("operators").spliterator(), false)
                .filter(operator -> "ValidType".equals(operator.path("id").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ValidType is absent from packaged operators output"));
        assertEquals("Valid", validType.path("keyword").asText(), result::out);
    }

    @Test
    void packagedManifestOwnsTheCompleteOperationalDenominator(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        PackagedDmtool.Result result = dmtool.run("manifest");
        assertEquals(0, result.code(), result::err);
        JsonNode manifest = MAPPER.readTree(result.out());

        int leaves = 0;
        int writes = 0;
        int previews = 0;
        Set<String> runtime = new HashSet<>();
        for (JsonNode verb : manifest.path("verbs")) {
            leaves++;
            String name = verb.path("verb").asText();
            assertFalse(verb.path("operationalProfiles").isEmpty(), () -> name + " has no profiles");
            for (JsonNode profile : verb.path("operationalProfiles")) {
                for (String key : List.of("id", "when", "execution", "effect", "failurePersistence",
                        "dryRun", "aggregateSuccess", "topLevelOk", "returns", "provenance")) {
                    assertTrue(profile.has(key), () -> name + "#" + profile.path("id").asText() + " lacks " + key);
                }
                assertEquals("CASE_INSENSITIVE", profile.path("when").path("valueComparison").asText(), name);
                if (Set.of("MODEL_WRITE", "ARTIFACT_WRITE", "MULTI_FILE_WRITE", "TRANSACTION", "DISPATCH")
                        .contains(profile.path("effect").asText())) {
                    writes++;
                }
                if ("ACTIVE".equals(profile.path("dryRun").asText())) previews++;
                if ("DM_INTERPRETER_RUNTIME".equals(profile.path("execution").asText())) runtime.add(name);
            }
        }
        assertEquals(ManifestVerbTest.EXPECTED_VERBS.size(), leaves);
        assertEquals(49, writes);
        assertEquals(39, previews);
        assertEquals(RUNTIME, runtime, "only the three interpreter runtime routes may claim runtime execution");
    }

    @Test
    void packagedSchemaAndManifestRenderTheSameOperationalOwner(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        Map<String, PackagedDmtool.Result> rendered = new HashMap<>();
        int checked = 0;
        for (JsonNode verb : manifest.path("verbs")) {
            String name = verb.path("verb").asText();
            assertEquals(name, verb.path("operationalSchema").asText(), () -> name + " lacks its operational pointer");
            for (String key : List.of("schema", "operationalSchema")) {
                if (!verb.hasNonNull(key)) {
                    continue;
                }
                String pointer = verb.path(key).asText();
                PackagedDmtool.Result scalar = rendered.get(pointer);
                if (scalar == null) {
                    scalar = dmtool.run("schema", pointer);
                    rendered.put(pointer, scalar);
                }
                assertEquals(0, scalar.code(), name + " scalar " + key + ": " + scalar.err());
            }
            PackagedDmtool.Result result = rendered.get(verb.path("operationalSchema").asText());
            JsonNode contract = MAPPER.readTree(result.out());
            assertEquals(verb.path("operationalProfiles"), contract.path("operationalProfiles"),
                    () -> name + " manifest/schema profiles drifted");
            checked++;
        }
        assertEquals(ManifestVerbTest.EXPECTED_VERBS.size(), checked,
                "every live leaf projects its operational owner through schema");

        PackagedDmtool.Result scalarFieldAdd = rendered.get("field add");
        PackagedDmtool.Result tokenizedFieldAdd = dmtool.run("schema", "field", "add");
        assertEquals(0, scalarFieldAdd.code(), scalarFieldAdd::err);
        assertEquals(tokenizedFieldAdd.out(), scalarFieldAdd.out(),
                "the reported field-add failure must stay locked through the packaged launcher");

        for (String frame : List.of("apply", "batch")) {
            JsonNode schema = MAPPER.readTree(dmtool.run("schema", frame).out());
            assertEquals(manifestVerb(manifest, frame).path("operationalProfiles"),
                    schema.path("operationalProfiles"), frame + " frame schema drifted");
        }
    }

    @Test
    void packagedWorkedInvocationsRespectTheirDeclaredOutputAndEnvelopeContracts(@TempDir Path dir)
            throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        JsonNode examples = VerbDemonstrationTest.corpus();
        int envelopes = 0;
        int raw = 0;
        for (Iterator<String> names = examples.fieldNames(); names.hasNext();) {
            String verb = names.next();
            if (verb.startsWith("_")) continue;
            Path fixture = Files.createDirectories(dir.resolve(verb.replace(' ', '-')));
            JsonNode example = examples.get(verb);
            List<String> command = VerbDemonstrationTest.materialize(verb, example, fixture);
            List<CommandContracts.Profile> matches = CommandContracts.matchingProfiles(command);
            assertEquals(1, matches.size(), () -> verb + " profiles did not partition " + command + ": " + matches);
            CommandContracts.Profile profile = matches.getFirst();
            PackagedDmtool.Result result = dmtool.run(command);
            assertEquals(0, result.code(), () -> verb + " packaged example failed: " + result.err() + result.out());
            String expect = example.path("expect").asText();
            if (Set.of("read", "preview").contains(expect)) {
                JsonNode envelope = MAPPER.readTree(result.out());
                assertEquals(expect, envelope.path("outcome").asText(), () -> verb + ": " + result.out());
                ResultEnvelopeAssertions.assertConforms(envelope, verb);
                switch (profile.provenance().consistency()) {
                    case REQUIRED -> assertTrue(envelope.hasNonNull("verification"),
                            () -> verb + " promised a consistency receipt: " + result.out());
                    case NONE -> assertFalse(envelope.has("verification"),
                            () -> verb + " promised no consistency receipt: " + result.out());
                    case OPTIONAL, NESTED, CHILD_DEFINED, NOT_EMITTED -> { }
                }
                if (profile.provenance().runtime() == CommandContracts.Runtime.REQUIRED) {
                    assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), () -> verb + ": " + result.out());
                    assertTrue(envelope.hasNonNull("engineVersion") && envelope.hasNonNull("toolVersion"), result::out);
                }
                envelopes++;
            } else {
                assertFalse(result.out().isBlank(), () -> verb + " raw route emitted nothing");
                raw++;
            }
        }
        int envelopeCount = envelopes;
        int rawCount = raw;
        assertTrue(envelopeCount > 55, () -> "packaged envelope coverage shrank: " + envelopeCount);
        assertTrue(rawCount >= 7, () -> "packaged artifact coverage shrank: " + rawCount);
    }

    @Test
    void publicEnvelopeDescriptionsDeferToTheOperationalOutputProfile(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        String help = dmtool.run("--help").out();
        assertFalse(PublicOutputClaimsTest.containsBlanketClaim(help.replace('\n', ' ')),
                "root help must not universalize the profiled input/output contract");
        assertFalse(PublicOutputClaimsTest.containsJsonInOutSlogan(help),
                "root help must not use the universal JSON-in/JSON-out slogan");
        assertTrue(help.contains("explicit JSON/text artifact profiles"), help);

        JsonNode manifest = MAPPER.readTree(dmtool.run("manifest").out());
        JsonNode seedProfiles = manifestVerb(manifest, "model seed").path("operationalProfiles");
        assertEquals(1, seedProfiles.size(), "model seed has one unconditional output contract");
        assertTrue(selectorIsUnconditional(seedProfiles.get(0).path("when")), seedProfiles::toPrettyString);
        assertEquals("RAW_JSON", seedProfiles.get(0).path("returns").path("kind").asText(),
                "model seed is the positive control: its primary output is an artifact, not an envelope");
        Path model = resource(dir.resolve("outputs"), "order-ruled.dm.json");
        PackagedDmtool.Result seed = dmtool.run("-m", model.toString(), "model", "seed", "--seed", "42");
        assertEquals(0, seed.code(), seed::err);
        JsonNode document = MAPPER.readTree(seed.out());
        assertTrue(document.has("Order"), seed::out);
        assertFalse(document.has("outcome"), "model seed must emit the Document artifact, not an envelope");

        JsonNode readProfiles = manifestVerb(manifest, "field read").path("operationalProfiles");
        assertEquals(1, readProfiles.size(), "field read has one primary output contract");
        JsonNode readReturns = readProfiles.get(0).path("returns");
        assertEquals("RESULT_ENVELOPE", readReturns.path("kind").asText());
        JsonNode readViews = readReturns.path("views");
        assertEquals(1, readViews.size(), "field read has one output view override");
        JsonNode textView = readViews.get(0);
        JsonNode textValues = textView.path("when").path("values").path("--text");
        assertEquals(1, textValues.size(), "the raw-text view has one accepted --text value");
        assertEquals("true", textValues.get(0).asText());
        assertEquals("RAW_TEXT", textView.path("kind").asText());
        PackagedDmtool.Result textRead = dmtool.run("-m", model.toString(), "field", "read",
                "/Order/Quantity", "--text=true");
        assertEquals(0, textRead.code(), textRead::err);
        assertFalse(textRead.out().isBlank(), "the RAW_TEXT view must not discard stdout");
        assertTrue(textRead.out().startsWith("read /Order/Quantity"), textRead::out);

        String schemaDescription = MAPPER.readTree(dmtool.run("schema", "result").out())
                .path("description").asText();
        assertOutputSelectionIsExplained(schemaDescription);
        assertFalse(schemaDescription.contains("every `dmtool` verb"), schemaDescription);

        String skill = Files.readString(Path.of("../skill/SKILL.md"));
        assertOutputSelectionIsExplained(skill);
        assertFalse(skill.contains("universal output envelope every verb emits"),
                "the skill must not contradict raw artifact/catalog routes");
    }

    @Test
    void packagedGlobalTextViewUsesTheParsedBooleanValue(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir, "subscription.dm.json");

        PackagedDmtool.Result enabled = dmtool.run("-m", model.toString(), "field", "read",
                "/Subscription/AutoRenew", "--text=true");
        PackagedDmtool.Result disabled = dmtool.run("-m", model.toString(), "field", "read",
                "/Subscription/AutoRenew", "--text=false");

        assertEquals(0, enabled.code(), enabled::err);
        assertFalse(enabled.out().stripLeading().startsWith("{"), enabled::out);
        assertEquals("read", MAPPER.readTree(disabled.out()).path("outcome").asText(), disabled::out);
    }

    @Test
    void packagedPostEvaluationRefusalsKeepRequiredRuntimeProvenance(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path customModel = resource(dir.resolve("model-eval"), "payment-customtype.dm.json");
        Path customDocument = dir.resolve("custom-document.json");
        Files.writeString(customDocument,
                "{\"Payment\":{\"AccountHolder\":\"Acme\",\"Iban\":\"NOT-AN-IBAN\"}}");
        Path ruleModel = resource(dir.resolve("rule-eval"), "order-ruled.dm.json");
        Path ruleDocument = dir.resolve("rule-document.json");
        Files.writeString(ruleDocument, "{\"Order\":{\"CustomerName\":\"Acme\"}}");

        for (PackagedDmtool.Result refusal : List.of(
                dmtool.run("-m", customModel.toString(), "model", "eval", "--instance",
                        customDocument.toString(), "--strict-custom"),
                dmtool.run("-m", ruleModel.toString(), "rule", "eval", "/Order/EligibilityCheck", "--instance",
                        ruleDocument.toString(), "--strict-custom"))) {
            assertEquals(2, refusal.code(), refusal::out);
            JsonNode envelope = MAPPER.readTree(refusal.out());
            assertEquals("refused", envelope.path("outcome").asText(), refusal::out);
            assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), refusal::out);
            assertTrue(envelope.hasNonNull("engineVersion") && envelope.hasNonNull("toolVersion"), refusal::out);
        }
    }

    @Test
    void packagedComputeStrictModeChecksComputedTargetSupport(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("claim-custom-copy").locale("en_US")
                .rootGroup("Claim", g -> g.customField("Source", f -> f.type("SourceType"))
                        .customField("Copy", f -> f.type("TargetType"))).build().toJson();
        var bound = BoundModel.ofJson(base);
        var source = bound.custom("/Claim/Source");
        var copy = bound.custom("/Claim/Copy");
        String json = EditSession.read(base).addComputation(m -> m.computation(copy).name("CopyIdentifier")
                .calc(source.asString()).message(Dsl.en("Check the copy.")).build()).toJson();
        Path model = dir.resolve("custom.dm.json");
        Path document = dir.resolve("document.json");
        Path registry = dir.resolve("types.json");
        Files.writeString(model, json);
        Files.writeString(document, "{\"Claim\":{\"Source\":\"ready\",\"Copy\":\"stale\"}}");
        String original = Files.readString(document);
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        for (boolean targetRegistered : List.of(true, false)) {
            Files.writeString(registry, targetRegistered ? "{\"SourceType\":{},\"TargetType\":{}}" : "{\"SourceType\":{}}");
            var result = dmtool.run("-m", model.toString(), "model", "compute", "--instance", document.toString(),
                    "--predefined-types", registry.toString(), "--strict-custom");
            assertEquals(targetRegistered ? 0 : 2, result.code(), result::out);
            JsonNode envelope = MAPPER.readTree(result.out());
            assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), result::out);
            assertTrue(envelope.hasNonNull("engineVersion") && envelope.hasNonNull("toolVersion"), result::out);
            if (!targetRegistered) {
                assertEquals("refused", envelope.path("outcome").asText(), result::out);
                assertEquals("Claim/Copy", envelope.path("data").path("unsupported").get(0).path("name").asText());
            }
            assertEquals(original, Files.readString(document));
        }
    }

    @Test
    void packagedPartialValidationReportsEmptyExcessOccurrences(@TempDir Path dir) throws Exception {
        String modelJson = DocumentModelDraft.named("parcel-occurrences-cli").locale("en_US")
                .rootGroup("Parcel", p -> p.stringField("Marker", f -> { })
                        .group("Rows", r -> r.repeatable(2).numberField("Quantity", f -> f.fractionDigits(0))))
                .build().toJson();
        Path model = dir.resolve("parcel.dm.json");
        Files.writeString(model, modelJson);
        var dmtool = new PackagedDmtool(dir);
        for (String cell : List.of("null", "\"\"")) {
            String json = "{\"Parcel\":{\"Marker\":\"ready\",\"Rows\":[{},{},{\"Quantity\":" + cell + "}]}}";
            Path document = dir.resolve("parcel.json");
            Files.writeString(document, json);
            for (String relevant : List.of("[]", "[\"/Parcel/Marker\"]")) {
                var result = dmtool.run("-m", model.toString(), "model", "eval", "--instance", document.toString(),
                        "--no-computations", "--relevant", relevant);
                assertEquals(0, result.code(), result::out);
                var codes = new java.util.ArrayList<String>();
                MAPPER.readTree(result.out()).path("data").path("messages")
                        .forEach(message -> codes.add(message.path("code").asText()));
                codes.sort(String::compareTo);
                assertEquals(relevant.equals("[]") ? List.of() : List.of("zuGrosseKontextnummer", "zuGrosseZeile"),
                        codes, result::out);
                assertEquals(json, Files.readString(document));
                assertEquals(modelJson, Files.readString(model));
            }
        }
    }

    @Test
    void packagedPartialCountsIgnoreExcessDescendantCacheErrors(@TempDir Path dir) throws Exception {
        var base = DocumentModelDraft.named("shipment-cache-cli").locale("en_US")
                .rootGroup("Shipment", s -> s.stringField("Title", f -> { })
                        .group("Manifests", m -> m.repeatable(2).stringField("Label", f -> { })
                                .group("Crates", c -> c.repeatable(2).stringField("Tag", f -> { })
                                        .group("Items", i -> i.repeatable(2)
                                                .numberField("Quantity", f -> f.fractionDigits(0)))))).build().toJson();
        var edit = EditSession.read(base);
        var shipment = edit.group("/Shipment");
        shipment.addRule(b -> b.name("RootCount").code("ROOT2").errorField(shipment.str("Title"))
                .when(shipment.str("Title").filled().and(Dsl.numberOfFilledGroups(shipment.flattenGroup("Manifests*")).eq(2)))
                .message(Dsl.en("Check manifest count.")).build());
        String modelJson = edit.toJson();
        Path model = dir.resolve("shipment.dm.json");
        Files.writeString(model, modelJson);
        var dmtool = new PackagedDmtool(dir);
        for (boolean excess : List.of(false, true)) {
            String items = excess ? "{\"Quantity\":1},{},{\"Quantity\":\"bad\"}" : "{\"Quantity\":\"bad\"}";
            String json = "{\"Shipment\":{\"Title\":\"shipment\",\"Manifests\":[{\"Label\":\"first\","
                    + "\"Crates\":[{\"Tag\":\"crate\",\"Items\":[" + items + "]}]},{\"Label\":\"second\"}]}}";
            Path document = dir.resolve("shipment.json");
            Files.writeString(document, json);
            var result = dmtool.run("-m", model.toString(), "model", "eval", "--instance", document.toString(),
                    "--no-computations", "--relevant", "[\"/Shipment\"]");
            assertEquals(0, result.code(), result::out);
            JsonNode data = MAPPER.readTree(result.out()).path("data");
            assertEquals("partial", data.path("validationMode").asText());
            var codes = new java.util.ArrayList<String>();
            data.path("messages").forEach(message -> codes.add(message.path("code").asText()));
            codes.sort(String::compareTo);
            assertEquals(excess ? List.of("ROOT2", "zuGrosseKontextnummer", "zuGrosseZeile")
                    : List.of("zahlHatUngueltigeZeichen"), codes, result::out);
            assertEquals(json, Files.readString(document));
            assertEquals(modelJson, Files.readString(model));
        }
    }

    @Test
    void packagedFirstFilledRetainsNestedPrefixPolarity(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("claim-first-payout-cli").locale("en_US")
                .rootGroup("Claim", claim -> claim.stringField("Ref", f -> { })
                        .group("Payouts", payouts -> payouts.repeatable(2)
                                .group("Parts", parts -> parts.repeatable(2)
                                        .numberField("Amount", field -> field.fractionDigits(0)))))
                .build().toJson();
        var edit = EditSession.read(base);
        var claim = edit.group("/Claim");
        var ref = claim.str("Ref");
        var first = Dsl.firstFilledNumber(claim.flatten("Payouts*/Parts*/Amount").firstFilledOperand());
        claim.addRule(rule -> rule.name("FirstPayout").code("FIRST").errorField(ref)
                .when(ref.filled().and(first.eq(7))).message(Dsl.en("Review the first payout.")).build());
        String json = edit.toJson();
        Path model = dir.resolve("claim.dm.json");
        Files.writeString(model, json);
        var dmtool = new PackagedDmtool(dir);
        for (boolean prefix : List.of(true, false)) {
            String filled = "{\"Parts\":[{\"Amount\":7}]}";
            String rows = prefix ? "{}," + filled : filled + ",{}";
            String input = "{\"Claim\":{\"Ref\":\"C\",\"Payouts\":[" + rows + "]}}";
            Path document = dir.resolve("claim.json");
            Files.writeString(document, input);
            String[] args = {"-m", model.toString(), "model", "eval", "--instance", document.toString()};
            var evaluated = dmtool.run(args);
            var oracle = CliTestSupport.runKernel(args);
            assertEquals(0, evaluated.code(), evaluated::out);
            assertEquals(0, oracle.code(), oracle::out);
            for (JsonNode envelope : List.of(MAPPER.readTree(evaluated.out()), MAPPER.readTree(oracle.out()))) {
                JsonNode data = envelope.path("data");
                assertEquals(MAPPER.valueToTree(List.of("FIRST")), data.path("fired"), envelope::toString);
                assertEquals(1, data.path("messages").size(), envelope::toString);
                assertEquals(prefix ? "OMISSION_ERROR" : "VALUE_ERROR", data.path("messages").get(0).path("type").asText());
                assertEquals("Claim/Ref", data.path("messages").get(0).path("field").asText());
            }
            assertEquals(input, Files.readString(document));
            assertEquals(json, Files.readString(model));
        }
    }

    @Test
    void packagedNumericParallelValidationRetainsRenderedTokenMultiplicity(@TempDir Path dir) throws Exception {
        var dmtool = new PackagedDmtool(dir);
        for (var scales : List.of(List.of(0, 0), List.of(0, 2), List.of(2, 2))) {
            String base = DocumentModelDraft.named("tariff-rendered-keys-cli").locale("en_US")
                    .rootGroup("Tariff", tariff -> {
                        int ordinal = 0;
                        for (String name : List.of("Offered", "Quoted")) {
                            int scale = scales.get(ordinal++);
                            tariff.group(name, group -> group.repeatable(3).indexField("Deductible")
                                    .numberField("Deductible", field -> field.fractionDigits(scale, 2))
                                    .stringField("Premium", field -> { }));
                        }
                    }).build().toJson();
            var edit = EditSession.read(base);
            var tariff = edit.group("/Tariff");
            var offered = edit.group("/Tariff/Offered").str("Premium");
            var quoted = edit.group("/Tariff/Quoted").str("Premium");
            tariff.addRule(rule -> rule.name("ComparePremiums").code("JOIN").errorField(offered)
                    .when(Dsl.allFieldsFilled(offered.field(), quoted.field()))
                    .message(Dsl.en("Compare the premiums.")).build());
            Path model = dir.resolve("tariff.dm.json");
            String modelJson = edit.toJson();
            Files.writeString(model, modelJson);
            for (String key : List.of("5", "5.25")) {
                var row = Map.of("Deductible", new java.math.BigDecimal(key).setScale(2), "Premium", "p");
                String input = MAPPER.writeValueAsString(Map.of("Tariff",
                        Map.of("Offered", List.of(row), "Quoted", List.of(row))));
                Path document = dir.resolve("tariff.json");
                Files.writeString(document, input);
                String[] args = {"-m", model.toString(), "model", "eval", "--instance", document.toString()};
                var evaluated = dmtool.run(args);
                var oracle = CliTestSupport.runKernel(args);
                assertEquals(0, evaluated.code(), evaluated::out);
                assertEquals(0, oracle.code(), oracle::out);
                JsonNode data = MAPPER.readTree(evaluated.out()).path("data");
                int expected = !scales.get(0).equals(scales.get(1)) && key.equals("5") ? 2 : 1;
                assertEquals(expected, data.path("messages").size(), evaluated::out);
                JsonNode kernelMessages = MAPPER.readTree(oracle.out()).path("data").path("messages");
                assertEquals(expected, kernelMessages.size(), oracle::out);
                for (JsonNode messages : List.of(kernelMessages, data.path("messages"))) {
                    for (JsonNode message : messages) {
                        assertEquals("JOIN", message.path("code").asText());
                        var references = new java.util.ArrayList<JsonNode>();
                        message.path("referenced").forEach(references::add);
                        references.sort(java.util.Comparator.comparing(JsonNode::asText));
                        ((com.fasterxml.jackson.databind.node.ObjectNode) message).set("referenced", MAPPER.valueToTree(references));
                    }
                }
                assertEquals(kernelMessages, data.path("messages"));
                assertEquals(input, Files.readString(document));
                assertEquals(modelJson, Files.readString(model));
            }
        }
    }

    @Test
    void packagedEmptyPatternFailsOnlyWhenReached(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("parcel-pattern-cli").locale("en_US")
                .rootGroup("Parcel", g -> g.stringField("Ref", f -> { }).stringField("Label", f -> { }))
                .build().toJson();
        var edit = EditSession.read(base);
        var parcel = edit.group("/Parcel");
        var ref = parcel.str("Ref");
        var label = parcel.str("Label");
        var reached = label.violates("");
        parcel.addRule(b -> b.name("Reached").code("REACHED").errorField(ref)
                .when(ref.filled().and(reached)).message(Dsl.en("Review the label.")).build());
        parcel.addRule(b -> b.name("Hidden").code("HIDDEN").errorField(ref)
                .when(ref.eq("hide").and(reached)).message(Dsl.en("Review the label.")).build());
        parcel.addRule(b -> b.name("Nonempty").code("NONEMPTY").errorField(ref)
                .when(ref.filled().and(label.violates(".*"))).message(Dsl.en("Review the label.")).build());
        Path model = dir.resolve("parcel.dm.json");
        Files.writeString(model, edit.toJson());
        var dmtool = new PackagedDmtool(dir);
        for (String body : List.of("{\"Ref\":\"parcel\"}", "{\"Ref\":\"parcel\",\"Label\":\"ready\"}")) {
            Path document = dir.resolve("parcel.json");
            String input = "{\"Parcel\":" + body + "}";
            Files.writeString(document, input);
            String[] args = {"-m", model.toString(), "model", "eval", "--instance", document.toString()};
            var evaluated = dmtool.run(args);
            var oracle = CliTestSupport.runKernel(args);
            assertEquals(0, evaluated.code(), evaluated::out);
            assertEquals(0, oracle.code(), oracle::out);
            assertEquals(MAPPER.readTree(oracle.out()).path("data").path("fired"),
                    MAPPER.readTree(evaluated.out()).path("data").path("fired"));
            assertEquals(List.of("REACHED"), MAPPER.convertValue(
                    MAPPER.readTree(evaluated.out()).path("data").path("fired"), List.class));
            assertEquals(input, Files.readString(document));
        }
    }

    @Test
    void packagedSelectedGroupsExpandColumnsAndRetainOmissionType(@TempDir Path dir) throws Exception {
        var session = EditSession.read(DocumentModelDraft.named("selected-group-cli").locale("en_US")
                .rootGroup("Order", order -> order.numberField("Needle", f -> {})
                        .group("Packages", packages -> packages.repeatable(3).indexField("Code").stringField("Code", f -> {})
                                .group("Pair", pair -> pair.numberField("A", f -> {}).numberField("B", f -> {}))
                                .group("Rows", rows -> rows.repeatable(3).numberField("A", f -> {}).numberField("B", f -> {}))))
                .build().toJson());
        var order = session.group("/Order");
        var needle = order.num("Needle");
        order.addRule(rule -> rule.name("Row").code("ROW").errorField(needle)
                .when(Dsl.atLeastOneFieldValueIncludedInValueList(List.of(needle.field()), order.flattenGroup("Packages/Rows*").forIndex("P1")))
                .message(Dsl.en("Check selected row.")).build());
        order.addRule(rule -> rule.name("Pair").code("PAIR").errorField(needle)
                .when(Dsl.noFieldValueIncludedInValueList(List.of(needle.field()), order.flattenGroup("Packages/Pair").forIndex("P1")))
                .message(Dsl.en("Check selected pair.")).build());
        Path model = dir.resolve("selected.dm.json");
        Path document = dir.resolve("selected.json");
        Files.writeString(model, session.toJson());
        Files.writeString(document, """
                {"Order":{"Needle":10,"Packages":[{"Code":"P1","Rows":[{"A":7,"B":10}]},{"Code":"P2","Rows":[{"A":7,"B":7}]}]}}
                """);
        String[] args = {"-m", model.toString(), "model", "eval", "--instance", document.toString(), "--no-computations"};
        var result = new PackagedDmtool(dir).run(args);
        var oracle = CliTestSupport.runKernel(args);
        assertEquals(0, result.code(), result::out);
        assertEquals(0, oracle.code(), oracle::out);
        JsonNode actual = MAPPER.readTree(result.out()).path("data").path("messages");
        JsonNode expected = MAPPER.readTree(oracle.out()).path("data").path("messages");
        var normalized = new java.util.ArrayList<List<String>>();
        for (JsonNode messages : List.of(expected, actual)) {
            var signatures = new java.util.ArrayList<String>();
            for (JsonNode message : messages) {
                for (String channel : List.of("referenced", "fillToFix")) {
                    if (!message.has(channel)) continue;
                    var pointers = new java.util.ArrayList<JsonNode>();
                    message.path(channel).forEach(pointers::add);
                    pointers.sort(java.util.Comparator.comparing(JsonNode::asText));
                    ((com.fasterxml.jackson.databind.node.ObjectNode) message).set(channel, MAPPER.valueToTree(pointers));
                }
                signatures.add(message.toString());
            }
            signatures.sort(String::compareTo);
            normalized.add(signatures);
        }
        assertEquals(normalized.get(0), normalized.get(1));
        var types = new HashMap<String, String>();
        actual.forEach(message -> types.put(message.path("code").asText(), message.path("type").asText()));
        assertEquals(Map.of("ROW", "VALUE_ERROR", "PAIR", "OMISSION_ERROR"), types);
    }

    @Test
    void packagedLiteralDecodingPreservesOnePassAndSelectedKeys(@TempDir Path dir) throws Exception {
        String base = DocumentModelDraft.named("dispatch-literal-cli").locale("en_US")
                .rootGroup("Dispatch", g -> g.stringField("Note", f -> f.lineBreaks())
                        .stringField("PairCopy", f -> f.lineBreaks())
                        .stringField("RepeatedCopy", f -> f.lineBreaks())
                        .group("Bins", b -> b.repeatable(2).indexField("Code")
                                .stringField("Code", f -> f.lineBreaks()).numberField("Stock", f -> f.fractionDigits(0))))
                .build().toJson();
        var bound = BoundModel.ofJson(base);
        var group = bound.group("/Dispatch");
        var note = bound.str("/Dispatch/Note");
        var stock = bound.num("/Dispatch/Bins/Stock");
        var edit = EditSession.read(base);
        edit.addRule(m -> m.rule(group).name("ComparePair").code("PAIR").errorField(note)
                .when(note.eq("A\r\nB")).message(Dsl.en("Review the note.")).build());
        edit.addRule(m -> m.rule(group).name("CompareRepeated").code("REPEATED").errorField(note)
                .when(note.eq("A\r\r\nB")).message(Dsl.en("Review the note.")).build());
        edit.addRule(m -> m.rule(group).name("SelectBin").code("SELECTED").errorField(note)
                .when(note.filled().and(stock.forIndex("A\r\nB").eq(11)))
                .message(Dsl.en("Review stock.")).build());
        for (var literal : Map.of("PairCopy", "A\r\nB", "RepeatedCopy", "A\r\r\nB").entrySet()) {
            var target = bound.str("/Dispatch/" + literal.getKey());
            edit.addComputation(m -> m.computation(target).name("Write" + literal.getKey())
                    .calc(new io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral(literal.getValue()))
                    .message(Dsl.en("Review the copied note.")).build());
        }
        Path model = dir.resolve("dispatch.dm.json");
        Path document = dir.resolve("dispatch.json");
        Files.writeString(model, edit.toJson());
        String input = MAPPER.writeValueAsString(Map.of("Dispatch", Map.of("Note", "A\r\r\nB",
                "Bins", List.of(Map.of("Code", "A\nB", "Stock", 11)))));
        Files.writeString(document, input);
        var dmtool = new PackagedDmtool(dir);
        var evaluated = dmtool.run("-m", model.toString(), "model", "eval", "--instance", document.toString());
        assertEquals(0, evaluated.code(), () -> evaluated.out() + evaluated.err());
        var fired = new HashSet<String>();
        MAPPER.readTree(evaluated.out()).path("data").path("fired").forEach(code -> fired.add(code.asText()));
        var oracle = CliTestSupport.runKernel("-m", model.toString(), "model", "eval", "--instance", document.toString());
        assertEquals(0, oracle.code(), () -> oracle.out() + oracle.err());
        var kernelFired = new HashSet<String>();
        MAPPER.readTree(oracle.out()).path("data").path("fired").forEach(code -> kernelFired.add(code.asText()));
        assertEquals(kernelFired, fired, evaluated::out);
        assertTrue(fired.containsAll(Set.of("REPEATED", "SELECTED")), evaluated::out);
        assertFalse(fired.contains("PAIR"), evaluated::out);
        var computed = dmtool.run("-m", model.toString(), "model", "compute", "--instance", document.toString());
        assertEquals(0, computed.code(), () -> computed.out() + computed.err());
        var values = new HashMap<String, String>();
        for (JsonNode cell : MAPPER.readTree(computed.out()).path("data").path("computed")) {
            assertEquals("value", cell.path("outcome").asText(), computed::out);
            values.put(cell.path("field").asText(), cell.path("value").asText());
        }
        assertEquals(Map.of("Dispatch/PairCopy", "A\nB", "Dispatch/RepeatedCopy", "A\r\nB"), values, computed::out);
        assertEquals(input, Files.readString(document));
    }

    @Test
    void packagedRuleEvalRunsANonPersistedCandidate(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir.resolve("candidate-rule-eval"), "order-ruled.dm.json");
        String before = Files.readString(model);
        Path document = dir.resolve("candidate-rule-eval.json");
        Files.writeString(document, "{\"Order\":{\"Quantity\":\"150\"}}");

        PackagedDmtool.Result result = dmtool.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", "/Order/Quantity",
                "--instance", document.toString());

        assertEquals(0, result.code(), () -> result.out() + result.err());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("fired", envelope.path("data").path("verdict").asText(), result::out);
        assertEquals("DM_INTERPRETER", envelope.path("engine").asText(), result::out);
        assertFalse(envelope.path("written").asBoolean(), result::out);
        assertEquals(before, Files.readString(model), "packaged candidate eval must leave the model byte-identical");

        PackagedDmtool.Result missing = dmtool.run("-m", model.toString(), "rule", "eval",
                "--condition", "[Quantity] > 100", "--field", "/Order/DefinitelyMissing",
                "--instance", document.toString());
        assertEquals(2, missing.code(), () -> missing.out() + missing.err());
        JsonNode refusal = MAPPER.readTree(missing.out());
        assertEquals("RK_NO_SUCH_FIELD", refusal.path("diagnostics").path(0).path("code").asText(), missing::out);
        assertEquals(before, Files.readString(model), "a packaged rejected candidate must not write the model");
    }

    @Test
    void packagedBatchWritesReplayableKernelObservations(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir.resolve("batch-observations"), "subscription.dm.json");
        Path ops = dir.resolve("batch-observations.json");
        String condition = "[MonthlyFee] > 1000";
        Files.writeString(ops, "[{\"id\":\"fee-cap\",\"verb\":\"rule\",\"args\":[\"check\",\"--field\","
                + "\"/Subscription/Addons/MonthlyFee\",\"--condition\"," + MAPPER.writeValueAsString(condition)
                + ",\"--code\",\"CAP\"]}]");
        Path observations = dir.resolve("kernel-observations.json");

        PackagedDmtool.Result first = dmtool.run("-m", model.toString(), "batch", ops.toString(),
                "--observations", observations.toString());

        assertEquals(0, first.code(), () -> first.out() + first.err());
        String firstBytes = Files.readString(observations);
        JsonNode artifact = MAPPER.readTree(firstBytes);
        assertEquals(2, artifact.path("schemaVersion").asInt());
        assertEquals("fee-cap", artifact.path("observations").get(0).path("id").asText());
        assertTrue(artifact.path("observations").get(0).path("valid").asBoolean());
        JsonNode firstEnvelope = MAPPER.readTree(first.out());
        assertTrue(firstEnvelope.path("written").asBoolean());
        assertEquals(observations.toAbsolutePath().normalize().toString(), firstEnvelope.path("output").asText());
        assertFalse(firstBytes.contains(dir.toString()), "the packaged artifact must exclude local paths");
        JsonNode served = MAPPER.readTree(dmtool.run("schema", "batch").out())
                .path("input").path("observationArtifact");
        assertEquals("BatchObservations", served.path("title").asText());

        PackagedDmtool.Result second = dmtool.run("-m", model.toString(), "batch", ops.toString(),
                "--observations", observations.toString());
        assertEquals(first.code(), second.code(), second::err);
        assertEquals(firstBytes, Files.readString(observations));
    }

    @Test
    void packagedLiteralTextPositionalDoesNotSelectTheTextView(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir, "subscription.dm.json");
        Path literalTextSpec = dir.resolve("--text");
        Files.writeString(literalTextSpec, """
                {
                  "computedField": "/Subscription/Billing/EffectiveFee",
                  "name": "LiteralTextFile",
                  "alternatives": [{"operation":"[/Subscription/Billing/BaseFee]"}],
                  "messages": [{"locale":"en_US","text":"Literal text."},{"locale":"de_DE","text":"Wörtlicher Text."}]
                }
                """);

        PackagedDmtool.Result direct = dmtool.run("-m", model.toString(), "computation", "add", "--dry-run",
                "--", "--text");
        assertEquals(0, direct.code(), () -> direct.out() + direct.err());
        assertEquals("preview", MAPPER.readTree(direct.out()).path("outcome").asText(),
                "a positional named --text must retain the primary JSON envelope");

        Path ops = dir.resolve("literal-text-ops.json");
        Files.writeString(ops, "[{\"id\":\"literal-text\",\"verb\":\"computation\","
                + "\"args\":[\"add\",\"--dry-run\",\"--\",\"--text\"]}]");
        Path observations = dir.resolve("literal-text-observations.json");
        PackagedDmtool.Result batch = dmtool.run("-m", model.toString(), "batch", ops.toString(),
                "--observations", observations.toString());

        assertEquals(0, batch.code(), () -> batch.out() + batch.err());
        JsonNode child = MAPPER.readTree(batch.out()).path("data").path("results").get(0);
        assertTrue(child.has("result"), "batch must receive the child's JSON envelope, not a text-rendered stdout");
        assertFalse(child.has("stdout"), "a positional named --text is not the global option");
        assertEquals("preview", child.path("result").path("outcome").asText());
        assertEquals("literal-text",
                MAPPER.readTree(Files.readString(observations)).path("observations").get(0).path("id").asText());
    }

    @Test
    void packagedRejectedModeCombinationsMatchNoOperationalProfile(@TempDir Path dir) throws Exception {
        PackagedDmtool dmtool = new PackagedDmtool(dir);
        Path model = resource(dir.resolve("modes"), "order-ruled.dm.json");
        Path schema = dir.resolve("mode.schema.json");
        Files.writeString(schema, "{\"title\":\"R\",\"type\":\"object\"}");
        List<List<String>> rejected = List.of(
                List.of("-m", model.toString(), "patterns", "date-order", "--arg",
                        "earlier=/Order/OrderDate", "--dry-run=false"),
                List.of("-m", model.toString(), "model", "report", "--root", "/Order"),
                List.of("model", "import-jsonschema", "--schema", schema.toString(), "--out-dir",
                        dir.resolve("bundle").toString(), "-o", dir.resolve("ignored.json").toString()),
                List.of("model", "import-jsonschema", "--schema", schema.toString(), "--out-dir",
                        dir.resolve("component-bundle").toString(), "--component", "R"),
                List.of("-m", model.toString(), "meta", "/Order", "--dry-run"));

        for (List<String> command : rejected) {
            assertTrue(CommandContracts.matchingProfiles(command).isEmpty(),
                    () -> "rejected invocation matched an operational profile: " + command);
            PackagedDmtool.Result result = dmtool.run(command);
            assertEquals(2, result.code(), () -> command + ": " + result.out() + result.err());
            assertTrue(result.out().isBlank(), () -> command + " promised no envelope: " + result.out());
            assertFalse(result.err().isBlank(), () -> command + " lacks its corrective usage error");
        }
        assertFalse(Files.exists(dir.resolve("bundle")));
        assertFalse(Files.exists(dir.resolve("ignored.json")));
    }

    private static Path resource(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        try (InputStream input = DmtoolPublicContractTest.class.getResourceAsStream("/models/" + name)) {
            assertTrue(input != null, () -> "missing /models/" + name);
            Files.writeString(target, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        return target;
    }

    private static Properties packagedVersionProperties() throws Exception {
        Path snapshot = Path.of(System.getProperty("dmtool.versionProperties", "")).toAbsolutePath();
        assertTrue(Files.isRegularFile(snapshot),
                () -> "publicContractTest needs -Ddmtool.versionProperties from the packaged build, got "
                        + snapshot);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(snapshot)) {
            properties.load(input);
        }
        assertTrue(properties.containsKey("sourceRevision"), () -> snapshot + " lacks sourceRevision");
        assertTrue(properties.containsKey("sourceState"), () -> snapshot + " lacks sourceState");
        return properties;
    }

    private static JsonNode manifestVerb(JsonNode manifest, String name) {
        for (JsonNode verb : manifest.path("verbs")) {
            if (name.equals(verb.path("verb").asText())) return verb;
        }
        throw new AssertionError("manifest has no " + name);
    }

    private static void assertOutputSelectionIsExplained(String prose) {
        assertTrue(prose.contains("matching `operationalProfiles[].returns.views[]` overrides "
                        + "`operationalProfiles[].returns.kind`"),
                () -> "public output contract must state view-before-primary precedence: " + prose);
        for (String term : List.of("operationalProfiles[].returns.views[]", "operationalProfiles[].returns.kind")) {
            assertTrue(prose.contains(term), () -> "public output contract omits " + term + ": " + prose);
        }
        // DERIVED from the enum, not a hand-kept list. The list form could only check the kinds someone
        // remembered to add to it, so `NDJSON_STREAM` reached the enum and this literal while the skill was
        // left behind — the guard named the term it was itself missing elsewhere. Now a sixth constant fails
        // here until BOTH published surfaces explain it, which is what CLAUDE.md's verb row advertises.
        for (CommandContracts.ReturnKind kind : CommandContracts.ReturnKind.values()) {
            assertTrue(prose.contains(kind.name()),
                    () -> "public output contract omits the " + kind.name() + " return kind: " + prose);
        }
    }

    private static boolean selectorIsUnconditional(JsonNode when) {
        return when.path("supplied").isEmpty()
                && when.path("omitted").isEmpty()
                && when.path("anySet").isEmpty()
                && when.path("noneSet").isEmpty()
                && when.path("values").isEmpty();
    }
}
