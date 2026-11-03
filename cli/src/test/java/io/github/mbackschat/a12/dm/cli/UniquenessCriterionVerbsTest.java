package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * A field a document uniqueness criterion names is in use, though no rule reads it (KERNEL-FINDINGS KF671): the
 * read verbs show the criterion by name, and the delete gates refuse what A12 would refuse after the edit, a model
 * whose criterion names a missing field. {@code --cascade} refuses too, since it removes rules and computations and
 * no verb edits a criterion. Own domain (insurance policies and their branch offices).
 */
class UniquenessCriterionVerbsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code PolicyKey} over {@code /Policy/PolicyNo}, {@code OfficeKey} over {@code /Policy/Office/Code}; {@code Holder} unused. */
    private static Path policy(Path dir) throws Exception {
        String json = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .stringField("Holder", f -> { })
                        .group("Office", o -> o.stringField("Code", f -> f.required())))
                .build().toJson();
        String criteria = "\"documentUniquenessCriteria\":["
                + "{\"name\":\"PolicyKey\",\"fields\":[{\"fullName\":\"/Policy/PolicyNo\"}],"
                + "\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"Policy $/Policy/PolicyNo$ exists\"}]},"
                + "{\"name\":\"OfficeKey\",\"fields\":[{\"fullName\":\"/Policy/Office/Code\"}],"
                + "\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"Office $/Policy/Office/Code$ exists\"}]}],";
        int at = json.indexOf("\"modelRoot\"");
        Path model = dir.resolve("policy.json");
        Files.writeString(model, json.substring(0, at) + criteria + json.substring(at));
        return model;
    }

    private static JsonNode data(CliTestSupport.Result result) throws Exception {
        return JSON.readTree(result.out()).path("data");
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }

    @Test
    void whereUsedNamesTheCriterionOfAFieldAndNoneForAGroup(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        JsonNode field = data(CliTestSupport.run("-m", model.toString(), "where-used", "/Policy/PolicyNo"));
        JsonNode group = data(CliTestSupport.run("-m", model.toString(), "where-used", "/Policy/Office"));

        assertEquals(List.of("PolicyKey"), strings(field.path("documentUniquenessCriteria")));
        assertEquals(1, field.path("count").asInt());
        assertEquals(List.of(), strings(group.path("documentUniquenessCriteria")));
    }

    @Test
    void aFieldOnlyACriterionNamesIsNotUnreferenced(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        JsonNode usage = data(CliTestSupport.run("-m", model.toString(), "model", "usage"));
        JsonNode report = data(CliTestSupport.run("-m", model.toString(), "model", "report"));

        assertEquals(List.of("/Policy/Holder"), strings(usage.path("unreferenced")));
        assertEquals(List.of("PolicyKey"), strings(usage.path("documentUniquenessCriteria").path("/Policy/PolicyNo")));
        assertEquals(List.of("/Policy/Holder"), strings(report.path("unreferenced")));
    }

    @Test
    void removingAFieldOrGroupACriterionNamesIsRefusedEvenWithCascade(@TempDir Path dir) throws Exception {
        Path model = policy(dir);
        String before = Files.readString(model);

        List<String[]> attempts = List.of(
                new String[] {"field", "remove", "/Policy/PolicyNo"},
                new String[] {"field", "remove", "/Policy/PolicyNo", "--cascade"},
                new String[] {"group", "remove", "/Policy/Office"},
                new String[] {"group", "remove", "/Policy/Office", "--cascade"});
        List<String> actual = new ArrayList<>();
        for (String[] attempt : attempts) {
            String[] args = new String[attempt.length + 2];
            args[0] = "-m";
            args[1] = model.toString();
            System.arraycopy(attempt, 0, args, 2, attempt.length);
            var result = CliTestSupport.run(args);
            JsonNode envelope = JSON.readTree(result.out());
            actual.add(String.join(" ", attempt) + " → " + result.code() + " " + envelope.path("outcome").asText() + " "
                    + envelope.path("diagnostics").path(0).path("code").asText() + " "
                    + strings(envelope.path("data").path("referencedBy").path("documentUniquenessCriteria")));
            assertTrue(envelope.path("diagnostics").path(0).path("fix").asText().contains("out of the document uniqueness criteria"),
                    result.out());
        }

        assertEquals(List.of(
                "field remove /Policy/PolicyNo → 2 refused RK_REFERENCED [PolicyKey]",
                "field remove /Policy/PolicyNo --cascade → 2 refused RK_REFERENCED [PolicyKey]",
                "group remove /Policy/Office → 2 refused RK_REFERENCED [OfficeKey]",
                "group remove /Policy/Office --cascade → 2 refused RK_REFERENCED [OfficeKey]"), actual);
        assertEquals(before, Files.readString(model), "a refusal writes nothing");
    }

    /** The control for the refusal: a field no criterion names is still removed. */
    @Test
    void aFieldNoCriterionNamesIsRemoved(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "remove", "/Policy/Holder").code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "check").code());
    }

    /**
     * No pre-edit gate is needed on {@code field modify}: making a criterion's field optional is refused in A12's own
     * sentence and writes nothing, as for any edit A12 refuses (KF671 admission).
     */
    @Test
    void makingACriterionsFieldOptionalIsRefusedInA12sSentence(@TempDir Path dir) throws Exception {
        Path model = policy(dir);
        String before = Files.readString(model);
        Path spec = dir.resolve("optional.json");
        Files.writeString(spec, "{\"group\":\"/Policy\",\"name\":\"PolicyNo\",\"required\":false}");

        var result = CliTestSupport.run("-m", model.toString(), "field", "modify", spec.toString());

        assertTrue(result.code() != 0, result.out());
        assertTrue(result.out().contains("Document Uniqueness Criterion 'PolicyKey': The field '/Policy/PolicyNo' is not set as required."),
                result.out());
        assertEquals(before, Files.readString(model), "a refusal writes nothing");
    }

    /** The parent keeps its criteria, which A12 admits over the mounted field; the sub-model carries none (KF671). */
    @Test
    void extractingAGroupLeavesTheCriteriaWithTheParent(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        var extract = CliTestSupport.run("-m", model.toString(), "group", "extract", "/Policy/Office",
                "--reference", "office");

        assertEquals(0, extract.code(), extract.out() + extract.err());
        Path office = dir.resolve("office.dm.json");
        assertTrue(Files.exists(office), "the sub-model is written beside the model: " + extract.out());
        assertTrue(!Files.readString(office).contains("documentUniquenessCriteria"), Files.readString(office));
        assertTrue(Files.readString(model).contains("OfficeKey"));
        assertEquals(0, CliTestSupport.run("-m", office.toString(), "model", "check").code());
    }
}
