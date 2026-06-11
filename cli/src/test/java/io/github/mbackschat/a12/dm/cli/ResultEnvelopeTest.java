package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * The uniform result envelope (CLI-SPEC §3b): every verb and every {@code apply} op emits one shape.
 * The contract under test is the {@code outcome → ok/valid → exit-code} mapping and the JSON shape,
 * with the diagnostics array embedded verbatim from {@code ValidationResult}.
 */
class ResultEnvelopeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void appliedEnvelopeIsOkAndExitsZero() {
        ObjectNode changed = M.createObjectNode();
        changed.put("added", "/Order/PromoCode").put("kind", "STRING");
        ResultEnvelope env = ResultEnvelope.applied(
                "field", "add", "added field /Order/PromoCode (STRING)", changed, "order.dm.json");

        assertEquals(0, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("field", j.get("target").asText());
        assertEquals("add", j.get("op").asText());
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        assertEquals("/Order/PromoCode", j.path("changed").path("added").asText());
        assertTrue(j.get("written").asBoolean());
        assertEquals("order.dm.json", j.get("output").asText());
        assertFalse(j.has("valid"), "a plain applied op makes no separate validity claim");
        assertTrue(j.path("diagnostics").isArray());
    }

    @Test
    void rejectedEnvelopeCarriesDiagnosticsAndExitsOne() {
        Diagnostic d = Diagnostic.precheck(
                Optional.of("MVK_UNKNOWN_FIELD"),
                "/Order/Quanity is not a field in the model",
                new Diagnostic.Where("/Order/MinQuantity", Optional.empty()),
                Optional.of("replace /Order/Quanity with /Order/Quantity"),
                Optional.empty());
        ResultEnvelope env = ResultEnvelope.rejected(
                "rule", "add", "condition rejected by the kernel (1 error)", List.of(d));

        assertEquals(1, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("rejected", j.get("outcome").asText());
        assertFalse(j.get("ok").asBoolean());
        assertFalse(j.get("written").asBoolean());
        JsonNode diags = j.get("diagnostics");
        assertEquals(1, diags.size());
        assertEquals("MVK_UNKNOWN_FIELD", diags.get(0).get("code").asText());
        assertEquals("replace /Order/Quanity with /Order/Quantity", diags.get(0).get("fix").asText());
    }

    @Test
    void refusedExitsTwo() {
        ResultEnvelope env = ResultEnvelope.refused(
                "field", "remove", "/Order/DeliveryDate is referenced by 1 rule", List.of());
        assertEquals(2, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("refused", j.get("outcome").asText());
        assertFalse(j.get("ok").asBoolean());
    }

    @Test
    void validateInvalidIsOkButNotValidAndExitsOne() {
        Diagnostic d = Diagnostic.precheck(Optional.of("MVK_X"), "bad",
                new Diagnostic.Where("/Order", Optional.empty()), Optional.empty(), Optional.empty());
        ResultEnvelope env = ResultEnvelope.read("doc", "validate", "model has 1 error", false, List.of(d), null);

        assertEquals(1, env.exitCode());
        JsonNode j = env.toJson();
        assertTrue(j.get("ok").asBoolean(), "the validate op itself executed fine");
        assertFalse(j.get("valid").asBoolean(), "the subject model is invalid");
    }

    @Test
    void validateValidExitsZero() {
        ResultEnvelope env = ResultEnvelope.read("doc", "validate", "model is valid", true, List.of(), null);
        assertEquals(0, env.exitCode());
        assertTrue(env.toJson().get("valid").asBoolean());
    }

    @Test
    void readEnvelopeCarriesItsPayloadInData() {
        ObjectNode payload = M.createObjectNode();
        payload.put("rule", "/Order/R").put("converted", true);
        ResultEnvelope env = ResultEnvelope.read("rule", "explain", "explained /Order/R", true, List.of(), payload);
        JsonNode j = env.toJson();
        assertEquals("read", j.get("outcome").asText());
        assertEquals("/Order/R", j.path("data").path("rule").asText());
        assertFalse(j.has("changed"), "a read carries its payload in `data`, not `changed`");
    }
}
