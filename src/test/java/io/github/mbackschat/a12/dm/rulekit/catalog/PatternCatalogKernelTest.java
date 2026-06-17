package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.ComputationInstantiation;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.Instantiation;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.RuleInstantiation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The pattern catalog's <b>no-rot</b> gate (FEATURE-GAPS G2/R1) — the peer of the operator catalog's
 * example-validation (N7): every built-in pattern's {@link PatternEntry#example()} is instantiated through the
 * typed DSL and run through the <b>real kernel</b> — a rule via {@link DraftRule}, a computation via
 * {@link DraftComputation}. A pattern that no longer scaffolds a kernel-valid artifact (right polarity, a
 * referenced error field; mutually-exclusive preconditions, kind-matched operations) fails the build — a stale
 * idiom can't ship. Fixture: {@link Fixtures#order()} (the OrderDate/DeliveryDate/Items/Priority/Quantity shape
 * the examples reference).
 */
class PatternCatalogKernelTest {

    @Test
    void everyBuiltInPatternExampleScaffoldsAKernelValidArtifact() {
        String modelJson = Fixtures.orderJson();
        for (PatternEntry p : PatternCatalog.load().entries()) {
            Instantiation inst = p.instantiate(Fixtures.order(), p.example());
            ValidationResult result = switch (inst) {
                case RuleInstantiation r -> checkRule(modelJson, r);
                case ComputationInstantiation c -> new RuleValidator().check(modelJson, c.computation().draft());
            };
            assertTrue(result.ok(),
                    () -> "pattern '" + p.id() + "' scaffolds a kernel-INVALID " + p.kind()
                            + ":\n  " + inst + "\n  diagnostics: " + result.diagnostics());
        }
    }

    private static ValidationResult checkRule(String modelJson, RuleInstantiation r) {
        String field = r.errorField();
        int slash = field.lastIndexOf('/');
        String group = field.substring(0, slash);
        String rel = "../" + field.substring(slash + 1);
        DraftRule draft = new DraftRule(group, "PatternCheck", rel, r.condition(), r.suggestedCode(), "ERROR");
        return new RuleValidator().check(modelJson, draft);
    }
}
