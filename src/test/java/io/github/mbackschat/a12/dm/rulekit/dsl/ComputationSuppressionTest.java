package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * A computation's suppressed warnings, stored as A12's {@code additionalParserInformation} preamble (document-model
 * format 29.8.0, A12K-4372; the IF92 escape hatch). A scale-mismatched operation ({@code [UnitPrice] * [UnitPrice]},
 * scale 4, into the scale-0 {@code BackorderQuantity}) draws {@code MVK_INVALID_COMPARE_DEC_PLACES} unless the
 * computation suppresses it. The typed builder checks each code against the supported set, renders the preamble in
 * the model's condition language, and the real kernel honors it. Mirrors {@code ComputedNumberDigitOverflowDiffTest}'s
 * reachability control.
 */
class ComputationSuppressionTest {

    private static final String SCALE_GATE = "MVK_INVALID_COMPARE_DEC_PLACES";
    private static final BoundModel m = Fixtures.order();

    /** Scale-4 product into the scale-0 {@code BackorderQuantity} — the static gate, suppressed by [suppress]. */
    private Computation scaleMismatch(String... suppress) {
        NumberExpr product = m.num("/Order/UnitPrice").asNumber().times(m.num("/Order/UnitPrice").asNumber());
        return m.computation(m.num("/Order/BackorderQuantity"))
                .name("ScaleMismatchComp")
                .calc(product.expression())
                .suppressWarnings(suppress)
                .message(Dsl.en("Backorder derived from the unit price."))
                .build();
    }

    @Test
    void theDraftCarriesThePreambleInTheModelsLanguage() {
        Computation suppressed = scaleMismatch(SCALE_GATE);
        assertEquals(List.of(SCALE_GATE), suppressed.suppressedWarnings());
        assertEquals("@SuppressWarning(" + SCALE_GATE + ")", suppressed.draft().additionalParserInformation());
        assertEquals("@UnterdrueckeWarnung(" + SCALE_GATE + ")",
                suppressed.draft(ConditionLanguage.DE).additionalParserInformation());
    }

    @Test
    void noSuppressionStoresNoPreamble() {
        assertNull(scaleMismatch().draft().additionalParserInformation());
    }

    /** The draft boundary's list form stores nothing for an empty list, which A12 would otherwise refuse as `()`. */
    @Test
    void theDraftBuilderRendersAListAndStoresNothingForAnEmptyOne() {
        assertEquals("@UnterdrueckeWarnung(" + SCALE_GATE + ",MVK_ERROR_INVALID_ENUM_CALCULATION)",
                DraftComputation.builder()
                        .suppressWarnings(ConditionLanguage.DE, List.of(SCALE_GATE, "MVK_ERROR_INVALID_ENUM_CALCULATION"))
                        .build().additionalParserInformation());
        assertNull(DraftComputation.builder().additionalParserInformation("@SuppressWarning(" + SCALE_GATE + ")")
                .suppressWarnings(ConditionLanguage.EN, List.of()).build().additionalParserInformation());
    }

    @Test
    void theSerializedComputationCarriesThePreamble() {
        String json = ModelWriter.addComputation(Fixtures.orderJson(), scaleMismatch(SCALE_GATE));
        assertTrue(json.contains("\"additionalParserInformation\" : \"@SuppressWarning(" + SCALE_GATE + ")\""),
                () -> "missing member in: " + json);
        assertFalse(json.contains("errorCodesToSuppress"), "the legacy list is not written");
    }

    @Test
    void kernelAcceptsScaleMismatchWithSuppression() {
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(), scaleMismatch(SCALE_GATE).draft());
        assertTrue(vr.ok(), () -> "kernel rejected the suppressed scale mismatch: " + vr.diagnostics());
    }

    /** A12 admits the same code twice, and so does the builder (`ComputationParserInformationLawsTest`). */
    @Test
    void kernelAcceptsTheSupportedCodeListedTwice() {
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(),
                scaleMismatch(SCALE_GATE, SCALE_GATE).draft());
        assertTrue(vr.ok(), () -> "kernel rejected the repeated code: " + vr.diagnostics());
    }

    @Test
    void kernelRejectsScaleMismatchWithoutSuppression() {
        // the reachability control (IF92): without the suppression the static gate is an ERROR
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(), scaleMismatch().draft());
        assertFalse(vr.ok(), "expected the static scale gate to fire without suppression");
        assertTrue(vr.diagnostics().stream()
                        .anyMatch(d -> d.code().filter(SCALE_GATE::equals).isPresent()),
                () -> "expected " + SCALE_GATE + ", got: " + vr.diagnostics());
    }

    /**
     * Only the builder's supported set is authorable, and a code-shaped entry outside it is refused with the class
     * A12 gives a name it cannot suppress ({@code validate.laws.ComputationParserInformationLawsTest}).
     */
    @Test
    void anUnsupportedCodeIsRejectedWithTheKernelsClass() {
        assertEquals(KernelCode.MVK_INVALID_SUPPRESSED_WARNING, citedBy("MVK_NO_WILDCARD"));
        assertEquals(KernelCode.MVK_INVALID_SUPPRESSED_WARNING, citedBy(SCALE_GATE, "MVK_NO_WILDCARD"));
    }

    /**
     * The class is withheld wherever the entry alone does not decide it: a keyword is a token in one condition
     * language and a name in the other, and a digit-led spelling is no name at all.
     */
    @Test
    void theClassIsWithheldWhereTheEntryAloneDoesNotDecideIt() {
        assertNull(citedBy("Sum"), "a keyword in English models, a name in German ones");
        assertNull(citedBy("7_DAYS"), "a digit-led spelling, which A12 cannot read as a name");
    }

    /**
     * A12 admits the supported code padded with whitespace, because its lexer discards the padding; the builder
     * refuses it anyway so the stored preamble keeps the one spelling every consumer compares, and cites no class
     * because A12 has none for it.
     */
    @Test
    void aPaddedSupportedCodeIsRefusedDeliberatelyAndUncited() {
        assertNull(citedBy(" " + SCALE_GATE + " "));
    }

    /** The code a refused {@code suppressWarnings(codes)} cites, or {@code null} when it cites none. */
    private static KernelCode citedBy(String... codes) {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> m.computation(m.num("/Order/BackorderQuantity")).name("C")
                        .calc(m.num("/Order/UnitPrice").value())
                        .suppressWarnings(codes));
        return refusal instanceof KernelCodeRefusal coded ? coded.code() : null;
    }
}
