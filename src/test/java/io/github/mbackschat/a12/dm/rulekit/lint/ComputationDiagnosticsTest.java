package io.github.mbackschat.a12.dm.rulekit.lint;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/** DG2a — the computation peer of {@link RuleDiagnostics}: kernel ground truth + lint, one result. */
class ComputationDiagnosticsTest {

    private static final BoundModel m = BoundModel.ofJson(Fixtures.subscriptionJson());

    @Test
    void cleanComputationDiagnosesOk() {
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("EffFee")
                .when(base.ge(100)).calc(base.value())
                .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
                .message(en("Effective fee follows the tiers."), de("Effektivgebühr nach Stufen.")).build();

        ValidationResult vr = new ComputationDiagnostics().diagnose(m, comp);

        assertTrue(vr.ok(), () -> "a kernel-valid computation diagnoses ok: " + vr.diagnostics());
    }

    @Test
    void foldsTheOverlappingPreconditionLintIntoOneResult() {
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("EffFee")
                .when(base.ge(100)).calc(base.value())
                .when(base.ge(100)).calc(NumberLiteral.of("0.00"))   // identical precondition → overlap lint
                .message(en("Effective fee follows the tiers."), de("Effektivgebühr nach Stufen.")).build();

        ValidationResult vr = new ComputationDiagnostics().diagnose(m, comp);

        assertTrue(vr.diagnostics().stream()
                        .anyMatch(d -> d.code().orElse("").equals("RK_COMPUTATION_OVERLAPPING_PRECONDITIONS")),
                () -> "the computation lint must be folded into the unified result: " + vr.diagnostics());
    }
}
