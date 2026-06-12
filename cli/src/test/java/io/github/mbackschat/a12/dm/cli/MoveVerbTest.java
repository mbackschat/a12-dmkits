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
 * Reference-preserving move verbs {@code field|group move} (CLI-SPEC §6). Fixture: {@code /Order} with a
 * {@code Shipping} subgroup whose {@code Zip} is referenced by a rule at {@code /Order} (external to
 * {@code Shipping}), and a {@code Billing} subgroup that shares a {@code City} field name with Shipping (the
 * collision target). The happy paths prove references follow the move; the refusal tests prove the safety
 * gate <em>fires</em> on each unsafe input (no such destination, into-own-subtree, name collision) and
 * mutates nothing — the refactoring gate's does-fire requirement.
 */
class MoveVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path orderModel(Path dir) throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Note", FieldKind.STRING)
                        .group("Shipping", sh -> sh.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("Billing", b -> b.field("Tax", FieldKind.STRING).field("City", FieldKind.STRING)))
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
    void movesAFieldToANewParentRewritingItsReferrers(@TempDir Path dir) throws Exception {
        // ZipGuard (at /Order) references /Order/Shipping/Zip; moving Zip into Billing must rewrite that
        // reference to /Order/Billing/Zip so the model still validates (a dangling ref would fail validate).
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "move", "/Order/Shipping/Zip", "--to", "/Order/Billing");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("/Order/Billing/Zip", j.path("changed").path("to").asText());
        assertTrue(j.path("changed").path("rewroteReferences").toString().contains("/Order/ZipGuard"),
                "the referrer is reported as rewritten");
        assertTrue(Files.readString(model).contains("/Order/Billing/Zip"), "the field was re-homed");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "doc", "validate").code(),
                "the reference followed the field, so the model still validates");
    }

    @Test
    void movesAGroupToANewParentRewritingExternalReferrers(@TempDir Path dir) throws Exception {
        // ZipGuard references the child /Order/Shipping/Zip from OUTSIDE Shipping; moving the whole group
        // under Billing rewrites that reference to /Order/Billing/Shipping/Zip.
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "move", "/Order/Shipping", "--to", "/Order/Billing");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("/Order/Billing/Shipping", j.path("changed").path("to").asText());
        assertTrue(j.path("changed").path("rewroteReferences").toString().contains("/Order/ZipGuard"));
        assertTrue(Files.readString(model).contains("/Order/Billing/Shipping/Zip"), "the subtree was re-homed");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "doc", "validate").code(),
                "the external reference followed the move, so the model still validates");
    }

    @Test
    void refusesMovingANonexistentField(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        String before = Files.readString(model);
        var res = CliTestSupport.run("-m", model.toString(), "field", "move", "/Order/Nope", "--to", "/Order/Billing");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_NO_SUCH_FIELD"),
                "a missing source field is a structured refusal, not a bare error");
        assertEquals(before, Files.readString(model), "a refused move mutates nothing");
    }

    @Test
    void refusesMovingANonexistentGroup(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        var res = CliTestSupport.run("-m", model.toString(), "group", "move", "/Order/Nope", "--to", "/Order/Billing");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_NO_SUCH_GROUP"));
    }

    @Test
    void refusesMoveToANonexistentDestination(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        String before = Files.readString(model);
        var res = CliTestSupport.run("-m", model.toString(), "field", "move", "/Order/Note", "--to", "/Order/Nope");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_NO_SUCH_GROUP"));
        assertEquals(before, Files.readString(model), "a refused move mutates nothing");
    }

    @Test
    void refusesMovingAGroupIntoItsOwnSubtree(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        String before = Files.readString(model);
        var res = CliTestSupport.run("-m", model.toString(), "group", "move", "/Order/Shipping", "--to", "/Order/Shipping");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_MOVE_INTO_SELF"));
        assertEquals(before, Files.readString(model), "a refused move mutates nothing");
    }

    @Test
    void refusesMoveWhenTheNameIsTakenAtTheDestination(@TempDir Path dir) throws Exception {
        // Billing already has a City; moving Shipping's City there would collide.
        Path model = orderModel(dir);
        String before = Files.readString(model);
        var res = CliTestSupport.run("-m", model.toString(), "field", "move", "/Order/Shipping/City", "--to", "/Order/Billing");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("diagnostics").toString().contains("RK_NAME_EXISTS"));
        assertEquals(before, Files.readString(model), "a refused move mutates nothing");
    }

    @Test
    void dryRunPreviewsWithoutWriting(@TempDir Path dir) throws Exception {
        Path model = orderModel(dir);
        String before = Files.readString(model);
        var res = CliTestSupport.run("-m", model.toString(), "field", "move", "/Order/Note", "--to", "/Order/Billing", "--dry-run");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("preview", j.get("outcome").asText());
        assertEquals("/Order/Billing/Note", j.path("changed").path("to").asText());
        assertEquals(before, Files.readString(model), "a dry-run writes nothing");
        assertFalse(j.path("changed").path("to").asText().isEmpty());
    }
}
