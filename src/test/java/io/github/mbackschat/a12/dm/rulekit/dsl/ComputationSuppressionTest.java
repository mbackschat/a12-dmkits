package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A computation's {@code errorCodesToSuppress} (DEV-API-GAPS; {@code IComputation.getErrorCodesToSuppress},
 * the IF92 escape hatch). The kernel's only model-level suppression for the static assignment-scale gate:
 * a scale-mismatched operation ({@code [UnitPrice] * [UnitPrice]}, scale 4, into the scale-0
 * {@code BackorderQuantity}) draws {@code MVK_INVALID_COMPARE_DEC_PLACES} — unless the computation
 * declares {@code errorCodesToSuppress: ["MVK_INVALID_COMPARE_DEC_PLACES"]}. The typed builder now carries
 * it (domain-checked against the kernel's supported set), the serializer writes it, and the real kernel
 * honors it. Mirrors {@code ComputedNumberDigitOverflowDiffTest}'s reachability control.
 */
class ComputationSuppressionTest {

    private static final BoundModel m = Fixtures.order();

    /** Scale-4 product into the scale-0 {@code BackorderQuantity} — the static gate, suppressed by [suppress]. */
    private Computation scaleMismatch(String... suppress) {
        NumberExpr product = m.num("/Order/UnitPrice").asNumber().times(m.num("/Order/UnitPrice").asNumber());
        return m.computation(m.num("/Order/BackorderQuantity"))
                .name("ScaleMismatchComp")
                .calc(product.expression())
                .errorCodesToSuppress(suppress)
                .message(Dsl.en("Backorder derived from the unit price."))
                .build();
    }

    @Test
    void draftCarriesTheSuppressionList() {
        DraftComputation d = scaleMismatch("MVK_INVALID_COMPARE_DEC_PLACES").draft();
        assertEquals(List.of("MVK_INVALID_COMPARE_DEC_PLACES"), d.errorCodesToSuppress());
    }

    @Test
    void draftDefaultsToNoSuppressions() {
        assertEquals(List.of(), scaleMismatch().draft().errorCodesToSuppress());
    }

    @Test
    void serializedComputationCarriesErrorCodesToSuppress() {
        String json = ModelWriter.addComputation(Fixtures.orderJson(),
                scaleMismatch("MVK_INVALID_COMPARE_DEC_PLACES"));
        assertTrue(json.contains("errorCodesToSuppress"), () -> "missing key in: " + json);
        assertTrue(json.contains("MVK_INVALID_COMPARE_DEC_PLACES"));
    }

    @Test
    void kernelAcceptsScaleMismatchWithSuppression() {
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(),
                scaleMismatch("MVK_INVALID_COMPARE_DEC_PLACES").draft());
        assertTrue(vr.ok(), () -> "kernel rejected the suppressed scale mismatch: " + vr.diagnostics());
    }

    @Test
    void kernelRejectsScaleMismatchWithoutSuppression() {
        // the reachability control (IF92): without the suppression the static gate is an ERROR
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(), scaleMismatch().draft());
        assertFalse(vr.ok(), "expected the static scale gate to fire without suppression");
        assertTrue(vr.diagnostics().stream()
                        .anyMatch(d -> d.code().filter("MVK_INVALID_COMPARE_DEC_PLACES"::equals).isPresent()),
                () -> "expected MVK_INVALID_COMPARE_DEC_PLACES, got: " + vr.diagnostics());
    }

    @Test
    void bogusSuppressionCodeIsRejectedFailFast() {
        // only the kernel's supported set is authorable — a corrective error, not a silent no-op
        assertThrows(IllegalArgumentException.class,
                () -> m.computation(m.num("/Order/BackorderQuantity")).name("C")
                        .calc(m.num("/Order/UnitPrice").value())
                        .errorCodesToSuppress("MVK_NO_WILDCARD"));
    }
}
