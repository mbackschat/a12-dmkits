package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target-first {@code field} group (CLI-SPEC §5) — modelkit structure edits on the model named by
 * {@code -m}. {@code add} (Local) and {@code remove} (Cascading — the safe-delete gate refuses a referenced
 * field unless {@code --cascade}). Fixture: {@code order-ruled} (its `DeliveryNotBeforeOrder` rule
 * references `/Order/DeliveryDate`; `/Order/Quantity` is referenced by nothing).
 */
class FieldVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void addAddsAField(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Order", "--name", "PromoCode", "--kind", "STRING");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("/Order/PromoCode", j.path("changed").path("added").asText());
        assertTrue(Files.readString(model).contains("PromoCode"), "the field is persisted");
    }

    @Test
    void readWithNoPathListsTheFieldPaths(@TempDir Path dir) throws Exception {
        // P2: no path → a lean name-index, uniform with the read family.
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        JsonNode fields = j.path("data").path("fields");
        assertTrue(fields.isArray() && fields.size() > 0, () -> "lists field paths: " + res.out());
        assertTrue(fields.toString().contains("/Order/Quantity"), () -> res.out());
    }

    @Test
    void readReturnsTheFieldTypeInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/Quantity");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertEquals("/Order/Quantity", j.path("data").path("field").asText());
        assertTrue(j.path("data").has("type"));
    }

    @Test
    void removesAnUnreferencedField(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "/Order/Quantity");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("\"/Order/Quantity\""));
    }

    @Test
    void refusesAReferencedFieldWithoutCascade(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "/Order/DeliveryDate");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("data").path("referencedBy").path("rules").toString().contains("DeliveryNotBeforeOrder"));
        assertTrue(j.path("diagnostics").toString().contains("RK_REFERENCED"));
        assertTrue(Files.readString(model).contains("/Order/DeliveryDate"), "untouched");
    }

    @Test
    void cascadeRemovesFieldAndReferrers(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "/Order/DeliveryDate", "--cascade");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("DeliveryNotBeforeOrder"), "the referencing rule cascades away");
    }

    // --- G19: field read/remove accept --group/--name too (the locator style learned from `field add`) ---

    @Test
    void readAcceptsGroupAndNameLocator(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read", "--group", "/Order", "--name", "Quantity");
        assertEquals(0, res.code(), res::err);
        assertEquals("/Order/Quantity", M.readTree(res.out()).path("data").path("field").asText(),
                () -> "--group/--name resolves to the same field as the positional path: " + res.out());
    }

    @Test
    void removeAcceptsGroupAndNameLocator(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "--group", "/Order", "--name", "Quantity");
        assertEquals(0, res.code(), res::err);
        assertFalse(Files.readString(model).contains("\"/Order/Quantity\""), "removed via the --group/--name locator");
    }

    @Test
    void fieldLocatorRejectsBothForms(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read",
                "/Order/Quantity", "--group", "/Order", "--name", "Quantity");
        assertEquals(2, res.code(), "a positional AND --group/--name together is a usage error");
    }

    // --- G20: field read echoes the friendly required state (closes the verify-a-refactor loop) ---

    @Test
    void readEchoesTheFriendlyRequiredState(@TempDir Path dir) throws Exception {
        Path m = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", m.toString()).code());
        // a required field (via a spec), a conditionally-required one, and a plain optional one
        Path reqSpec = dir.resolve("req.json");
        Files.writeString(reqSpec, "{\"group\":\"/Doc\",\"name\":\"Must\",\"kind\":\"STRING\",\"required\":true}");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", reqSpec.toString()).code());
        Path condSpec = dir.resolve("cond.json");
        Files.writeString(condSpec, "{\"group\":\"/Doc\",\"name\":\"Maybe\",\"kind\":\"STRING\",\"required\":\"ifParentPresent\"}");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", condSpec.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add",
                "--group", "/Doc", "--name", "Free", "--kind", "STRING").code());

        assertTrue(M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Must").out())
                .path("data").path("required").asBoolean(), "a required field reads required:true");
        assertEquals("ifParentPresent", M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Maybe").out())
                .path("data").path("required").asText(), "a conditionally-required field reads \"ifParentPresent\"");
        assertFalse(M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Free").out())
                .path("data").path("required").asBoolean(), "a plain field reads required:false");
    }
}
