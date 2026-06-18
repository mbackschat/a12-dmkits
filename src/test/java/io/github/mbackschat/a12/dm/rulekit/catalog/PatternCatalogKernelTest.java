package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.ComputationInstantiation;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.FieldInstantiation;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.Instantiation;
import io.github.mbackschat.a12.dm.rulekit.catalog.PatternEntry.RuleInstantiation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The pattern catalog's <b>no-rot</b> gate (FEATURE-GAPS G2/R1) — the peer of the operator catalog's
 * example-validation (N7): every built-in pattern's {@link PatternEntry#example()} is instantiated through the
 * typed DSL and run through the <b>real kernel</b> — a rule via {@link DraftRule}, a computation via its draft.
 * FIELD idioms produce a modelkit-free spec (the sibling boundary keeps rulekit modelkit-free), so their kernel
 * round-trip is the CLI guard {@code PatternsVerbTest.everyFieldIdiomExampleScaffoldsAKernelValidField}; here we
 * assert their spec is well-formed. A pattern that no longer scaffolds a kernel-valid artifact fails the build.
 * Fixture: {@link Fixtures#order()} (the OrderDate/DeliveryDate/Items/Priority/Quantity/BackorderQuantity shape).
 */
class PatternCatalogKernelTest {

    @Test
    void everyBuiltInPatternExampleScaffoldsAKernelValidArtifact() {
        String modelJson = Fixtures.orderJson();
        for (PatternEntry p : PatternCatalog.load().entries()) {
            Instantiation inst = p.instantiate(Fixtures.order(), p.example());
            switch (inst) {
                case RuleInstantiation r -> assertOk(p, "rule", checkRule(modelJson, r));
                case ComputationInstantiation c -> assertOk(p, "computation",
                        new RuleValidator().check(modelJson, c.computation().draft()));
                case FieldInstantiation f -> assertTrue(
                        !f.group().isBlank() && !f.name().isBlank() && !f.kind().isBlank() && !f.config().isEmpty(),
                        () -> "field idiom '" + p.id() + "' produced an ill-formed spec: " + f);
            }
        }
    }

    private static void assertOk(PatternEntry p, String kind, ValidationResult result) {
        assertTrue(result.ok(), () -> "pattern '" + p.id() + "' scaffolds a kernel-INVALID " + kind
                + "\n  diagnostics: " + result.diagnostics());
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
