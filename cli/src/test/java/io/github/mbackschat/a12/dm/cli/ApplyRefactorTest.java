package io.github.mbackschat.a12.dm.cli;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Refactor ops inside the {@code apply} session (CLI-SPEC §6/§7) — {@code rename}/{@code move} run as
 * op-records on the shared {@code EditSession}: the refactor's own safety gate runs at the op's turn
 * (against the live, transiently-edited model — so it sees prior ops' effects), references are rewritten
 * via the kernel's {@code MoveSupportDM}, and the change rides the one terminal gate + atomic rollback.
 * A refused refactor (e.g. a name collision) becomes a mid-sequence failure → the WHOLE sequence rolls
 * back, nothing written, with the gate's corrective diagnostic in the failing op's envelope.
 *
 * <p>Fixture: {@code /Order} with an unreferenced {@code Note}, a {@code Shipping} subgroup whose
 * {@code Zip} is referenced by a rule {@code ZipGuard} at {@code /Order} (external to {@code Shipping}),
 * a {@code Billing} subgroup, and a {@code Money} typedef used by {@code /Order/Price}.
 */
class ApplyRefactorTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path orderModel(Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Note", FieldKind.STRING)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("Billing", b -> b.field("Tax", FieldKind.STRING)))
                .build()
                .toJson();
        String full = EditSession.read(base)
                .addTypeDefinition("Money", FieldKind.NUMBER)
                .addTypedefField("/Order", "Price", "Money")
                .addRule(m -> m.rule(m.group("/Order")).name("ZipGuard")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Shipping/Zip").notFilled())
                        .code("ZIP_REQ").severity(Severity.ERROR).message(en("A zip is required.")).build())
                .toJson();
        Path f = dir.resolve("order.dm.json");
        Files.writeString(f, full);
        return f;
    }

    private static JsonNode applyOps(Path model, Path dir, String opsJson) throws Exception {
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, opsJson);
        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        JsonNode summary = M.readTree(res.out());
        // attach the raw exit code so callers can assert it (apply: 0 committed, 1 rolled back)
        ((com.fasterxml.jackson.databind.node.ObjectNode) summary).put("_exit", res.code());
        return summary;
    }

    @Test
    void renamesAReferencedFieldAndRewritesReferrersInOneSession(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        JsonNode j = applyOps(model, dir, """
                [ {"target":"field","op":"rename","fieldPathInModel":"/Order/Shipping/Zip","to":"Postal"} ]""");

        assertEquals(0, j.path("_exit").asInt(), () -> "committed apply exits 0: " + j);
        assertTrue(j.path("committed").asBoolean());
        assertEquals("staged", j.path("results").get(0).path("outcome").asText());
        assertEquals("/Order/Shipping/Postal", j.path("results").get(0).path("changed").path("to").asText());
        // the referrer was rewritten, so the written model still validates (a dangle would fail)
        assertTrue(Files.readString(model).contains("/Order/Shipping/Postal"));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code(),
                "the rewritten reference keeps the model valid");
    }

    @Test
    void theHeadlineMixedSequence_addRenameThenRuleOnTheNewName(@TempDir Path dir) throws Exception {
        // add a field, rename an existing referenced field, then add a rule referencing the RENAMED field —
        // edits and a refactor interleave in ONE atomic session, committed together.
        Path model = orderModel(dir);
        JsonNode j = applyOps(model, dir, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"field","op":"rename","fieldPathInModel":"/Order/Shipping/Zip","to":"Postal"},
                  {"target":"rule","op":"add","field":"/Order/Discount","code":"DISC_NEG",
                   "condition":"FieldFilled(/Order/Discount) And [/Order/Discount] < 0",
                   "messages":[{"locale":"en_US","text":"Discount must not be negative."}]} ]""");

        assertEquals(0, j.path("_exit").asInt(), () -> "committed: " + j);
        assertTrue(j.path("committed").asBoolean());
        assertEquals(3, j.path("results").size());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code());
        assertTrue(Files.readString(model).contains("/Order/Shipping/Postal"));
    }

    @Test
    void theGateSeesPriorOpsEffects_moveIntoAJustAddedGroup(@TempDir Path dir) throws Exception {
        // op0 adds /Order/Archive; op1 moves /Order/Note INTO it. The move's gate (destination must exist)
        // checks the LIVE session state — so it sees the group op0 just added, not the original model.
        Path model = orderModel(dir);
        JsonNode j = applyOps(model, dir, """
                [ {"target":"group","op":"add","parent":"/Order","name":"Archive"},
                  {"target":"field","op":"move","fieldPathInModel":"/Order/Note","to":"/Order/Archive"} ]""");

        assertEquals(0, j.path("_exit").asInt(), () -> "committed: " + j);
        assertTrue(j.path("committed").asBoolean());
        assertEquals("/Order/Archive/Note", j.path("results").get(1).path("changed").path("to").asText());
        assertTrue(Files.readString(model).contains("/Order/Archive/Note"));
    }

    @Test
    void movesAGroupRewritingExternalReferrers(@TempDir Path dir) throws Exception {
        // ZipGuard (at /Order) references /Order/Shipping/Zip from OUTSIDE Shipping; moving Shipping under
        // Billing rewrites that external reference to /Order/Billing/Shipping/Zip.
        Path model = orderModel(dir);
        JsonNode j = applyOps(model, dir, """
                [ {"target":"group","op":"move","groupPathInModel":"/Order/Shipping","to":"/Order/Billing"} ]""");

        assertEquals(0, j.path("_exit").asInt(), () -> "committed: " + j);
        assertTrue(j.path("committed").asBoolean());
        assertTrue(Files.readString(model).contains("/Order/Billing/Shipping/Zip"));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code());
    }

    @Test
    void renamesARuleInApply(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        JsonNode j = applyOps(model, dir, """
                [ {"target":"rule","op":"rename","rulePathInModel":"/Order/ZipGuard","to":"ZipRequired"} ]""");

        assertEquals(0, j.path("_exit").asInt(), () -> "committed: " + j);
        assertTrue(j.path("committed").asBoolean());
        assertTrue(Files.readString(model).contains("ZipRequired"));
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code());
    }

    @Test
    void renamesATypedefInApplyRepointingFields(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        JsonNode j = applyOps(model, dir, """
                [ {"target":"typedef","op":"rename","typeDefId":"Money","to":"Currency"} ]""");

        assertEquals(0, j.path("_exit").asInt(), () -> "committed: " + j);
        assertTrue(j.path("committed").asBoolean());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "model", "validate").code(),
                "every using field was repointed to the new id");
    }

    @Test
    void aRefusedRefactorRollsBackTheWholeSequence(@TempDir Path dir) throws Exception {
        // op0 adds a field; op1 renames /Order/Note to "Shipping" — colliding with the existing Shipping
        // group. The gate refuses (RK_NAME_EXISTS); the WHOLE sequence rolls back, nothing is written.
        Path model = orderModel(dir);
        String before = Files.readString(model);
        JsonNode j = applyOps(model, dir, """
                [ {"target":"field","op":"add","group":"/Order","name":"Fee","kind":"NUMBER"},
                  {"target":"field","op":"rename","fieldPathInModel":"/Order/Note","to":"Shipping"} ]""");

        assertEquals(1, j.path("_exit").asInt(), () -> "a rolled-back apply exits 1: " + j);
        assertFalse(j.path("committed").asBoolean());
        assertEquals(1, j.path("failedAt").asInt());
        assertFalse(j.path("written").asBoolean());
        JsonNode failed = j.path("results").get(1);
        assertEquals("refused", failed.path("outcome").asText(), () -> "the gate refused: " + failed);
        assertEquals("RK_NAME_EXISTS", failed.path("diagnostics").get(0).path("code").asText());
        assertEquals(before, Files.readString(model), "the model is untouched after rollback");
    }
}
