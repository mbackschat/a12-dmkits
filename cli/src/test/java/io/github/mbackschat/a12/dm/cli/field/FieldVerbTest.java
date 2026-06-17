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
}
