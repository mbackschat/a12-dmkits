package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T4 parity: the {@code config modify}, {@code group modify}, and G45 {@code meta} setter cascades are
 * single-sourced, so the standalone verbs and the
 * {@code apply} op-record must produce a <b>byte-identical resulting model</b> over the full flag matrix.
 * Running the same directives through both surfaces onto two copies of one fixture and asserting the two
 * outputs are structurally equal is the lock that the two paths can't drift.
 */
class ModifyApplyParityTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path copyModel(Path dir, String name) throws Exception {
        Path m = dir.resolve(name);
        Files.writeString(m, CliTestSupport.subscriptionJson());
        return m;
    }

    private static JsonNode modelTree(Path m) throws Exception {
        return M.readTree(Files.readString(m));
    }

    @Test
    void configModifyStandaloneAndApplyProduceTheSameModel(@TempDir Path dir) throws Exception {
        Path viaVerb = copyModel(dir, "config-verb.dm.json");
        Path viaApply = copyModel(dir, "config-apply.dm.json");

        var verb = CliTestSupport.run("-m", viaVerb.toString(), "config", "modify",
                "--decimal-separator", ",", "--time-zone", "Europe/Berlin", "--comment", "audited",
                "--annotation", "owner=platform", "--base-year", "2020", "--field-ref-by-short-name", "true",
                "--role", "admin", "--role", "editor", "--supported-character", "A-Z", "--supported-character", "0-9");
        assertEquals(0, verb.code(), verb::err);
        assertEquals("applied", M.readTree(verb.out()).path("outcome").asText(), verb::out);

        Path ops = dir.resolve("config-ops.json");
        Files.writeString(ops, """
                [ {"target":"config","op":"modify","decimal-separator":",","time-zone":"Europe/Berlin",
                   "comment":"audited","annotation":{"owner":"platform"},"base-year":2020,
                   "field-ref-by-short-name":true,"role":["admin","editor"],
                   "supported-character":["A-Z","0-9"]} ]""");
        var apply = CliTestSupport.run("-m", viaApply.toString(), "apply", ops.toString());
        assertEquals(0, apply.code(), apply::err);
        assertTrue(M.readTree(apply.out()).path("data").path("committed").asBoolean(), apply::out);

        assertEquals(modelTree(viaVerb), modelTree(viaApply),
                "config modify (standalone) and apply CONFIG_MODIFY must yield an identical model");
    }

    @Test
    void groupModifyStandaloneAndApplyProduceTheSameModel(@TempDir Path dir) throws Exception {
        Path viaVerb = copyModel(dir, "group-verb.dm.json");
        Path viaApply = copyModel(dir, "group-apply.dm.json");

        // /Subscription/Addons is repeatable with child fields Name + MonthlyFee; the model declares en_US + de_DE
        // This exercises group semantics plus the canonical metadata route through both standalone/apply forms.
        var verb = CliTestSupport.run("-m", viaVerb.toString(), "group", "modify", "/Subscription/Addons",
                "--repeatable", "15", "--index-field", "Name",
                "--sort-field", "MonthlyFee:desc", "--sort-field", "Name");
        assertEquals(0, verb.code(), verb::err);
        assertEquals("applied", M.readTree(verb.out()).path("outcome").asText(), verb::out);
        var meta = CliTestSupport.run("-m", viaVerb.toString(), "meta", "/Subscription/Addons",
                "--label", "en_US=Add-ons", "--label", "de_DE=Zusaetze",
                "--annotation", "owner=fulfilment",
                "--internal-description", "en_US=internal note", "--external-description", "en_US=shown");
        assertEquals(0, meta.code(), meta::err);

        Path ops = dir.resolve("group-ops.json");
        Files.writeString(ops, """
                [ {"target":"group","op":"modify","groupPathInModel":"/Subscription/Addons",
                   "repeatable":15,"index-field":"Name","sort-field":["MonthlyFee:desc","Name"]},
                  {"target":"meta","op":"modify","ref":"/Subscription/Addons",
                   "label":{"en_US":"Add-ons","de_DE":"Zusaetze"},"annotation":{"owner":"fulfilment"},
                   "internal-description":{"en_US":"internal note"},"external-description":{"en_US":"shown"}} ]""");
        var apply = CliTestSupport.run("-m", viaApply.toString(), "apply", ops.toString());
        assertEquals(0, apply.code(), apply::err);
        assertTrue(M.readTree(apply.out()).path("data").path("committed").asBoolean(), apply::out);

        assertEquals(modelTree(viaVerb), modelTree(viaApply),
                "group modify (standalone) and apply GROUP_MODIFY must yield an identical model");
    }
}
