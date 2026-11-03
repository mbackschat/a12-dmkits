package io.github.mbackschat.a12.dm.cli;

import static io.github.mbackschat.a12.dm.adapter.UniquenessCriteriaJson.criterion;
import static io.github.mbackschat.a12.dm.adapter.UniquenessCriteriaJson.withCriteria;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
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

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * A field a document uniqueness criterion names is in use, though no rule reads it (KERNEL-FINDINGS KF671): the
 * read verbs show the criterion by name, and the delete gates refuse what A12 would refuse after the edit, a model
 * whose criterion names a missing field. {@code --cascade} refuses too, since it removes rules and computations and
 * no verb edits a criterion. Own domain (insurance policies and their branch offices).
 */
class UniquenessCriterionVerbsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code PolicyKey} over {@code /Policy/PolicyNo} and {@code OfficeKey} over {@code /Policy/Office/Code}. The
     * controls sit beside them: {@code Holder} is required like a criterion field but named by none, and read by
     * the rule {@code HolderGiven}; {@code Notes} is a group no criterion reaches into.
     */
    private static Path policy(Path dir) throws Exception {
        String base = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .stringField("Holder", f -> f.required())
                        .group("Office", o -> o.stringField("Code", f -> f.required()))
                        .group("Notes", n -> n.stringField("Text", f -> { })))
                .build().toJson();
        String ruled = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Policy")).name("HolderGiven")
                        .errorField(m.str("/Policy/Holder")).when(m.str("/Policy/Holder").notFilled())
                        .code("HOLDER_MISSING").severity(Severity.ERROR)
                        .message(en("The holder is missing.")).build())
                .toJson();
        Path model = dir.resolve("policy.dm.json");
        Files.writeString(model, withCriteria(ruled,
                criterion("PolicyKey", "Policy $/Policy/PolicyNo$ exists", "/Policy/PolicyNo"),
                criterion("OfficeKey", "Office $/Policy/Office/Code$ exists", "/Policy/Office/Code")));
        return model;
    }

    private static JsonNode envelope(CliTestSupport.Result result) throws Exception {
        return JSON.readTree(result.out());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }

    private static List<String> keys(JsonNode object) {
        List<String> out = new ArrayList<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static CliTestSupport.Result run(Path model, String... verb) {
        String[] args = new String[verb.length + 2];
        args[0] = "-m";
        args[1] = model.toString();
        System.arraycopy(verb, 0, args, 2, verb.length);
        return CliTestSupport.run(args);
    }

    /** {@code <exit> <outcome> <first code> <referencedBy criteria>} — one line per refusal or application. */
    private static String verdict(CliTestSupport.Result result) throws Exception {
        JsonNode envelope = envelope(result);
        return result.code() + " " + envelope.path("outcome").asText() + " "
                + envelope.path("diagnostics").path(0).path("code").asText() + " "
                + strings(envelope.path("data").path("referencedBy").path("documentUniquenessCriteria"));
    }

    @Test
    void whereUsedNamesTheCriterionOfAFieldAndNoneForAGroup(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        JsonNode field = envelope(run(model, "where-used", "/Policy/PolicyNo")).path("data");
        JsonNode group = envelope(run(model, "where-used", "/Policy/Office")).path("data");

        assertEquals(List.of("PolicyKey"), strings(field.path("documentUniquenessCriteria")));
        assertEquals(1, field.path("count").asInt());
        assertEquals(List.of(), strings(group.path("documentUniquenessCriteria")));
    }

    /** The usage map lists exactly the criterion-named fields, and a field only a criterion names is not dead. */
    @Test
    void aFieldOnlyACriterionNamesIsNotUnreferenced(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        JsonNode usage = envelope(run(model, "model", "usage")).path("data");
        JsonNode report = envelope(run(model, "model", "report")).path("data");

        assertEquals(List.of("/Policy/PolicyNo", "/Policy/Office/Code"), keys(usage.path("documentUniquenessCriteria")));
        assertEquals(List.of("OfficeKey"), strings(usage.path("documentUniquenessCriteria").path("/Policy/Office/Code")));
        assertEquals(List.of("/Policy/Notes/Text"), strings(usage.path("unreferenced")));
        assertEquals(List.of("/Policy/Notes/Text"), strings(report.path("unreferenced")));
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
            var result = run(model, attempt);
            actual.add(String.join(" ", attempt) + " → " + verdict(result));
            assertTrue(envelope(result).path("diagnostics").path(0).path("fix").asText()
                    .startsWith("first remove from the document uniqueness criteria"), result.out());
        }

        assertEquals(List.of(
                "field remove /Policy/PolicyNo → 2 refused RK_REFERENCED [PolicyKey]",
                "field remove /Policy/PolicyNo --cascade → 2 refused RK_REFERENCED [PolicyKey]",
                "group remove /Policy/Office → 2 refused RK_REFERENCED [OfficeKey]",
                "group remove /Policy/Office --cascade → 2 refused RK_REFERENCED [OfficeKey]"), actual);
        assertEquals(before, Files.readString(model), "a refusal writes nothing");
    }

    /**
     * The controls for the refusal, in the same model: {@code --cascade} still cascades a rule-read field no
     * criterion names, and a group no criterion reaches into is removed; the criteria survive both.
     */
    @Test
    void whatNoCriterionNamesIsStillRemoved(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        String cascaded = verdict(run(model, "field", "remove", "/Policy/Holder", "--cascade"));
        String group = verdict(run(model, "group", "remove", "/Policy/Notes"));

        assertEquals(List.of("0 applied  []", "0 applied  []"), List.of(cascaded, group));
        assertEquals(0, run(model, "model", "check").code());
        assertEquals(List.of("/Policy/PolicyNo", "/Policy/Office/Code"),
                keys(envelope(run(model, "model", "usage")).path("data").path("documentUniquenessCriteria")));
    }

    /**
     * Inside {@code apply} each op is gated on the model as it stands, so removing a criterion's field and adding
     * it back is refused at the removal, though A12 would admit the final model: a deliberate over-strictness
     * (CLI-SPEC §6), since {@code field modify} and {@code field rename} change a field in place.
     */
    @Test
    void applyRefusesRemovingACriterionFieldEvenIfALaterOpAddsItBack(@TempDir Path dir) throws Exception {
        Path model = policy(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"remove","fieldPathInModel":"/Policy/PolicyNo"},
                  {"target":"field","op":"add","group":"/Policy","name":"PolicyNo","kind":"STRING","required":true} ]""");

        var result = run(model, "apply", ops.toString());

        assertEquals(2, result.code(), result.out());
        assertTrue(result.out().contains("RK_REFERENCED"), result.out());
        assertEquals(before, Files.readString(model), "a refusal writes nothing");
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

        var result = run(model, "field", "modify", spec.toString());

        assertEquals("1 rejected", result.code() + " " + envelope(result).path("outcome").asText(), result.out());
        assertTrue(result.out().contains("Document Uniqueness Criterion 'PolicyKey': The field '/Policy/PolicyNo' is not set as required."),
                result.out());
        assertEquals(before, Files.readString(model), "a refusal writes nothing");
    }

    /**
     * The parent keeps its criteria, which A12 admits over the mounted field, and still refuses to lose that field
     * through the mount; the sub-model carries none (KF671).
     */
    @Test
    void extractingAGroupLeavesTheCriteriaWithTheParent(@TempDir Path dir) throws Exception {
        Path model = policy(dir);

        var extract = run(model, "group", "extract", "/Policy/Office", "--reference", "office");

        assertEquals(0, extract.code(), extract.out() + extract.err());
        Path office = dir.resolve("office.dm.json");
        assertTrue(!Files.readString(office).contains("documentUniquenessCriteria"), Files.readString(office));
        assertEquals(0, run(office, "model", "check").code());
        assertEquals(0, run(model, "model", "check").code());
        assertEquals(List.of("/Policy/PolicyNo", "/Policy/Office/Code"),
                keys(envelope(run(model, "model", "usage")).path("data").path("documentUniquenessCriteria")));
        assertEquals("2 refused RK_REFERENCED [OfficeKey]", verdict(run(model, "group", "remove", "/Policy/Office")));
    }
}
