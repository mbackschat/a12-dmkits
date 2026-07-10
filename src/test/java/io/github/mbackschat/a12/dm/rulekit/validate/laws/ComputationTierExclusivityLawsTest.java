package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §11 — the "mutually exclusive preconditions" requirement's ENFORCEMENT TIER (the IG56
 * Set-S probe candidate 2): the BA requires a tiered computation's alternative preconditions to be mutually
 * exclusive, and the runtime picks the FIRST matching alternative in declaration order — but is the
 * requirement enforced by any consistency-tier diagnostic? This law pins {@code checkConsistency}'s emitted
 * code set over a DELIBERATELY-overlapping tiered computation: exclusivity is a semantic (runtime-judged)
 * contract, not a checked one — the kernel accepts overlapping and even IDENTICAL preconditions with an
 * empty diagnostic set, exactly like the exclusive control. (The runtime half — first-declared-wins among
 * alternatives — is locked by the compute differentials; the multi-COMPUTATION sibling is IF87.)
 */
class ComputationTierExclusivityLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    /** {@code checkConsistency} over a two-tier computation with the given preconditions. */
    private static ValidationResult check(java.util.function.Function<NumberRef, List<Condition>> tiers) {
        BoundModel m = BoundModel.ofJson(Fixtures.subscriptionJson());
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        List<Condition> pre = tiers.apply(base);
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("TierProbe")
                .when(pre.get(0)).calc(base.value())
                .when(pre.get(1)).calc(NumberLiteral.of("0.00"))
                .message(en("Effective fee follows the tiers."), de("Effektivgebühr folgt den Stufen.")).build();
        return VALIDATOR.check(Fixtures.subscriptionJson(), comp.draft());
    }

    private static List<String> codes(ValidationResult r) {
        return r.diagnostics().stream().map(d -> d.code().orElse("<none>")).sorted().toList();
    }

    @Test
    void overlappingPreconditionsPassWithAnEmptyDiagnosticSet() {
        // every BaseFee > 0 satisfies BOTH tiers — maximal practical overlap, still accepted silently
        ValidationResult r = check(base -> List.of(base.gt(0), base.gt(-100)));
        assertTrue(r.ok(), () -> "overlapping tiers must pass checkConsistency: " + codes(r));
        assertEquals(List.of(), codes(r), "no consistency-tier diagnostic flags the overlap");
    }

    @Test
    void identicalPreconditionsPassWithAnEmptyDiagnosticSet() {
        ValidationResult r = check(base -> List.of(base.gt(0), base.gt(0)));
        assertTrue(r.ok(), () -> "IDENTICAL tiers must pass checkConsistency: " + codes(r));
        assertEquals(List.of(), codes(r), "even byte-identical preconditions draw no diagnostic");
    }

    @Test
    void exclusivePreconditionsControl() {
        ValidationResult r = check(base -> List.of(base.ge(100), base.lt(100)));
        assertTrue(r.ok(), () -> "the exclusive control passes: " + codes(r));
        assertEquals(List.of(), codes(r));
    }
}
