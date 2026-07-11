package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.fieldFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The {@code Computation} authoring object (F2, study §4.3). Slot rules are kernel-verified and
 * pre-checked at {@code build()}: no mixing of unconditional and preconditioned alternatives
 * (MVK_ERROR_EMPTY_PRECONDITION), no reference to the computed field in preconditions/operations
 * (MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD), operation kind matches the computed field, message
 * required. Notably this killed the study's original {@code .otherwise(...)} sketch — it was
 * kernel-illegal.
 */
class ComputationAuthoringTest {

    private static BoundModel m;
    private static String modelJson;

    @BeforeAll
    static void bind() throws Exception {
        try (InputStream in = ComputationAuthoringTest.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            assertNotNull(in);
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        m = BoundModel.of(ModelIndex.ofSelfContained(modelJson));
    }

    @Test
    void buildFailureCarriesAStructuredDiagnostic() {
        // DG2b — parity with Rule.Builder: a build() precheck throws a structured PrecheckException
        // carrying a coded Diagnostic, not a bare IllegalStateException.
        PrecheckException ex = assertThrows(PrecheckException.class, () ->
                m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("NoMessage")
                        .calc(m.num("/Subscription/Billing/BaseFee").value())
                        .build());   // no message() → MVK_ERROR_MESSAGE_MISSING
        assertEquals(Optional.of("MVK_ERROR_MESSAGE_MISSING"), ex.diagnostic().code());
    }

    @Test
    void buildsAnUnconditionalComputationTheKernelAccepts() {
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFeeComp")
                .calc(m.num("/Subscription/Billing/BaseFee").value())
                .message(en("EffectiveFee must equal BaseFee."), de("Die effektive Gebühr muss der Basisgebühr entsprechen."))
                .build();

        assertEquals("/Subscription/Billing", comp.groupPath());
        assertEquals("../EffectiveFee", comp.computedFieldRelPath());

        ValidationResult vr = new RuleValidator().check(modelJson, comp.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the computation, got: " + vr.diagnostics());
    }

    @Test
    void buildsATieredComputationWithCommonPrecondition() {
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("TieredFeeComp")
                .commonPrecondition(fieldFilled(base.field()))
                .when(base.ge(100)).calc(base.value())
                .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
                .message(en("EffectiveFee follows the fee tiers."), de("Die effektive Gebühr folgt den Gebührenstufen."))
                .build();

        ValidationResult vr = new RuleValidator().check(modelJson, comp.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the tiered computation, got: " + vr.diagnostics());
    }

    @Test
    void commentsAnOperationAndAPreconditionAndTheKernelAccepts() {
        // #32 — comments cover computations: a leading ;; on an operation (a Calculation) and on a
        // precondition (a Condition). Both ride the Comments side-channel; the kernel validates them.
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        Condition filled = fieldFilled(base.field());
        Calculation passThrough = base.value();
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFeeComp")
                .commonPrecondition(filled)
                .comment(filled, "only when a base fee is set")          // comment on the precondition
                .when(base.ge(100)).calc(passThrough)
                .comment(passThrough, "tiered: pass the base fee through") // comment on the operation
                .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
                .message(en("EffectiveFee follows the fee tiers."),
                        de("Die effektive Gebühr folgt den Gebührenstufen."))
                .build();

        var draft = comp.draft();
        assertTrue(draft.commonPrecondition().contains(";; only when a base fee is set"),
                () -> "the precondition carries its comment: " + draft.commonPrecondition());
        assertTrue(draft.alternatives().get(0).operation().contains(";; tiered: pass the base fee through"),
                () -> "the operation carries its comment: " + draft.alternatives().get(0).operation());

        ValidationResult vr = new RuleValidator().check(modelJson, comp.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the commented computation, got: " + vr.diagnostics());
    }

    @Test
    void commentsTheWholeComputationViaItsFirstOperation() {
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("EffectiveFeeComp")
                .comment("effective fee equals the base fee")     // leading comment on the (first) operation
                .calc(base.value())
                .message(en("EffectiveFee must equal BaseFee."),
                        de("Die effektive Gebühr muss der Basisgebühr entsprechen."))
                .build();

        assertTrue(comp.draft().alternatives().get(0).operation().startsWith(";; effective fee equals the base fee"),
                () -> "the whole-computation comment leads the first operation: "
                        + comp.draft().alternatives().get(0).operation());

        ValidationResult vr = new RuleValidator().check(modelJson, comp.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the commented computation, got: " + vr.diagnostics());
    }

    @Test
    void rejectsMixingUnconditionalAndPreconditionedAlternatives() {
        // The study's original .otherwise(...) sketch — kernel-illegal (MVK_ERROR_EMPTY_PRECONDITION).
        NumberRef base = m.num("/Subscription/Billing/BaseFee");
        var builder = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("MixedComp")
                .when(base.ge(100)).calc(base.value())
                .calc(NumberLiteral.of("0.00"))
                .message(en("nope"), de("nein"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("MVK_ERROR_EMPTY_PRECONDITION"), ex::getMessage);
    }

    @Test
    void rejectsAnOperationOfTheWrongKind() {
        var builder = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("WrongKindComp")
                .calc(m.str("/Subscription/Billing/InvoiceEmail").field().value())
                .message(en("nope"), de("nein"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("kinds must match"), ex::getMessage);
    }

    @Test
    void rejectsReferencingTheComputedFieldInPreconditionOrOperation() {
        NumberRef computed = m.num("/Subscription/Billing/EffectiveFee");
        NumberRef base = m.num("/Subscription/Billing/BaseFee");

        var inPrecondition = m.computation(computed)
                .name("SelfPre")
                .when(computed.ge(0)).calc(base.value())
                .message(en("nope"), de("nein"));
        assertThrows(IllegalStateException.class, inPrecondition::build);

        var inOperation = m.computation(computed)
                .name("SelfOp")
                .calc(computed.value())
                .message(en("nope"), de("nein"));
        assertThrows(IllegalStateException.class, inOperation::build);
    }

    @Test
    void requiresAMessage() {
        var builder = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("NoMsgComp")
                .calc(m.num("/Subscription/Billing/BaseFee").value());
        assertThrows(IllegalStateException.class, builder::build);
    }

}
