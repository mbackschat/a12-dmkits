package io.github.mbackschat.a12.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScaleExperimentTest {
    @Test
    void experiment() throws Exception {
        String modelJson;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("models/subscription.dm.json")) {
            assertNotNull(in);
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String cond : new String[]{
                "[MonthlyFee] == 0", "[MonthlyFee] == 0.00", "[MonthlyFee] == 0.0",
                "[MonthlyFee] != 0", "[MonthlyFee] <= 0.000"}) {
            ValidationResult r = new RuleValidator().check(modelJson, new DraftRule(
                    "/Subscription/Addons", "Exp", "../MonthlyFee",
                    "FieldFilled(MonthlyFee) And " + cond, "EXP", "ERROR"));
            System.out.println("EXPERIMENT: " + cond + " -> " + (r.ok() ? "OK" : r.problems()));
        }
    }
}
