package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.StringReader;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * A member A12 does not declare survives a modelkit load, edit and save untouched: {@link ModelDocument} edits
 * the raw DM-JSON tree, so a slot it does not model is neither stripped nor rewritten. The example is a field's
 * {@code variantFilter}, which DM 29.1 retired (A12K-3986) and which a hand-written model may still carry; kernel
 * 31.1.1 reads past it ({@code rulekit.validate.laws.RetiredVariantMembersLawsTest}). Both halves are locked: the
 * model loads through the real kernel, and the member is unchanged after an unrelated edit.
 */
class UnknownMemberPreservationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A model whose {@code Coupon} field carries the retired {@code variantFilter}. */
    private static String modelWithVariantFilter() throws Exception {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.stringField("Coupon", s -> { }).numberField("Total", n -> { }))
                .build().toJson();
        ObjectNode root = (ObjectNode) MAPPER.readTree(base);
        ObjectNode couponBody = (ObjectNode) findField(root, "Coupon").get("Field");
        ObjectNode vf = couponBody.putObject("variantFilter");
        vf.put("filterType", "ONLY");
        vf.putArray("variants").add("promo");
        return MAPPER.writeValueAsString(root);
    }

    @Test
    void aModelWithAnUnknownMemberLoadsThroughTheKernel() throws Exception {
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(modelWithVariantFilter()));
    }

    @Test
    void anUnknownMemberSurvivesAnUnrelatedEdit() throws Exception {
        String saved = ModelDocument.read(modelWithVariantFilter())
                .setLabel("/Order/Total", Map.of("en_US", "Total amount"))   // an UNRELATED edit elsewhere
                .toJson();

        JsonNode vf = findField((ObjectNode) MAPPER.readTree(saved), "Coupon").get("Field").get("variantFilter");
        assertNotNull(vf, () -> "variantFilter was stripped on round-trip:\n" + saved);
        assertEquals("ONLY", vf.get("filterType").asText());
        assertEquals("promo", vf.get("variants").get(0).asText());
    }

    private static JsonNode findField(JsonNode n, String name) {
        if (n.isObject() && "Field".equals(n.path("type").asText()) && name.equals(n.path("name").asText())) {
            return n;
        }
        for (JsonNode child : n) {
            JsonNode found = findField(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
