package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.Instantiation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The pattern catalog's <b>no-rot</b> gate (FEATURE-GAPS G2) — the peer of the operator catalog's
 * example-validation (N7): every built-in pattern's {@link PatternEntry#example()} is instantiated through the
 * typed DSL and run through the <b>real kernel</b> as a rule. A pattern that no longer scaffolds a kernel-valid
 * rule (right polarity, a referenced error field) fails the build — a stale idiom can't ship. Fixture:
 * {@link Fixtures#order()} (the same OrderDate/DeliveryDate/Items/Priority shape the examples reference).
 */
class PatternCatalogKernelTest {

    @Test
    void everyBuiltInPatternExampleScaffoldsAKernelValidRule() {
        String modelJson = Fixtures.orderJson();
        for (PatternEntry p : PatternCatalog.load().entries()) {
            Instantiation inst = p.instantiate(Fixtures.order(), p.example());

            String field = inst.errorField();
            int slash = field.lastIndexOf('/');
            String group = field.substring(0, slash);
            String rel = "../" + field.substring(slash + 1);
            DraftRule draft = new DraftRule(group, "PatternCheck", rel, inst.condition(), inst.suggestedCode(), "ERROR");

            ValidationResult result = new RuleValidator().check(modelJson, draft);
            assertTrue(result.ok(),
                    () -> "pattern '" + p.id() + "' scaffolds a kernel-INVALID rule:\n  condition: " + inst.condition()
                            + "\n  errorField: " + field + "\n  diagnostics: " + result.diagnostics());
        }
    }
}
