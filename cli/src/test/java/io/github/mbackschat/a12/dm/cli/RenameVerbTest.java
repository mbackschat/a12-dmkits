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
 * Tier-2 safe-rename verbs {@code field|group|rule rename} (CLI-SPEC §6). Fixture: {@code /Order} with a
 * {@code Shipping} subgroup whose {@code Zip} is referenced by a rule at {@code /Order} (external to
 * {@code Shipping}), a referrer-free {@code Billing} subgroup, and an unreferenced {@code Note} field.
 */
class RenameVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path orderModel(Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Note", FieldKind.STRING)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("Billing", b -> b.field("Tax", FieldKind.STRING)))
                .build()
                .toJson();
        String withRule = EditSession.read(base)
                .addRule(m -> m.rule(m.group("/Order")).name("ZipGuard")
                        .errorField(m.str("/Order/Shipping/Zip"))
                        .when(m.str("/Order/Shipping/Zip").notFilled())
                        .code("ZIP_REQ").severity(Severity.ERROR).message(en("A zip is required.")).build())
                .toJson();
        Path f = dir.resolve("order.dm.json");
        Files.writeString(f, withRule);
        return f;
    }

    @Test
    void renamesAnUnreferencedField(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "rename", "/Order/Note", "--to", "Memo");
        assertEquals(0, res.code(), res.err());
        assertEquals("/Order/Memo", M.readTree(res.out()).path("changed").path("to").asText());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "doc", "validate").code());
    }

    @Test
    void refusesRenamingAReferencedField(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "rename", "/Order/Shipping/Zip", "--to", "Postal");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_REFERENCED"));
        assertTrue(Files.readString(model).contains("\"Zip\""), "untouched");
    }

    @Test
    void renamesAGroupWithNoExternalReferrers(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "rename", "/Order/Billing", "--to", "Finance");
        assertEquals(0, res.code(), res.err());
        assertEquals("/Order/Finance", M.readTree(res.out()).path("changed").path("to").asText());
        assertTrue(Files.readString(model).contains("/Order/Finance/Tax"), "the descendant was re-homed");
    }

    @Test
    void refusesRenamingAGroupWhoseChildIsReferencedFromOutside(@TempDir Path dir) throws Exception {
        // the subtlety: ZipGuard references /Order/Shipping/Zip — the CHILD, not the group — and lives
        // OUTSIDE Shipping; a naive gate on whereUsed(group) would miss it and silently dangle.
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "rename", "/Order/Shipping", "--to", "Delivery");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_REFERENCED"));
        assertTrue(j.path("data").path("referencedBy").toString().contains("/Order/ZipGuard"));
        assertTrue(Files.readString(model).contains("/Order/Shipping/Zip"), "untouched");
    }

    @Test
    void renamesARule(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "rule", "rename", "/Order/ZipGuard", "--to", "ZipPresent");
        assertEquals(0, res.code(), res.err());
        assertEquals("/Order/ZipPresent", M.readTree(res.out()).path("changed").path("to").asText());
        assertTrue(Files.readString(model).contains("ZipPresent"));
        assertFalse(Files.readString(model).contains("ZipGuard"), "the old rule name is gone");
    }
}
