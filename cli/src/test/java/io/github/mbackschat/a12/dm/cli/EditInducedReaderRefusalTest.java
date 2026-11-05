package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * A spec value A12's model reader refuses is the kernel rejecting the edit, never a malformed {@code -m} model
 * (CLI-CONFORMANCE-GAPS CG27, closed). A12's reader parses each message locale with {@code LocaleUtils}, which
 * refuses {@code en_us} (KERNEL-FINDINGS KF290), so a spec carrying it yields a model only the edit made
 * unreadable. The discriminator is the source: the control stores the same locale in the {@code -m} model itself
 * and makes an otherwise valid edit, and that stays the model's input refusal. Own domain (a customer register).
 */
class EditInducedReaderRefusalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> criterion(String name, String locale) {
        return Map.of("name", name, "fields", List.of(Map.of("fullName", "/Customer/Number")),
                "errorMessage", List.of(Map.of("locale", locale, "text", "That customer number is taken.")));
    }

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

    private static CliTestSupport.Result run(Path model, String... verb) {
        List<String> args = new ArrayList<>(List.of("-m", model.toString()));
        args.addAll(List.of(verb));
        return CliTestSupport.run(args.toArray(new String[0]));
    }

    private static void assertTheEditIsRejected(Path model, String before, CliTestSupport.Result result)
            throws Exception {
        JsonNode envelope = JSON.readTree(result.out());
        assertEquals(1, result.code(), result::out);
        JsonNode results = envelope.path("data").path("results");
        assertTheGateRejected(envelope.has("diagnostics") && !envelope.path("diagnostics").isEmpty()
                ? envelope : results.path(results.size() - 1), result);
        assertTrue(result.err().isBlank(), result::err);
        assertEquals(before, Files.readString(model));
    }

    private static void assertTheGateRejected(JsonNode rejection, CliTestSupport.Result result) {
        assertEquals("rejected", rejection.path("outcome").asText(), result::out);
        assertEquals("KERNEL_CONFIRMED", rejection.path("verification").asText(), result::out);
        JsonNode diagnostic = rejection.path("diagnostics").path(0);
        assertEquals("KERNEL", diagnostic.path("source").asText(), result::out);
        assertTrue(diagnostic.path("summary").asText().contains("en_us"), result::out);
        assertTrue(diagnostic.path("where").path("arg").isMissingNode(), result::out);
    }

    @Test
    void aCriterionLocaleTheReaderRefusesRejectsTheEdit(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        String before = Files.readString(model);
        assertTheEditIsRejected(model, before,
                run(model, "criterion", "add", write(dir, "key.json", criterion("UniqueNumber", "en_us")).toString()));
    }

    @Test
    void aRuleMessageLocaleTheReaderRefusesRejectsTheEdit(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        String before = Files.readString(model);
        Path spec = write(dir, "rule.json", Map.of("group", "/Customer", "field", "/Customer/Nickname",
                "condition", "FieldNotFilled(Nickname)", "code", "NICKNAME", "name", "NicknameRequired",
                "messages", List.of(Map.of("locale", "en_us", "text", "A nickname is required."))));
        assertTheEditIsRejected(model, before, run(model, "rule", "add", spec.toString()));
    }

    /**
     * The aggregate keeps its shape: the staged op and then the terminal gate's rejection ride {@code results[]},
     * with {@code failedAt} null because no op failed on its own, and nothing is written.
     */
    @Test
    void anApplyOpCarryingTheLocaleIsATerminalGateRejection(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        String before = Files.readString(model);
        Map<String, Object> op = new java.util.LinkedHashMap<>(Map.of("target", "criterion", "op", "add"));
        op.putAll(criterion("UniqueNumber", "en_us"));
        CliTestSupport.Result result = run(model, "apply", write(dir, "ops.json", List.of(op)).toString());

        assertTheEditIsRejected(model, before, result);
        JsonNode data = JSON.readTree(result.out()).path("data");
        assertEquals(false, data.path("committed").asBoolean(true), result::out);
        assertTrue(data.path("failedAt").isNull(), result::out);
        assertEquals(2, data.path("results").size(), result::out);
    }

    /**
     * {@code model new} builds its model from flags alone, so the kernel's verdict on it is the verdict on those
     * flags: a locale the reader refuses and a name the consistency check refuses both come back as the
     * rejection envelope, where they used to leave as a prose line on stderr with no envelope at all.
     */
    @Test
    void aNewModelTheKernelRefusesIsARejectionEnvelope(@TempDir Path dir) throws Exception {
        CliTestSupport.Result unreadable = CliTestSupport.run("model", "new", "--id", "shop", "--locale", "en_us",
                "-o", dir.resolve("shop.json").toString());
        assertEquals(1, unreadable.code(), unreadable::out);
        assertTheGateRejected(JSON.readTree(unreadable.out()), unreadable);

        CliTestSupport.Result inconsistent = CliTestSupport.run("model", "new", "--id", "a b", "--locale", "en_US");
        JsonNode envelope = JSON.readTree(inconsistent.out());
        assertEquals(1, inconsistent.code(), inconsistent::out);
        assertEquals("rejected", envelope.path("outcome").asText(), inconsistent::out);
        assertEquals("MVK_PATH_INVALID", envelope.path("diagnostics").path(0).path("code").asText(), inconsistent::out);
        assertTrue(Files.notExists(dir.resolve("shop.json")));
    }

    /** The control: the model as read already carries the refused locale, so the refusal stays the model's. */
    @Test
    void theSameLocaleStoredInTheModelStaysAModelInputRefusal(@TempDir Path dir) throws Exception {
        Path model = customer(dir);
        run(model, "criterion", "add", write(dir, "first.json", criterion("UniqueNumber", "en_US")).toString());
        Files.writeString(model, Files.readString(model).replaceAll("(\"locale\"\\s*:\\s*)\"en_US\"", "$1\"en_us\""));
        assertTrue(Files.readString(model).contains("\"en_us\""), "the stored criterion now carries en_us");

        CliTestSupport.Result result = run(model, "criterion", "add",
                write(dir, "second.json", criterion("SecondNumberKey", "en_US")).toString());
        JsonNode envelope = JSON.readTree(result.out());
        assertEquals(2, result.code(), result::out);
        assertEquals("refused", envelope.path("outcome").asText(), result::out);
        assertEquals("RK_MODEL_INPUT_INVALID", envelope.path("diagnostics").path(0).path("code").asText(), result::out);
        assertEquals(model.toString(), envelope.path("diagnostics").path(0).path("where").path("arg").asText(),
                result::out);
    }
}
