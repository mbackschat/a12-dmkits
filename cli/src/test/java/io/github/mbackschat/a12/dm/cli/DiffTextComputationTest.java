package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** MR9 Set 1 — computation changes remain visible in the compact human renderer. */
class DiffTextComputationTest {

    @Test
    void rendersAComputationOperationChange() throws Exception {
        var data = new ObjectMapper().readTree("""
                {"changes":[{"element":"COMPUTATION","id":"/Quote/PriceTotalComp",
                  "change":"MODIFIED","risk":"MEDIUM","riskReasons":["COMPUTATION_CHANGED"],
                  "deltas":[{"aspect":"alternative[0].operation","before":"[Price] * [Quantity]",
                  "after":"[Price] + [Quantity]"}]}]}
                """);

        String rendered = new DiffText().render(data);
        assertTrue(rendered.contains("MODIFIED") && rendered.contains("computation")
                        && rendered.contains("/Quote/PriceTotalComp") && rendered.contains("COMPUTATION_CHANGED"),
                () -> "computation change should be scannable:\n" + rendered);
    }
}
