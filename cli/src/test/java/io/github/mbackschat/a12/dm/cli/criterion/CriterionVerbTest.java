package io.github.mbackschat.a12.dm.cli.criterion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion;

/**
 * The {@code criterion} verbs (FEATURE-GAPS G81 closure): a criterion is declared, read back in A12's stored shape,
 * and removed through dmtool alone, which is what lets {@code field remove} take a field a criterion named. The kernel
 * gate decides admission (KERNEL-FINDINGS KF671); the verbs refuse only a taken name and a missing one. Own domain
 * (a customer register): {@code Number} is required, so a criterion over it is admitted, and {@code Nickname} is
 * optional, so one over it is not.
 */
class CriterionVerbTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> NUMBER_KEY = Map.of(
            "name", "UniqueCustomerNumber",
            "fields", List.of(Map.of("fullName", "/Customer/Number")),
            "errorMessage", List.of(Map.of("locale", "en_US", "text", "Customer number $/Customer/Number.value$ is taken.")));

    private static Path customer(Path dir) throws Exception {
        Path model = dir.resolve("customer.dm.json");
        Files.writeString(model, DocumentModelDraft.named("customer").locale("en_US")
                .rootGroup("Customer", c -> c
                        .stringField("Number", f -> f.required())
                        .stringField("Nickname", f -> { }))
                .build().toJson());
        return model;
    }

    private static Path write(Path dir, String name, Object json) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, JSON.writeValueAsString(json));
        return file;
    }

    private static JsonNode run(Path model, String... verb) throws Exception {
        List<String> args = new ArrayList<>(List.of("-m", model.toString()));
        args.addAll(List.of(verb));
        return JSON.readTree(CliTestSupport.run(args.toArray(new String[0])).out());
    }

    private static String verdict(JsonNode envelope) {
        return envelope.path("outcome").asText() + " " + envelope.path("diagnostics").path(0).path("code").asText("");
    }

    @Test
    void aCriterionIsAddedReadBackInA12sShapeAndRemoved(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        assertEquals(JSON.readTree("{\"documentUniquenessCriteria\":[]}"), run(model, "criterion", "read").path("data"));

        JsonNode added = run(model, "criterion", "add", write(dir, "key.json", NUMBER_KEY).toString());
        assertEquals("applied", added.path("outcome").asText(), added::toString);
        assertEquals(JSON.valueToTree(NUMBER_KEY), run(model, "criterion", "read", "UniqueCustomerNumber").path("data"),
                "the read entry is the spec, so it can be added elsewhere as is");
        assertEquals(JSON.valueToTree(List.of(NUMBER_KEY)),
                JSON.readTree(Files.readString(model)).path("content").path("documentUniquenessCriteria"));
        assertTrue(run(model, "model", "check").path("valid").asBoolean(), "A12 admits the criterion");

        JsonNode removed = run(model, "criterion", "remove", "UniqueCustomerNumber");
        assertEquals("applied", removed.path("outcome").asText(), removed::toString);
        assertTrue(JSON.readTree(Files.readString(model)).path("content").path("documentUniquenessCriteria").isMissingNode(),
                "the emptied member goes with its last criterion");
    }

    @Test
    void removingTheCriterionFreesItsFieldForFieldRemove(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        run(model, "criterion", "add", write(dir, "key.json", NUMBER_KEY).toString());
        assertEquals("refused RK_REFERENCED", verdict(run(model, "field", "remove", "/Customer/Number")));

        run(model, "criterion", "remove", "UniqueCustomerNumber");
        assertEquals("applied ", verdict(run(model, "field", "remove", "/Customer/Number")));
    }

    @Test
    void theVerbsRefuseATakenOrMissingNameAndTheKernelRejectsAnInadmissibleCriterion(@TempDir Path dir)
            throws Exception {
        Path model = customer(dir);
        Path key = write(dir, "key.json", NUMBER_KEY);
        run(model, "criterion", "add", key.toString());
        String before = Files.readString(model);
        Path overOptional = write(dir, "nick.json", Map.of("name", "UniqueNickname",
                "fields", List.of(Map.of("fullName", "/Customer/Nickname")),
                "errorMessage", List.of(Map.of("locale", "en_US", "text", "Nickname taken."))));

        assertEquals(List.of(
                        "refused RK_NAME_EXISTS",
                        "refused RK_NO_SUCH_UNIQUENESS_CRITERION",
                        "refused RK_NO_SUCH_UNIQUENESS_CRITERION"),
                List.of(verdict(run(model, "criterion", "add", key.toString())),
                        verdict(run(model, "criterion", "remove", "NoSuchKey")),
                        verdict(run(model, "criterion", "read", "NoSuchKey"))));
        JsonNode rejected = run(model, "criterion", "add", overOptional.toString());
        assertEquals("rejected", rejected.path("outcome").asText(), rejected::toString);
        assertEquals(before, Files.readString(model), "no refusal or rejection writes");
    }

    @Test
    void applyAddsReadsAndRemovesACriterionInOneSequence(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        Map<String, Object> add = new java.util.LinkedHashMap<>(Map.of("target", "criterion", "op", "add"));
        add.putAll(NUMBER_KEY);
        Path ops = write(dir, "ops.json", List.of(add, Map.of("target", "criterion", "op", "read"),
                Map.of("target", "criterion", "op", "remove", "name", "UniqueCustomerNumber"),
                Map.of("target", "field", "op", "remove", "fieldPathInModel", "/Customer/Number")));

        JsonNode applied = run(model, "apply", ops.toString());
        assertTrue(applied.path("data").path("committed").asBoolean(), applied::toString);
        JsonNode read = applied.path("data").path("results").path(1).path("data");
        assertEquals(JSON.valueToTree(List.of(NUMBER_KEY)), read.path("documentUniquenessCriteria"), applied::toString);

    }

    /**
     * Each apply refusal names its op and its code. The model is fresh and the duplicate's field stays in it, so only
     * the name gate can refuse the second add, and a refused op rolls the whole sequence back.
     */
    @Test
    void applyRefusesATakenNameAMissingOneAndANamelessSpec(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        Map<String, Object> add = new java.util.LinkedHashMap<>(Map.of("target", "criterion", "op", "add"));
        add.putAll(NUMBER_KEY);
        Map<String, Object> nameless = new java.util.LinkedHashMap<>(add);
        nameless.remove("name");
        String before = Files.readString(model);
        Map<String, String> verdicts = new java.util.LinkedHashMap<>();
        Map<String, List<?>> sequences = Map.of(
                "taken", List.of(add, add),
                "missing", List.of(Map.of("target", "criterion", "op", "remove", "name", "NoSuchKey")),
                "nameless", List.of(nameless));
        for (String label : List.of("taken", "missing", "nameless")) {
            JsonNode applied = run(model, "apply", write(dir, label + ".json", sequences.get(label)).toString());
            JsonNode data = applied.path("data");
            JsonNode failed = data.path("results").path(data.path("failedAt").asInt());
            verdicts.put(label, data.path("committed").asBoolean() + " " + data.path("failedAt").asInt() + " "
                    + failed.path("diagnostics").path(0).path("code").asText());
        }
        assertEquals(Map.of(
                "taken", "false 1 RK_NAME_EXISTS",
                "missing", "false 0 RK_NO_SUCH_UNIQUENESS_CRITERION",
                "nameless", "false 0 RK_APPLY_OP_FAILED"), verdicts);
        assertEquals(before, Files.readString(model), "no refused sequence writes");
    }

    /** An op that takes no args still names the corrective for a stray one, rather than an empty list. */
    @Test
    void anArgOnTheArglessReadOpGetsACorrective(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        JsonNode applied = run(model, "apply", write(dir, "read.json",
                List.of(Map.of("target", "criterion", "op", "read", "name", "UniqueCustomerNumber"))).toString());
        JsonNode diagnostic = applied.path("data").path("results").path(0).path("diagnostics").path(0);
        assertEquals(List.of("RK_UNKNOWN_ARG", "op 'criterion read' takes no args beyond target and op; remove 'name'"),
                List.of(diagnostic.path("code").asText(), diagnostic.path("fix").asText()), applied::toString);
    }

    @Test
    void criteriaReadBackInModelOrder(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        Map<String, Object> second = new java.util.LinkedHashMap<>(NUMBER_KEY);
        second.put("name", "AnotherNumberKey");
        run(model, "criterion", "add", write(dir, "first.json", NUMBER_KEY).toString());
        run(model, "criterion", "add", write(dir, "second.json", second).toString());
        List<String> names = new ArrayList<>();
        run(model, "criterion", "read").path("data").path("documentUniquenessCriteria")
                .forEach(entry -> names.add(entry.path("name").asText()));
        assertEquals(List.of("UniqueCustomerNumber", "AnotherNumberKey"), names);
        assertTrue(run(model, "field", "remove", "/Customer/Number").path("diagnostics").path(0).path("fix").asText()
                .startsWith("first `criterion remove` the document uniqueness criteria AnotherNumberKey, "
                        + "UniqueCustomerNumber, which name a field at or below /Customer/Number (re-declare them"),
                "two criteria take the plural, listed by name");
    }

    /**
     * A stored entry reads as A12 reads it: its fields are a set, and an item without {@code fullName} names none
     * (A12's {@code JsonToFieldFullNamesConverter}, kernel 31.1.1). The kernel admits the stored model, so the read
     * shows what A12 holds rather than what the file spells.
     */
    @Test
    void aStoredEntryReadsAsA12ReadsIt(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        run(model, "criterion", "add", write(dir, "key.json", NUMBER_KEY).toString());
        ObjectNode stored = (ObjectNode) JSON.readTree(Files.readString(model));
        ArrayNode fields = (ArrayNode) stored.path("content").path("documentUniquenessCriteria").path(0).path("fields");
        fields.addObject().put("fullName", "/Customer/Number");
        fields.addObject();
        Files.writeString(model, JSON.writeValueAsString(stored));
        assertTrue(run(model, "model", "check").path("valid").asBoolean(), "A12 admits the stored entry");
        assertEquals(JSON.valueToTree(NUMBER_KEY), run(model, "criterion", "read", "UniqueCustomerNumber").path("data"));
    }

    /** The envelope's entry and the stored one come from two writers; they agree, empty members left out by both. */
    @Test
    void theReadEntryIsTheStoredEntry() throws Exception {
        for (UniquenessCriterion criterion : List.of(
                new UniquenessCriterion("Full", List.of("/Customer/Number"), Map.of("en_US", "Taken.")),
                new UniquenessCriterion("Textless", List.of("/Customer/Number"), Map.of()),
                new UniquenessCriterion("", List.of(), Map.of()))) {
            JsonNode stored = JSON.readTree(DocumentModelDraft.named("customer").locale("en_US")
                    .rootGroup("Customer", c -> c.stringField("Number", f -> f.required()))
                    .uniquenessCriterion(criterion).build().toJson())
                    .path("content").path("documentUniquenessCriteria").path(0);
            assertEquals(stored, CriterionSpec.toJson(criterion), criterion::toString);
        }
    }
}
