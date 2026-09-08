package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

class CandidateMessageFidelityTest {
    private static final String MODEL = DocumentModelDraft.named("shipment-totals").locale("en_US")
            .rootGroup("Shipment", root -> root.numberField("Quantity", f -> { })
                    .numberField("Total", f -> { })).build().toJson();

    @Test
    void typedRuleChecksItsAuthoredMessage() {
        var shipment = EditSession.read(MODEL).group("/Shipment");
        var quantity = shipment.num("Quantity");
        for (boolean malformed : new boolean[] {false, true}) {
            var rule = shipment.rule(r -> r.name("QuantityCap").code("CAP").severity(Severity.ERROR)
                    .errorField(quantity).when(quantity.gt(10))
                    .message(Dsl.en(malformed ? "Quantity $" : "Quantity exceeds the cap.")).build());
            var result = new RuleValidator().check(MODEL, rule.draft());
            assertEquals(rule.messages(), rule.draft(ConditionLanguage.DE).messages());
            assertEquals(Verification.KERNEL_CONFIRMED, result.consistencyGate().orElseThrow());
            if (malformed) assertFalse(result.ok(), "the kernel must see the unmatched message delimiter");
            else assertTrue(result.ok(), () -> result.diagnostics().toString());
        }
    }

    @Test
    void typedComputationChecksItsAuthoredMessage() {
        var session = EditSession.read(MODEL);
        var shipment = session.group("/Shipment");
        var quantity = shipment.num("Quantity");
        var total = shipment.num("Total");
        for (boolean malformed : new boolean[] {false, true}) {
            var computation = shipment.computation(total, c -> c.name("TotalQuantity")
                    .calc(quantity.value()).message(Dsl.en(malformed ? "Total $" : "Total differs from quantity.")).build());
            var result = new RuleValidator().check(MODEL, computation.draft());
            assertEquals(computation.messages(), computation.draft(ConditionLanguage.DE).messages());
            assertEquals(Verification.KERNEL_CONFIRMED, result.consistencyGate().orElseThrow());
            if (malformed) assertFalse(result.ok(), "the kernel must see the unmatched message delimiter");
            else assertTrue(result.ok(), () -> result.diagnostics().toString());
        }
    }
}
