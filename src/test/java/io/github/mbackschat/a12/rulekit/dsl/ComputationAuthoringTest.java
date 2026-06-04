package io.github.mbackschat.a12.rulekit.dsl;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.fieldFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.model.ModelIndex;
import io.github.mbackschat.a12.rulekit.model.ModelLoader;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

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
        try (Reader reader = fixture("models/subscription.dm.json")) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));
        }
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

    private static Reader fixture(String classpathLocation) {
        InputStream in = ComputationAuthoringTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull(in, "fixture not found: " + classpathLocation);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
