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

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

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

        Path duplicate = write(dir, "dup.json", List.of(add, add));
        String before = Files.readString(model);
        JsonNode refused = run(model, "apply", duplicate.toString());
        assertEquals(false, refused.path("data").path("committed").asBoolean(), refused::toString);
        assertEquals(before, Files.readString(model), "a refused op rolls the whole sequence back");
    }
}
