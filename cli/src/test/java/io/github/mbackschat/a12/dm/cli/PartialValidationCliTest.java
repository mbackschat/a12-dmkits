package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * G43 — {@code model eval --relevant} exposes the interpreter's already kernel-matched partial-validation
 * boundary. The CLI encoding is a JSON array of instance paths: {@code [*]} (or {@code [0]}) is the kernel's
 * {@code ALL_SEMANTIC} and {@code [n]} selects one repetition; an omitted index on a repeatable segment is
 * refused, never guessed — see {@link #anOmittedIndexOnARepeatableSegmentIsRefusedWithBothCorrectives} below.
 */
class PartialValidationCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parallelDispatchCompressesSelectorsWithoutChangingTheirInputSyntax(@TempDir Path dir) throws Exception {
        var session = EditSession.read(DocumentModelDraft.named("warehouse-partial-cli").locale("en_US")
                .rootGroup("Warehouse", root -> {
                    root.repeatable(2);
                    for (String name : List.of("Orders", "Stock")) {
                        root.group(name, group -> group.repeatable(5).indexField("Code")
                                .numberField("Code", field -> field.fractionDigits(0))
                                .numberField("Quantity", field -> field.fractionDigits(0)));
                    }
                }).build().toJson());
        var orders = session.group("/Warehouse/Orders");
        var stock = session.group("/Warehouse/Stock");
        orders.addRule(rule -> rule.name("Allocation").code("ALLOCATION").errorField(orders.num("Quantity"))
                .when(Dsl.groupFilled(orders.ref()).or(stock.num("Quantity").filled()))
                .message(Dsl.en("Check allocation.")).build());
        Path model = dir.resolve("warehouse.dm.json");
        Files.writeString(model, session.toJson());
        Path document = document(dir, """
                {"Warehouse":[{"Orders":[{"Code":1,"Quantity":1},{"Code":2,"Quantity":1}],
                "Stock":[{"Code":1,"Quantity":1},{"Code":3,"Quantity":1}]}]}
                """);
        for (boolean second : List.of(false, true)) {
            String relevant = "[\"/Warehouse[*]/Orders[*]/Code\",\"/Warehouse[*]/Stock[*]\","
                    + "\"/Warehouse[1]/Orders[1]/Quantity\""
                    + (second ? ",\"/Warehouse[1]/Orders[2]/Quantity\"" : "") + "]";
            JsonNode result = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                    "--instance", document.toString(), "--no-computations", "--relevant", relevant));
            assertEquals(second ? 3 : 0, messages(result, "ALLOCATION"));
        }
    }

    @Test
    void completeConcreteSelectionsUseDeclaredCapacity(@TempDir Path dir) throws Exception {
        Path doc = document(dir, """
                {"Delivery":{"Reference":"dispatch","Labels":[{"Code":"north"},{"Code":"south"}]}}
                """);
        for (int capacity : List.of(2, 3)) {
            var session = EditSession.read(DocumentModelDraft.named("delivery-coverage-cli").locale("en_US")
                    .rootGroup("Delivery", delivery -> delivery.stringField("Reference", field -> {})
                            .group("Labels", labels -> labels.repeatable(capacity).stringField("Code", field -> {}))
                            .group("Review", review -> review.group("Checks", checks ->
                                    checks.repeatable(capacity).stringField("Code", field -> {}))))
                    .build().toJson());
            var delivery = session.group("/Delivery");
            var reference = delivery.str("Reference");
            delivery.addRule(rule -> rule.name("NoUrgentLabel").code("NO_URGENT").errorField(reference)
                    .when(reference.filled().and(Dsl.noFieldValueIncludedInValueList(
                            List.of("urgent"), delivery.flatten("Labels*/Code"))))
                    .message(Dsl.en("No urgent label.")).build());
            delivery.addRule(rule -> rule.name("ReviewEmpty").code("REVIEW_EMPTY").errorField(reference)
                    .when(reference.filled().and(delivery.group("Review").notFilled()))
                    .message(Dsl.en("Review is empty.")).build());
            delivery.addRule(rule -> rule.name("ChecksEmpty").code("CHECKS_EMPTY").errorField(reference)
                    .when(reference.filled().and(Dsl.noGroupFilled(delivery.flattenGroup("Review/Checks*"))))
                    .message(Dsl.en("No check is present.")).build());
            Path model = dir.resolve("delivery-" + capacity + ".dm.json");
            Files.writeString(model, session.toJson());
            JsonNode result = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                    "--instance", doc.toString(), "--no-computations", "--relevant",
                    "[\"/Delivery/Reference\",\"/Delivery/Labels[1]/Code\",\"/Delivery/Labels[2]/Code\","
                            + "\"/Delivery/Review/Checks[1]/Code\",\"/Delivery/Review/Checks[2]/Code\"]"));
            assertEquals(capacity == 2, fired(result).contains("NO_URGENT"), "declared capacity=" + capacity);
            assertEquals(capacity == 2, fired(result).contains("REVIEW_EMPTY"), "parent coverage at capacity=" + capacity);
            assertEquals(capacity == 2, fired(result).contains("CHECKS_EMPTY"), "missing group coverage at capacity=" + capacity);
        }
    }

    @Test
    void relevantPageRunsOnlyItsRulesPlusGlobalFields(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, """
                { "Order": {} }
                """);

        JsonNode full = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations"));
        assertEquals(Set.of("NAME_MISSING", "CODE_MISSING", "GLOBAL_MISSING"), fired(full));

        JsonNode partial = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations",
                "--relevant", "[\"/Order/Name\"]"));
        assertEquals("partial", partial.path("validationMode").asText());
        assertEquals(Set.of("NAME_MISSING", "GLOBAL_MISSING"), fired(partial),
                "the selected page field runs; an omitted peer does not; a global field is auto-relevant");
    }

    @Test
    void concreteAndWildcardRepetitionIndicesMapToRelevantEntity(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, """
                { "Order": { "Lines": [
                  { "Quantity": "one" },
                  { "Quantity": "two" }
                ] } }
                """);

        JsonNode rowTwo = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations",
                "--relevant", "[\"/Order/Lines[2]/Quantity\"]"));
        assertEquals(1, messages(rowTwo, "QUANTITY_MISSING"));
        assertTrue(StreamSupport.stream(rowTwo.path("messages").spliterator(), false)
                .filter(message -> "QUANTITY_MISSING".equals(message.path("code").asText()))
                .allMatch(message -> message.path("field").asText().contains("Lines[2]")));

        JsonNode everyRow = data(CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--no-computations",
                "--relevant", "[\"/Order/Lines[*]/Quantity\"]"));
        assertEquals(2, messages(everyRow, "QUANTITY_MISSING"));
    }

    @Test
    void malformedOrUnknownRelevantPointerRefusesBeforeEvaluation(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, "{ \"Order\": {} }");

        // [0] is A12's "all repetitions" and is now ACCEPTED; a negative index is what has no reading.
        var malformed = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--relevant", "[\"/Order/Lines[-1]/Quantity\"]");
        assertEquals(2, malformed.code());
        assertTrue(malformed.err().contains("positive 1-based index"), malformed::err);

        var unknown = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--relevant", "[\"/Order/Nmae\"]");
        assertEquals(2, unknown.code());
        assertTrue(unknown.err().contains("/Order/Nmae"), unknown::err);
        assertTrue(unknown.err().contains("/Order/Name"), unknown::err);
    }

    /**
     * <b>The declared break.</b> An omitted index on a repeatable segment used to mean ALL repetitions here and
     * means repetition 1 in A12 — the same spelling, opposite scopes. Reinterpreting it silently would narrow an
     * existing caller's validation from every row to the first, with a clean pass as the only symptom, so it is
     * refused with both explicit forms named instead.
     */
    @Test
    void anOmittedIndexOnARepeatableSegmentIsRefusedWithBothCorrectives(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, "{ \"Order\": {} }");

        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--relevant", "[\"/Order/Lines/Quantity\"]");

        assertEquals(2, res.code(), () -> "an ambiguous scope is refused, never guessed: " + res.err());
        assertTrue(res.err().contains("/Order/Lines[0]"), () -> "names the ALL form: " + res.err());
        assertTrue(res.err().contains("/Order/Lines[1]"), () -> "and the first-row form: " + res.err());
    }

    /**
     * The bound on that refusal: a <b>non-repeatable</b> segment has exactly one instance, so an omitted index
     * there is unambiguous under both readings and stays legal. Without this the refusal would read as "always
     * write an index", which is not the rule and would be noise on every ordinary selection.
     */
    @Test
    void butAnOmittedIndexOnANonRepeatableSegmentStaysLegal(@TempDir Path dir) throws Exception {
        Path model = model(dir);
        Path doc = document(dir, "{ \"Order\": {} }");

        var res = CliTestSupport.run("-m", model.toString(), "model", "eval",
                "--instance", doc.toString(), "--relevant", "[\"/Order/Name\"]");

        assertEquals(0, res.code(), () -> "one instance, nothing to disambiguate: " + res.err());
    }

    private static Path model(Path dir) throws Exception {
        String base = DocumentModelDraft.named("partial-cli").locale("en_US")
                .rootGroup("Order", order -> order
                        .stringField("Name", field -> {})
                        .stringField("Code", field -> {})
                        .stringField("GlobalFlag", field -> field.global())
                        .group("Lines", lines -> lines.repeatable(5).stringField("Quantity", field -> {})))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("NameMissing").errorField(m.str("/Order/Name"))
                .when(m.str("/Order/Name").notFilled())
                .code("NAME_MISSING").severity(Severity.ERROR).message(Dsl.en("Name missing.")).build());
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("CodeMissing").errorField(m.str("/Order/Code"))
                .when(m.str("/Order/Code").notFilled())
                .code("CODE_MISSING").severity(Severity.ERROR).message(Dsl.en("Code missing.")).build());
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("GlobalMissing").errorField(m.str("/Order/GlobalFlag"))
                .when(m.str("/Order/GlobalFlag").notFilled())
                .code("GLOBAL_MISSING").severity(Severity.ERROR).message(Dsl.en("Global missing.")).build());
        session.addRule(m -> m.rule(m.group("/Order"))
                .name("QuantityMissing").errorField(m.str("/Order/Lines/Quantity"))
                .when(m.str("/Order/Lines/Quantity").filled())
                .code("QUANTITY_MISSING").severity(Severity.ERROR).message(Dsl.en("Quantity supplied.")).build());
        Path model = dir.resolve("partial.dm.json");
        Files.writeString(model, session.toJson());
        return model;
    }

    private static Path document(Path dir, String json) throws Exception {
        Path doc = dir.resolve("document-" + Integer.toHexString(json.hashCode()) + ".json");
        Files.writeString(doc, json);
        return doc;
    }

    private static JsonNode data(CliTestSupport.Result result) throws Exception {
        assertEquals(0, result.code(), result::err);
        return MAPPER.readTree(result.out()).path("data");
    }

    private static Set<String> fired(JsonNode data) {
        return StreamSupport.stream(data.path("fired").spliterator(), false)
                .map(JsonNode::asText).collect(java.util.stream.Collectors.toSet());
    }

    private static int messages(JsonNode data, String code) {
        int count = 0;
        for (JsonNode message : data.path("messages")) {
            if (code.equals(message.path("code").asText())) {
                count++;
            }
        }
        return count;
    }
}
