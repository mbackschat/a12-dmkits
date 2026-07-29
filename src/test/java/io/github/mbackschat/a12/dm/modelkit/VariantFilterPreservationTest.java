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
 * The kernel's {@code variantFilter} is OUT OF SCOPE for authoring (dmkits never sets or interprets it —
 * DEV-API-GAPS §3), but an EXISTING document model may carry it. So it must survive a modelkit load → edit
 * → save <b>untouched</b> — the "preserve the unknown" invariant: {@link ModelDocument} edits the raw
 * DM-JSON tree, so a slot it doesn't model is neither stripped nor rewritten. This guard locks both halves:
 * such a model loads through the real kernel, and its {@code variantFilter} is byte-identical after an
 * unrelated edit. (We inject the {@code variantFilter} into the raw JSON here precisely because there is no
 * authoring API for it — that is the point.)
 */
class VariantFilterPreservationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A model whose {@code Coupon} field carries a {@code variantFilter} — simulating an existing model. */
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
    void existingModelWithVariantFilterLoadsThroughTheKernel() throws Exception {
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(modelWithVariantFilter()));
    }

    @Test
    void variantFilterSurvivesAnUnrelatedEditRoundTrip() throws Exception {
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
