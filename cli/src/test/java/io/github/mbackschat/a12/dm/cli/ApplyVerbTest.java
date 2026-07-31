package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * The {@code apply} session verb (CLI-SPEC §7) — the north-star: one read → ordered surgery on ONE
 * {@code EditSession} → one terminal kernel gate → one write, with <b>atomic rollback</b>. Distinct from
 * {@code batch} (stateless per-op dispatch): {@code apply}'s ops are op-records {@code {target,op,…args}}
 * applied to a shared model, each emitting a per-op envelope (a successful edit is {@code staged} until the
 * terminal gate commits the whole sequence). This first increment covers the structure ops (field/group).
 */
class ApplyVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void appliesAnOrderedSequenceAtomically(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"group","op":"add","parent":"/Order","name":"Notes"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals(2, j.path("ops").asInt());
        assertTrue(j.path("written").asBoolean());
        assertEquals(2, j.path("results").size());
        assertEquals("staged", j.path("results").get(0).path("outcome").asText());
        // the wrapper carries a human net-effect summary — per-op results are staged/written:false (the write is
        // ATOMIC at the wrapper, not per op), so an agent reads `summary`/`committed`, not the per-op flags (eval).
        assertTrue(j.path("summary").asText().contains("committed") && j.path("summary").asText().contains("written"),
                () -> "the apply wrapper summarizes the net effect: " + res.out());
        String written = Files.readString(model);
        assertTrue(written.contains("Discount") && written.contains("Notes"));
    }

    @Test
    void theApplyTerminalGateSurfacesTheKernelCauseNotABareNotValid(@TempDir Path dir) throws Exception {
        // probe 07 (root-cause sweep): a rejection at the apply TERMINAL gate (EditSession.validate) used to
        // flatten to a cause-less "the edited model is not kernel-valid" — unlike the per-edit gate
        // (ModelDocument.validate), which probe 04 fixed inline. The duplication was the defect; both now
        // route through ModelConsistencyException.notKernelValid, so the apply commit NAMES the cause.
        // Trigger: a duplicate field name slips the per-op precheck and is caught only by the terminal gate.
        Path model = CliTestSupport.orderRuled(dir);   // /Order/Items already has a Sku field
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order/Items","name":"Sku","kind":"STRING"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertNotEquals(0, res.code(), () -> "the duplicate must be rejected: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean());
        assertFalse(j.path("written").asBoolean());
        assertTrue(res.out().contains("the same name"),
                () -> "the apply terminal-gate rejection must surface the kernel cause, not a bare 'not kernel-valid': "
                        + res.out());
    }

    @Test
    void appliesGroupModifyTypedefEnumAndConfigRolesInOneSession(@TempDir Path dir) throws Exception {
        // apply-parity for the verbs widened this session: group modify --index-field (G24), the rich
        // typedef add ENUM spec (G25), and config modify --role (G26) all reachable as op-records.
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"group","op":"modify","groupPathInModel":"/Subscription/Addons","index-field":"Name"},
                  {"target":"typedef","op":"add","id":"Tier","kind":"ENUM",
                   "enum":{"values":[{"value":"GOLD"},{"value":"SILVER"}]}},
                  {"target":"config","op":"modify","role":["admin","editor"]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).path("committed").asBoolean(), () -> "committed: " + res.out());
        // the three edits all landed in one write
        assertEquals("Name", M.readTree(CliTestSupport.run("-m", model.toString(),
                "group", "read", "/Subscription/Addons").out()).path("data").path("indexField").asText());
        assertEquals("admin,editor", M.readTree(CliTestSupport.run("-m", model.toString(),
                "config", "read").out()).path("data").path("annotations").path("roles").asText());
        assertTrue(Files.readString(model).contains("Tier"), "the enum typedef is persisted");
    }

    @Test
    void appliesATypedefModifyInOneSession(@TempDir Path dir) throws Exception {
        // apply/batch parity (probe 10): `typedef modify` rides apply too — add a shared type then modify its
        // scale in one atomic session; the modify op shares the standalone verb's path.
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"typedef","op":"add","id":"Money","kind":"NUMBER","number":{"maxFractionalDigits":2,"unit":"AMOUNT"}},
                  {"target":"typedef","op":"modify","id":"Money","kind":"NUMBER","number":{"maxFractionalDigits":3,"unit":"AMOUNT"}} ]""");
        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).path("committed").asBoolean(), () -> "committed: " + res.out());
        JsonNode cfg = M.readTree(CliTestSupport.run("-m", model.toString(), "typedef", "read", "Money").out())
                .path("data").path("number");
        assertEquals(3, cfg.path("maxFractionalDigits").asInt(), "the typedef modify op landed in-session");
    }

    @Test
    void appliesAFieldRetypeInOneSession(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // re-type (field modify) now rides an apply transaction: add a STRING field, then re-type it
        // with a pattern — one read, one gate, one write
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Plz","kind":"STRING"},
                  {"target":"field","op":"modify","group":"/Order","name":"Plz","kind":"STRING",
                   "string":{"pattern":"[0-9]{5}","patternMessage":"five digits"}} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals(2, j.path("ops").asInt());
        assertEquals("staged", j.path("results").get(1).path("outcome").asText());
        assertTrue(Files.readString(model).contains("[0-9]{5}"),
                "the re-type is persisted in the same transaction");
    }

    @Test
    void appliesAFieldMetadataEditInOneSession(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // G45: add a field, then label it through the canonical polymorphic metadata op, in one atomic session.
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Plz","kind":"STRING"},
                  {"target":"meta","op":"modify","ref":"/Order/Plz","label":{"en_US":"Postal code"}} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertTrue(Files.readString(model).contains("Postal code"),
                "apply's meta modify must apply the label, not silently drop it");
    }

    @Test
    void aFieldModifyOpWithAConfigBlockButNoKindGetsTheCorrective(@TempDir Path dir) throws Exception {
        // the F1(b) slip in an apply op: a `string` block with no `kind` can't re-type → empty modify. apply
        // must give the SAME precise corrective as the standalone verb (name the kind, warn it replaces), and
        // roll the sequence back — not a generic, mystifying "nothing to modify".
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Plz","kind":"STRING"},
                  {"target":"field","op":"modify","group":"/Order","name":"Plz",
                   "string":{"patternMessages":{"en_US":"five digits"}}} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code(), () -> "the empty modify rolls the sequence back: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean());
        String diag = j.path("results").get(1).path("diagnostics").get(0).path("summary").asText();
        assertTrue(diag.contains("STRING") && diag.contains("REPLACES"),
                () -> "the failing op names the kind to add + warns it replaces the type: " + diag);
    }

    @Test
    void rejectsAnUnknownNestedKeyInAFieldOp(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // a typo INSIDE the string config block — the flat top-level key check can't see it; the nested
        // schema check must, so the op fails and the whole sequence rolls back (not a silent drop)
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Plz","kind":"STRING","string":{"maxLenght":5}} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertNotEquals(0, res.code(), "a nested config typo in an apply op must fail, not silently drop");
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean(), "nothing committed when an op has an unknown nested key");
        boolean unknownArg = false;
        for (JsonNode r : j.path("results")) {
            for (JsonNode d : r.path("diagnostics")) {
                unknownArg |= "RK_UNKNOWN_ARG".equals(d.path("code").asText());
            }
        }
        assertTrue(unknownArg, () -> "the failing op carries RK_UNKNOWN_ARG for the nested key: " + res.out());
    }

    @Test
    void addsAConstrainedFieldInOneRichOp(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // G8: a single rich `field add` op carries the per-kind constraint block — no add-then-modify dance.
        // a whole-number Fee (number.maxFractionalDigits:0) + a patterned Plz (string.pattern), added atomically.
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Fee","kind":"NUMBER",
                   "number":{"maxFractionalDigits":0}},
                  {"target":"field","op":"add","group":"/Order","name":"Plz","kind":"STRING",
                   "string":{"pattern":"[0-9]{5}","patternMessage":"five digits"}} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals("staged", j.path("results").get(0).path("outcome").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("[0-9]{5}"), "the STRING pattern is persisted by the rich add (not add-then-modify)");
        assertTrue(written.contains("Fee"), "the constrained NUMBER field is persisted");
    }

    @Test
    void fieldAddWithIncompleteLocaleRollsBackCleanly(@TempDir Path dir) throws Exception {
        // 02-crash sibling: the standalone `field add` once let an incomplete-locale label escape as exit 70 /
        // RK_INTERNAL_ERROR. The apply path is the sibling that must ALSO reject it as a clean rolled-back
        // rejection (exit 1, committed:false), never an internal error — locking the whole field-write class.
        Path m = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", m.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "config", "modify", "--add-locale", "de_DE").code());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Doc","name":"Title","kind":"STRING","label":{"en_US":"Title"}} ]""");

        var res = CliTestSupport.run("-m", m.toString(), "apply", ops.toString());
        assertNotEquals(70, res.code(), () -> "apply must not crash on incomplete locale coverage: " + res.out());
        assertEquals(1, res.code(), () -> "the bad op rolls the sequence back: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean(), "nothing committed");
        assertFalse(res.out().contains("RK_INTERNAL_ERROR"), () -> "clean rejection, not internal error: " + res.out());
        assertFalse(Files.readString(m).contains("Title"), "nothing written on the rolled-back sequence");
    }

    @Test
    void fieldReadOpSurfacesPerKindConfig(@TempDir Path dir) throws Exception {
        // read-gap fix (B) consistency: a `field read` op INSIDE a session surfaces the per-kind config too,
        // the same projection as the standalone verb — so a staged constraint is verifiable mid-session.
        Path m = dir.resolve("cfg.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "cfg", "--locale", "en_US",
                "--root", "Doc", "-o", m.toString()).code());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Doc","name":"Fee","kind":"NUMBER","number":{"min":"0","unit":"AMOUNT"}},
                  {"target":"field","op":"read","fieldPathInModel":"/Doc/Fee"} ]""");
        var res = CliTestSupport.run("-m", m.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode readData = M.readTree(res.out()).path("results").get(1).path("data");
        // the in-session read reports the clean ValueKind name too — one vocabulary across standalone + apply
        assertEquals("NUMBER", readData.path("kind").asText(), () -> "in-session read reports kind: " + res.out());
        assertEquals("0", readData.path("number").path("min").asText(), () -> "in-session read surfaces config: " + res.out());
        assertEquals("AMOUNT", readData.path("number").path("unit").asText());
    }

    @Test
    void rollsBackOnAFailingOp(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        // op 0 is valid; op 1 adds to a group that doesn't exist (addField fails fast) → the whole sequence rolls back
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"field","op":"add","group":"/Order/NoSuchGroup","name":"X","kind":"NUMBER"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code(), () -> "a rolled-back apply exits 1: " + res.out());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean());
        assertEquals(1, j.path("failedAt").asInt());
        assertFalse(j.path("written").asBoolean());
        assertEquals(before, Files.readString(model), "the model is untouched after a rollback");
    }

    @Test
    void appliesStructureThenRuleInOneSession(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // the north-star: add a field, then author a rule that guards it — atomically, one write
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"rule","op":"add","field":"/Order/Discount","code":"DISCOUNT_NEGATIVE",
                   "condition":"FieldFilled(/Order/Discount) And [/Order/Discount] < 0",
                   "messages":[{"locale":"en_US","text":"Discount cannot be negative."}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertTrue(j.path("written").asBoolean());
        assertEquals("staged", j.path("results").get(1).path("outcome").asText());
        assertEquals("/Order/DISCOUNT_NEGATIVE", j.path("results").get(1).path("changed").path("rule").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("Discount") && written.contains("DISCOUNT_NEGATIVE"));
    }

    @Test
    void aTypoedArgSelfCorrectsWithDidYouMean(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        // 'grop' instead of 'group' — apply must name it (RK_UNKNOWN_ARG) + suggest 'group', not fail opaquely
        Files.writeString(ops, """
                [ {"target":"field","op":"add","grop":"/Order","name":"Discount","kind":"NUMBER"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean());
        assertEquals(0, j.path("failedAt").asInt());
        JsonNode diag = j.path("results").get(0).path("diagnostics").get(0);
        assertEquals("RK_UNKNOWN_ARG", diag.path("code").asText());
        assertEquals("grop", diag.path("where").path("arg").asText());
        assertTrue(diag.path("fix").asText().contains("group"), () -> "did-you-mean suggests 'group': " + diag);
        assertEquals(before, Files.readString(model), "the model is untouched (rollback)");
    }

    @Test
    void anUnsupportedOpForTheTargetIsNamed(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"bogus","group":"/Order","name":"X"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code());
        JsonNode diag = M.readTree(res.out()).path("results").get(0).path("diagnostics").get(0);
        assertEquals("RK_UNKNOWN_OP", diag.path("code").asText());
    }

    @Test
    void aBatchShapedOpIsRedirectedToBatch(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // a batch op-record ({verb,args}) fed to apply — name it + point at the right verb (cross-type corrective)
        Files.writeString(ops, """
                [ {"verb":"validate","args":["m.json"]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code());
        JsonNode diag = M.readTree(res.out()).path("results").get(0).path("diagnostics").get(0);
        assertEquals("RK_WRONG_BATCH_KIND", diag.path("code").asText());
        assertTrue(diag.path("fix").asText().contains("batch"), () -> "points at `batch`: " + diag);
    }

    @Test
    void appliesAComputationOp(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"computation","op":"add","computedField":"/Subscription/Billing/EffectiveFee",
                   "alternatives":[{"operation":"[BaseFee] * 2"}],
                   "messages":[{"locale":"en_US","text":"Double base."},{"locale":"de_DE","text":"Doppelte Basis."}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals("staged", j.path("results").get(0).path("outcome").asText());
        assertEquals("/Subscription/Billing/EffectiveFeeComp",
                j.path("results").get(0).path("changed").path("computation").asText());
    }

    @Test
    void appliesTypedefAndConfigOps(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"typedef","op":"add","id":"MoneyAmount","kind":"NUMBER"},
                  {"target":"config","op":"modify","decimal-separator":","} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals("MoneyAmount", j.path("results").get(0).path("changed").path("added").asText());
        assertEquals(",", j.path("results").get(1).path("changed").path("decimalSeparator").asText());
        assertTrue(Files.readString(model).contains("MoneyAmount"));
    }

    @Test
    void appliesAnIncludeOpResolvedViaIncludeDir(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING)).build().toJson());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"include","op":"add","alias":"catalog","reference":"catalog",
                   "parent":"/Subscription","name":"Catalog"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed (resolved via -w/--workspace): " + res.out());
        assertEquals("catalog", j.path("results").get(0).path("changed").path("added").asText());
        assertTrue(Files.readString(model).contains("modelAlias"));
    }

    @Test
    void readOpsReturnCurrentSessionStateInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // a read op mid-sequence sees the just-staged field (unified read+edit in one apply call)
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
                  {"target":"field","op":"read","fieldPathInModel":"/Order/Discount"},
                  {"target":"config","op":"read"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        JsonNode fieldRead = j.path("results").get(1);
        assertEquals("read", fieldRead.path("outcome").asText());
        assertEquals("/Order/Discount", fieldRead.path("data").path("field").asText());
        JsonNode configRead = j.path("results").get(2);
        assertEquals("read", configRead.path("outcome").asText());
        assertTrue(configRead.path("data").has("decimalSeparator"), () -> "config read returns the config: " + configRead);
    }

    @Test
    void previewWritesNothing(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "--dry-run");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), "a clean preview still 'commits' the validation verdict");
        assertFalse(j.path("written").asBoolean());
        assertEquals(before, Files.readString(model), "--dry-run writes nothing");
    }

    // --- G12: apply op-set parity with the standalone verbs — `rule remove` + `group remove --cascade` ---

    @Test
    void appliesARuleRemoveOp(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // a "delete a rule" edit is now expressible IN apply (was RK_UNKNOWN_OP — forced a drop to standalone)
        Files.writeString(ops, """
                [ {"target":"rule","op":"remove","rulePathInModel":"/Order/EligibilityCheck"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertTrue(j.path("written").asBoolean());
        assertEquals("/Order/EligibilityCheck", j.path("results").get(0).path("changed").path("removed").asText());
        assertFalse(Files.readString(model).contains("EligibilityCheck"), "the rule is gone from the model");
    }

    // --- F2: apply op-record locator keys match the standalone verb positionals ---

    @Test
    void appliesARuleAddWithAGroupOverrideForRepetitionNotUnique(@TempDir Path dir) throws Exception {
        // F06-3: the apply rule-add op takes the same `group` override as the standalone verb + `rule check
        // --group`, so a RepetitionNotUnique rule (rule at the repeated group's PARENT) is persistable in a session.
        Path model = CliTestSupport.orderRuled(dir);   // repeatable /Order/Items with Sku
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"rule","op":"add","field":"/Order/Items/Sku","group":"/Order",
                   "condition":"RepetitionNotUnique(Items/Sku)","code":"DUPLICATE_SKU",
                   "messages":[{"locale":"en_US","text":"Duplicate SKU."}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertTrue(Files.readString(model).contains("DUPLICATE_SKU"), "the rule persisted with the group override");
    }

    @Test
    void appliesARuleModifyOpEditingSeverityAndMergingMessages(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path ops = dir.resolve("ops.json");
        // parity with the standalone `rule modify`: add a bilingual rule, then edit its severity + ONLY the
        // en_US message in one session — the de_DE message must survive (G17 merge), and the locator is the
        // standalone-matching rulePathInModel (was: rule modify couldn't touch severity/messages at all in apply).
        Files.writeString(ops, """
                [ {"target":"rule","op":"add","field":"/Subscription/AutoRenew","code":"AUTO_RENEW_SET",
                   "condition":"FieldFilled(/Subscription/AutoRenew)",
                   "messages":[{"locale":"en_US","text":"Confirm auto-renew."},{"locale":"de_DE","text":"Verlaengerung bestaetigen."}]},
                  {"target":"rule","op":"modify","rulePathInModel":"/Subscription/AUTO_RENEW_SET","severity":"WARNING",
                   "messages":[{"locale":"en_US","text":"Please confirm auto-renew."}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals("WARNING", j.path("results").get(1).path("changed").path("severity").asText());
        String written = Files.readString(model);
        assertTrue(written.contains("Please confirm auto-renew."), "the en_US message was updated");
        assertTrue(written.contains("Verlaengerung bestaetigen."),
                "the de_DE message survived the one-locale edit (G17 merge, like the standalone verb)");
    }

    @Test
    void configModifyAcceptsAScalarRepeatableKey(@TempDir Path dir) throws Exception {
        // Issue A: a scalar value for a repeatable op-record key (add-locale / role) must bind like a 1-element
        // array — not be silently dropped with a self-contradictory "needs one of … add-locale …" (the manifest
        // advertises these params as SCALAR, so a scalar is the natural first try).
        Path model = dir.resolve("m.dm.json");
        CliTestSupport.run("model", "new", "--id", "m", "--locale", "en_US", "--root", "M", "-o", model.toString());
        CliTestSupport.run("-m", model.toString(), "field", "add", "--group", "/M", "--name", "X", "--kind", "STRING");
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"config","op":"modify","add-locale":"de_DE","role":"admin"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).path("committed").asBoolean(), () -> "scalar add-locale/role bind: " + res.out());
        String written = Files.readString(model);
        assertTrue(written.contains("de_DE"), "the scalar add-locale was applied, not dropped");
        assertTrue(written.contains("admin"), "the scalar role was applied");
    }

    @Test
    void applyMergesANewLocaleMessageRegardlessOfOpOrder(@TempDir Path dir) throws Exception {
        // Issue B: add-locale THEN a rule-modify supplying that locale's message in the SAME apply used to fail
        // (the merge bound the transiently-invalid mid-sequence model and threw "message missing" in the very op
        // that supplies it). The merge now reads existing messages RAW, so this order commits like the reverse.
        Path model = dir.resolve("m.dm.json");
        CliTestSupport.run("model", "new", "--id", "m", "--locale", "en_US", "--root", "M", "-o", model.toString());
        CliTestSupport.run("-m", model.toString(), "field", "add", "--group", "/M", "--name", "Amount", "--kind", "NUMBER");
        Path r = dir.resolve("r.json");
        Files.writeString(r, """
                {"field":"/M/Amount","condition":"FieldFilled(Amount) And [Amount] < 0","code":"NEG","messages":[{"locale":"en_US","text":"neg"}]}""");
        CliTestSupport.run("-m", model.toString(), "rule", "add", r.toString());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"config","op":"modify","add-locale":["de_DE"]},
                  {"target":"rule","op":"modify","rulePathInModel":"/M/NEG","messages":[{"locale":"de_DE","text":"negativ"}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), () -> "locale-then-message commits (Issue B): " + res.out());
        assertTrue(M.readTree(res.out()).path("committed").asBoolean(), () -> "committed: " + res.out());
        String written = Files.readString(model);
        assertTrue(written.contains("negativ") && written.contains("neg"),
                "both the kept en_US and the merged de_DE message are present");
    }

    @Test
    void appliesAComputationModifyOpWithComputationPathInModel(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.subscriptionComputed(dir);
        Path ops = dir.resolve("ops.json");
        // the locator is computationPathInModel (matching the standalone positional), not the old short `computation`
        Files.writeString(ops, """
                [ {"target":"computation","op":"modify","computationPathInModel":"/Subscription/Billing/EffectiveFeeComp",
                   "computedField":"/Subscription/Billing/EffectiveFee",
                   "alternatives":[{"operation":"[BaseFee] * 3"}],
                   "messages":[{"locale":"en_US","text":"Triple base."},{"locale":"de_DE","text":"Dreifache Basis."}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals("/Subscription/Billing/EffectiveFeeComp",
                j.path("results").get(0).path("changed").path("modified").asText());
    }

    @Test
    void appliesARuleRenameOpWithRulePathInModel(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"rule","op":"rename","rulePathInModel":"/Order/EligibilityCheck","to":"Eligibility"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        assertEquals("/Order/Eligibility", j.path("results").get(0).path("changed").path("to").asText());
    }

    @Test
    void everyPathLocatedApplyOpAcceptsTheLocatorKeyTheManifestAdvertises(@TempDir Path dir) throws Exception {
        // F2 class-guard: an apply op-record's locator key MUST equal the key the manifest advertises for that
        // verb (its --stripped positional). They drifted once — manifest said rulePathInModel/computationPathInModel
        // while apply accepted only `rule`/`computation`, so an agent copying from the manifest hit RK_UNKNOWN_ARG.
        // Covers every path-located edit op (the siblings), not just the reported rule case.
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        String[][] pathOps = {
                {"rule", "remove"}, {"rule", "modify"}, {"rule", "rename"},
                {"computation", "modify"}, {"field", "remove"}, {"group", "remove"} };
        for (String[] to : pathOps) {
            String target = to[0];
            String op = to[1];
            String key = manifestPositionalKey(manifest, target, op);
            assertNotNull(key, () -> "manifest advertises a positional key for " + target + " " + op);
            Files.writeString(ops, "[ {\"target\":\"" + target + "\",\"op\":\"" + op + "\",\""
                    + key + "\":\"/Order/EligibilityCheck\"} ]");
            var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
            // the op may fail for OTHER reasons (a missing `to`, no such element) — but NEVER because the
            // manifest-advertised locator key is unknown to apply.
            for (JsonNode r : M.readTree(res.out()).path("results")) {
                for (JsonNode d : r.path("diagnostics")) {
                    if ("RK_UNKNOWN_ARG".equals(d.path("code").asText())) {
                        assertNotEquals(key, d.path("where").path("arg").asText(),
                                () -> "apply rejects the manifest-advertised locator key '" + key + "' for "
                                        + target + " " + op + " (manifest/apply drift): " + res.out());
                    }
                }
            }
        }
    }

    /** The op-record key the manifest advertises for {@code <target> <op>}'s positional (the locator path). */
    private static String manifestPositionalKey(JsonNode manifest, String target, String op) {
        for (JsonNode v : manifest.path("verbs")) {
            if (target.equals(v.path("target").asText()) && op.equals(v.path("op").asText())) {
                for (JsonNode p : v.path("params")) {
                    if (!p.path("flag").asText().startsWith("--")) {   // the positional carries the locator path
                        return p.path("key").asText();
                    }
                }
            }
        }
        return null;
    }

    @Test
    void theOldShortRuleKeySelfCorrectsAfterTheAlignment(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path ops = dir.resolve("ops.json");
        // clean break (no hidden alias, per the fail-fast preference): the pre-alignment `rule` key is gone, so
        // apply names it via RK_UNKNOWN_ARG — the corrective the probe agent already recovered through.
        Files.writeString(ops, """
                [ {"target":"rule","op":"remove","rule":"/Order/EligibilityCheck"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean());
        JsonNode diag = j.path("results").get(0).path("diagnostics").get(0);
        assertEquals("RK_UNKNOWN_ARG", diag.path("code").asText());
        assertEquals("rule", diag.path("where").path("arg").asText());
    }

    @Test
    void groupRemoveCascadeDropsAReferrerInOneSession(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path ops = dir.resolve("ops.json");
        // author a rule that references the /Subscription/Billing group node, then remove that group with
        // cascade — the referrer is dropped with it, atomically (was impossible: `cascade` was RK_UNKNOWN_ARG)
        Files.writeString(ops, """
                [ {"target":"rule","op":"add","field":"/Subscription/AutoRenew","code":"BILLING_GUARD",
                   "condition":"FieldFilled(/Subscription/AutoRenew) And GroupFilled(/Subscription/Billing)",
                   "messages":[{"locale":"en_US","text":"Billing required."},{"locale":"de_DE","text":"Rechnung noetig."}]},
                  {"target":"group","op":"remove","groupPathInModel":"/Subscription/Billing","cascade":true} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertTrue(j.path("committed").asBoolean(), () -> "committed: " + res.out());
        JsonNode removeResult = j.path("results").get(1);
        assertEquals("/Subscription/Billing", removeResult.path("changed").path("removed").asText());
        assertTrue(removeResult.path("changed").path("cascaded").toString().contains("/Subscription/BILLING_GUARD"),
                () -> "the referring rule is reported as cascaded: " + removeResult);
        String written = Files.readString(model);
        assertFalse(written.contains("BILLING_GUARD"), "the cascaded rule is gone");
        assertFalse(written.contains("\"/Subscription/Billing\""), "the group is gone");
    }

    @Test
    void groupRemoveRefusesAReferencedGroupWithoutCascade(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        String before = Files.readString(model);
        Path ops = dir.resolve("ops.json");
        // same scenario, no cascade — the §6 safe-delete gate fires mid-sequence and the WHOLE thing rolls back
        Files.writeString(ops, """
                [ {"target":"rule","op":"add","field":"/Subscription/AutoRenew","code":"BILLING_GUARD",
                   "condition":"FieldFilled(/Subscription/AutoRenew) And GroupFilled(/Subscription/Billing)",
                   "messages":[{"locale":"en_US","text":"Billing required."},{"locale":"de_DE","text":"Rechnung noetig."}]},
                  {"target":"group","op":"remove","groupPathInModel":"/Subscription/Billing"} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(1, res.code());
        JsonNode j = M.readTree(res.out());
        assertFalse(j.path("committed").asBoolean());
        assertEquals(1, j.path("failedAt").asInt());
        JsonNode refusal = j.path("results").get(1);
        assertEquals("refused", refusal.path("outcome").asText(), () -> "the gate refuses: " + refusal);
        assertEquals("RK_REFERENCED", refusal.path("diagnostics").get(0).path("code").asText());
        assertTrue(refusal.path("data").path("referencedBy").path("rules").toString().contains("/Subscription/BILLING_GUARD"),
                () -> "the refusal lists the referrer: " + refusal);
        assertEquals(before, Files.readString(model), "rollback — the model file is untouched");
    }

    // --- apply op-key parity with the standalone verbs' settable flags (05-apply-repeat, generalized) ---

    @Test
    void includeAddOpHonorsRepeatabilityAndExclusions(@TempDir Path dir) throws Exception {
        // 05-apply-repeat (generalized): the apply `include add` op must honor the SAME mount options the
        // standalone verb supports — repeatability + rule/computation exclusions — not silently drop them. The
        // op called the option-less 4-arg addInclude AND its key-set rejected repeatability/exclude-* as
        // RK_UNKNOWN_ARG, so neither could ever take effect through apply.
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING)).build().toJson());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"include","op":"add","alias":"catalog","reference":"catalog",
                   "parent":"/Subscription","name":"Catalog","repeatability":5,
                   "exclude-rules":true,"exclude-computations":true} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "-w", dir.toString());
        assertEquals(0, res.code(), res.err());
        JsonNode changed = M.readTree(res.out()).path("results").get(0).path("changed");
        assertEquals(5, changed.path("repeatability").asInt(), () -> "repeatability honored: " + res.out());
        assertTrue(changed.path("excludeRules").asBoolean(), "exclude-rules honored");
        assertTrue(changed.path("excludeComputations").asBoolean(), "exclude-computations honored");
        // the options actually took effect in the committed model (kernel-validated by the terminal gate)
        String written = Files.readString(model);
        assertTrue(written.contains("\"repeatability\" : 5"), "the mount is emitted as a repeatable group");
        assertTrue(written.contains("\"excludeRules\" : true") && written.contains("\"excludeComputations\" : true"),
                "the mount carries the exclusions");
    }

    @Test
    void configModifyOpHonorsSupportedCharacter(@TempDir Path dir) throws Exception {
        // the swept sibling of 05-apply-repeat: `config modify --supported-character A-Z` is a standalone
        // capability that apply's CONFIG_MODIFY op rejected (no key, no handler branch) — so an agent could not
        // restrict the allowed-character set inside a session.
        Path model = dir.resolve("m.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "m", "--locale", "en_US",
                "--root", "M", "-o", model.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/M", "--name", "X", "--kind", "STRING").code());
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"config","op":"modify","supported-character":["A-Z","0-9"]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());
        assertEquals(0, res.code(), res.err());
        assertTrue(M.readTree(res.out()).path("committed").asBoolean(), () -> "committed: " + res.out());
        JsonNode supported = M.readTree(CliTestSupport.run("-m", model.toString(), "config", "read").out())
                .path("data").path("supportedCharacters");
        assertTrue(supported.toString().contains("A-Z") && supported.toString().contains("0-9"),
                () -> "the supported-character set was applied: " + supported);
    }

    @Test
    void everyFlagDrivenApplyOpAcceptsTheSettableFlagsItsStandaloneVerbAdvertises(@TempDir Path dir) throws Exception {
        // Class-guard (05-apply-repeat, generalized — the anti-whack-a-mole lock): for a FLAG-driven verb,
        // every settable `--flag` the manifest advertises must be a valid apply op-record key — otherwise the
        // op silently can't do what the standalone verb can. This bit `include add` (repeatability/exclude-*)
        // and `config modify` (supported-character). The RICH-SPEC verbs (field/rule/computation/typedef
        // add·modify) are EXCLUDED: they carry a JSON spec body whose keys deliberately diverge from the CLI
        // flags (--message/--locale → messages) and are guarded by SpecKeys + the executable schema examples.
        JsonNode manifest = M.readTree(CliTestSupport.run("manifest").out());
        Path model = dir.resolve("subscription.dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Files.writeString(dir.resolve("catalog.dm.json"),
                DocumentModelDraft.named("catalog").locale("en_US").locale("de_DE")
                        .rootGroup("Catalog", g -> g.field("Sku", FieldKind.STRING)).build().toJson());
        Path ops = dir.resolve("ops.json");
        // the flag-driven mutating verbs (those whose settings arrive as --flags, not a JSON spec body or a
        // bare positional locator). include/rule/typedef remove and the rename/move ops carry only a positional
        // locator (no settable flags) — their parity is on the locator axis, guarded by the locator-parity test.
        String[][] flagVerbs = {
                {"include", "add"},
                {"group", "add"}, {"group", "modify"}, {"group", "remove"},
                {"config", "modify"} };
        for (String[] tv : flagVerbs) {
            String target = tv[0];
            String op = tv[1];
            Set<String> settable = manifestSettableKeys(manifest, target, op);
            assertFalse(settable.isEmpty(), () -> "manifest advertises settable flags for " + target + " " + op);
            ObjectNode rec = M.createObjectNode();
            rec.put("target", target);
            rec.put("op", op);
            settable.forEach(k -> rec.put(k, "x"));   // values are irrelevant — validateArgs only checks KEYS
            Files.writeString(ops, "[" + rec + "]");
            var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "-w", dir.toString());
            // the op may fail for OTHER reasons (a bad value, a missing element) — but NEVER because a
            // manifest-advertised settable flag is unknown to apply.
            for (JsonNode r : M.readTree(res.out()).path("results")) {
                for (JsonNode d : r.path("diagnostics")) {
                    if ("RK_UNKNOWN_ARG".equals(d.path("code").asText())) {
                        String arg = d.path("where").path("arg").asText();
                        assertFalse(settable.contains(arg),
                                () -> "apply " + target + " " + op + " rejects the manifest-advertised settable flag '"
                                        + arg + "' (apply/standalone drift): " + res.out());
                    }
                }
            }
        }
    }

    /**
     * The op-record keys an agent would copy from the manifest for {@code <target> <op>}'s settable
     * {@code --flags} — the {@code --}-stripped param keys, minus the CLI I/O mechanics that are not op-record
     * args ({@code -o/--output}, {@code --dry-run}, {@code -w/--workspace}, {@code --help}, {@code --version}).
     */
    private static Set<String> manifestSettableKeys(JsonNode manifest, String target, String op) {
        Set<String> mechanics = Set.of("output", "dry-run", "workspace", "help", "version");
        Set<String> keys = new TreeSet<>();
        for (JsonNode v : manifest.path("verbs")) {
            if (target.equals(v.path("target").asText()) && op.equals(v.path("op").asText())) {
                for (JsonNode p : v.path("params")) {
                    if (p.path("flag").asText().startsWith("--") && !p.path("positional").asBoolean()) {
                        String key = p.path("key").asText();
                        if (!mechanics.contains(key)) {
                            keys.add(key);
                        }
                    }
                }
            }
        }
        return keys;
    }
}
