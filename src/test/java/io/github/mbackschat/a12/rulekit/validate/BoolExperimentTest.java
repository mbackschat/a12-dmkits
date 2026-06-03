package io.github.mbackschat.a12.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoolExperimentTest {
    @Test
    void experiment() throws Exception {
        String modelJson;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("models/order.dm.json")) {
            assertNotNull(in);
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        record Probe(String errField, String cond) {}
        for (Probe p : new Probe[]{
                new Probe("../ExpressShipping", "[ExpressShipping] == True"),
                new Probe("../ExpressShipping", "[ExpressShipping] == False"),
                new Probe("../ExpressShipping", "[ExpressShipping] != True"),
                new Probe("../TermsConfirmed", "[TermsConfirmed] == True"),
                new Probe("../TermsConfirmed", "[TermsConfirmed] == False"),
                new Probe("../TermsConfirmed", "[TermsConfirmed] != True"),
                new Probe("../ExpressShipping", "[ExpressShipping] == \"yes\""),
                new Probe("../ExpressShipping", "[ExpressShipping] == [TermsConfirmed]")}) {
            ValidationResult r = new RuleValidator().check(modelJson, new DraftRule(
                    "/Order", "Exp", p.errField(), p.cond(), "EXP", "ERROR"));
            System.out.println("EXPERIMENT: " + p.cond() + " -> " + (r.ok() ? "OK" : r.problems()));
        }
    }
}
