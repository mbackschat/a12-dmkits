package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GroupExperimentTest {
    @Test
    void experiment() throws Exception {
        String modelJson;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("models/order.dm.json")) {
            assertNotNull(in);
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        record Probe(String group, String errField, String cond) {}
        for (Probe p : new Probe[]{
                // error-field-referenced via enclosing group reference?
                new Probe("/Order", "../ShippingAddress/Street", "GroupFilled(ShippingAddress)"),
                new Probe("/Order", "../ShippingAddress/Street", "GroupNotFilled(ShippingAddress)"),
                new Probe("/Order", "../ShippingAddress/Street", "AtLeastOneGroupFilled(ShippingAddress, BillingAddress)"),
                new Probe("/Order", "../ShippingAddress/Street", "NoGroupFilled(ShippingAddress, BillingAddress)"),
                new Probe("/Order", "../ShippingAddress/Street", "AllGroupsFilled(ShippingAddress, BillingAddress)"),
                new Probe("/Order", "../ShippingAddress/Street", "NotAllGroupsFilled(ShippingAddress, BillingAddress)"),
                new Probe("/Order", "../ShippingAddress/Street", "GroupsNotCollectivelyFilled(ShippingAddress, BillingAddress)"),
                // canonical corpus pattern
                new Probe("/Order", "../ShippingAddress/Street", "GroupFilled(ShippingAddress) And FieldNotFilled(ShippingAddress/Street)"),
                // GroupFilled on a FIELD — expected type error, learn the code
                new Probe("/Order", "../CustomerName", "GroupFilled(CustomerName)"),
                // FieldFilled on a GROUP — learn the code
                new Probe("/Order", "../ShippingAddress/Street", "FieldFilled(ShippingAddress) And FieldNotFilled(ShippingAddress/Street)")}) {
            ValidationResult r = new RuleValidator().check(modelJson, new DraftRule(
                    p.group(), "Exp", p.errField(), p.cond(), "EXP", "ERROR"));
            System.out.println("EXPERIMENT: " + p.cond() + " -> " + (r.ok() ? "OK" : r.diagnostics()));
        }
    }
}
