package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
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

    /**
     * Only the kernel's supported set is authorable, and a lone code-shaped entry is refused with the class
     * A12 gives it ({@code validate.laws.ComputationSuppressionLawsTest}, KERNEL-FINDINGS KF298).
     */
    @Test
    void anUnsupportedCodeIsRejectedWithTheKernelsClass() {
        assertEquals(KernelCode.MVK_INVALID_SUPPRESSED_WARNING, citedBy("MVK_NO_WILDCARD"));
    }

    /**
     * The class is withheld wherever the entry alone does not decide it: a keyword is a token in one condition
     * language and a name in the other, and a digit-led spelling is no name at all.
     */
    @Test
    void theClassIsWithheldWhereTheEntryAloneDoesNotDecideIt() {
        assertEquals(null, citedBy("Sum"), "a keyword in English models, a name in German ones");
        assertEquals(null, citedBy("7_DAYS"), "a digit-led spelling, which A12 cannot read as a name");
    }

    /**
     * A second entry is refused whatever it spells, because A12 renders the list into a one-code pragma and
     * fails on the comma. The class is cited where the first entry is a name A12 reads, which puts its parser
     * at that comma: the supported code twice or three times, and a bogus code on either side of it, are all
     * {@code MVK_UNEXPECTED_TOKEN} ({@code validate.laws.ComputationSuppressionLawsTest}, KERNEL-FINDINGS KF298).
     */
    @Test
    void aSecondEntryIsRefusedWithTheParseClass() {
        String supported = "MVK_INVALID_COMPARE_DEC_PLACES";
        assertEquals(KernelCode.MVK_UNEXPECTED_TOKEN, citedBy(supported, supported));
        assertEquals(KernelCode.MVK_UNEXPECTED_TOKEN, citedBy(supported, supported, supported));
        assertEquals(KernelCode.MVK_UNEXPECTED_TOKEN, citedBy(supported, "MVK_NO_WILDCARD"));
        assertEquals(KernelCode.MVK_UNEXPECTED_TOKEN, citedBy("MVK_NO_WILDCARD", supported));
        assertEquals(null, citedBy("Sum", supported), "a keyword first: which token fails depends on the language");
        IllegalArgumentException twoCalls = assertThrows(IllegalArgumentException.class,
                () -> m.computation(m.num("/Order/BackorderQuantity")).name("C")
                        .calc(m.num("/Order/UnitPrice").value())
                        .errorCodesToSuppress(supported).errorCodesToSuppress(supported));
        assertEquals(KernelCode.MVK_UNEXPECTED_TOKEN, ((KernelCodeRefusal) twoCalls).code(),
                "the list a second call extends is the one A12 renders");
    }

    /**
     * A12 admits the supported code padded with whitespace, because its lexer discards the padding; the
     * builder refuses it anyway so the stored member keeps the one spelling every consumer compares, and
     * cites no class because A12 has none for it.
     */
    @Test
    void aPaddedSupportedCodeIsRefusedDeliberatelyAndUncited() {
        assertEquals(null, citedBy(" MVK_INVALID_COMPARE_DEC_PLACES "));
    }

    /** The code a refused {@code errorCodesToSuppress(codes)} cites, or {@code null} when it cites none. */
    private static KernelCode citedBy(String... codes) {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> m.computation(m.num("/Order/BackorderQuantity")).name("C")
                        .calc(m.num("/Order/UnitPrice").value())
                        .errorCodesToSuppress(codes));
        return refusal instanceof KernelCodeRefusal coded ? coded.code() : null;
    }
}
