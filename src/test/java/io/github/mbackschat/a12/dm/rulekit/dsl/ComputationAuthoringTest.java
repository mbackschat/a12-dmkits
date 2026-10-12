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

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The {@code Computation} authoring object (F2, study §4.3). Slot rules are kernel-verified and
 * pre-checked at {@code build()}: no mixing of unconditional and preconditioned alternatives
 * (MVK_ERROR_EMPTY_PRECONDITION), no reference to the computed field in preconditions/operations
 * (MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD for a plain self-read,
 * MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD for a projected one — the last family below),
 * operation kind matches the computed field, message required. Notably this killed the study's original
 * {@code .otherwise(...)} sketch — it was kernel-illegal.
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
    void malformedReservedNamesRemainDelegatedToTheKernel() {
        for (String name : java.util.List.of("vk_vk.Bad", "vk_vk" + "x".repeat(96))) {
            var built = m.computation(m.num("/Subscription/Billing/EffectiveFee")).name(name)
                    .calc(m.num("/Subscription/Billing/BaseFee").value())
                    .message(en("x"), de("x")).build();
            assertEquals(name, built.name());
        }
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
    void rejectsAReservedVkVkComputationName() {
        // The reserved namespace is not the rule element's alone, despite MVK_RULE_NAME_INVALID's name —
        // the kernel rejects a computation called vk_vk_* with exactly that code (measured on both carriers,
        // locked by validate.laws.ComputationSlotLawsTest). Rule.Builder has pre-checked it since 2026-06-10.
        PrecheckException ex = assertThrows(PrecheckException.class, () ->
                m.computation(m.num("/Subscription/Billing/EffectiveFee")).name("VK_VK_FeeComp")
                        .calc(m.num("/Subscription/Billing/BaseFee").value())
                        .message(en("x"), de("x"))
                        .build());
        assertEquals(Optional.of("MVK_RULE_NAME_INVALID"), ex.diagnostic().code());
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

    /**
     * The kernel's confirm rule is asymmetric, and the pre-check has to be too. Measured on both strategies
     * through a raw-injected operation that bypasses this very pre-check
     * ({@code validate.laws.ConfirmComputationSourceKindLawsTest}): {@code True} into a CONFIRM target is
     * ACCEPTED, while {@code False} into one draws {@code MVK_INVALID_COMPARE_TO_YES} — "only ... equality or
     * unequality with the constant True". A plain kind-equality check refused both, so it made a legal model
     * unauthorable.
     */
    @Test
    void acceptsTheConstantTrueAsAConfirmComputation() {
        var computation = m.computation(m.confirm("/Subscription/TermsAccepted"))
                .name("TermsComp")
                .calc(new BoolLiteral(true))
                .message(en("Terms are accepted."), de("AGB akzeptiert."))
                .build();
        assertNotNull(computation);
        ValidationResult kernel = new RuleValidator().check(modelJson, computation.draft());
        assertTrue(kernel.ok(), () -> "the kernel must accept True into a CONFIRM target: " + kernel);
    }

    @Test
    void rejectsTheConstantFalseAsAConfirmComputationWithTheKernelsOwnCode() {
        var builder = m.computation(m.confirm("/Subscription/TermsAccepted"))
                .name("TermsComp")
                .calc(new BoolLiteral(false))
                .message(en("nope"), de("nein"));
        PrecheckException ex = assertThrows(PrecheckException.class, builder::build);
        assertEquals(Optional.of("MVK_INVALID_COMPARE_TO_YES"), ex.diagnostic().code(), ex::getMessage);
    }

    @Test
    void acceptsBothBooleanConstantsAsABooleanComputation() {
        for (boolean value : new boolean[] {true, false}) {
            var computation = m.computation(m.bool("/Subscription/AutoRenew"))
                    .name("RenewComp")
                    .calc(new BoolLiteral(value))
                    .message(en("Renewal is derived."), de("Verlaengerung abgeleitet."))
                    .build();
            ValidationResult kernel = new RuleValidator().check(modelJson, computation.draft());
            assertTrue(kernel.ok(), () -> "BOOLEAN target must accept " + value + ": " + kernel);
        }
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

    // ── KF182: the pre-check must name the kernel's PROJECTED self-read class, not the plain one ──────
    //
    // Every shape below was refused before this family existed; what it locks is WHICH code the refusal
    // names, because the code is what the author reads as the repair. The kernel verdicts these mirror are
    // in validate.laws.ComputedTargetProjectedReadLawsTest.

    private static BoundModel routing;

    private static synchronized BoundModel routing() {
        if (routing == null) {
            routing = BoundModel.ofJson(ModelDrafts.routing().toJson());
        }
        return routing;
    }

    private static final String PROJECTED = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD";
    private static final String PLAIN = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD";

    /** Builds and asserts the code the refusal names. */
    private static void refusesWith(String expectedCode, String name,
            java.util.function.UnaryOperator<Computation.Builder> shape) {
        BoundModel m = routing();
        Computation.Builder builder = shape.apply(
                m.computation(m.enumOf(SELECTED).field()).name(name).message(en("nope")));
        PrecheckException ex = assertThrows(PrecheckException.class, builder::build);
        assertEquals(Optional.of(expectedCode), ex.diagnostic().code(), ex::getMessage);
    }

    private static final String SELECTED = "/Routing/SelectedTier";
    private static final String REQUESTED = "/Routing/RequestedTier";

    private static CategoryRef tier(String path) {
        return routing().enumOf(path).category("ServiceTier");
    }

    @Test
    void aCategoryProjectedSelfReadNamesTheProjectedClassNotThePlainOne() {
        refusesWith(PROJECTED, "SelfCategory", b -> b.calc(tier(SELECTED).value()));
        refusesWith(PROJECTED, "SelfCategoryListed", b -> b.calc(Dsl.firstFilledEnum(
                tier(REQUESTED).firstFilledOperand(), tier(SELECTED).firstFilledOperand()).expression()));
    }

    /**
     * Pre-emption, and it is a CROSS-SLOT rule: the kernel decides these classes over the whole generated
     * condition, so a projected read anywhere wins over a plain read anywhere — measured on both the
     * common-precondition and the alternative-precondition pairing. A per-slot check reported whichever slot
     * it happened to visit first, which is the defect this pair exists to catch.
     */
    @Test
    void aProjectedSelfReadAnywherePreEmptsAPlainOneAnywhere() {
        refusesWith(PROJECTED, "SelfCategoryMixed", b -> b.calc(Dsl.firstFilledEnum(
                routing().enumOf(SELECTED).field().firstFilledOperand(),
                tier(SELECTED).firstFilledOperand()).expression()));
        refusesWith(PROJECTED, "SelfPlainOpProjectedCommonPre", b -> b
                .commonPrecondition(tier(SELECTED).eq("ECONOMY"))
                .calc(Dsl.firstFilledEnum(routing().enumOf(SELECTED).field().firstFilledOperand(),
                        routing().enumOf(REQUESTED).field().firstFilledOperand()).expression()));
        refusesWith(PROJECTED, "SelfPlainOpProjectedTierPre", b -> b
                .when(tier(SELECTED).eq("ECONOMY"))
                .calc(Dsl.firstFilledEnum(routing().enumOf(SELECTED).field().firstFilledOperand(),
                        routing().enumOf(REQUESTED).field().firstFilledOperand()).expression()));
    }

    /** Each precondition slot on its own, with the plain control that must keep the plain code. */
    @Test
    void aProjectedSelfReadInEitherPreconditionSlotIsClassifiedByItsProjection() {
        refusesWith(PROJECTED, "SelfCategoryCommonPre", b -> b
                .commonPrecondition(tier(SELECTED).eq("ECONOMY")).calc(tier(REQUESTED).value()));
        refusesWith(PROJECTED, "SelfCategoryTierPre", b -> b
                .when(tier(SELECTED).eq("ECONOMY")).calc(tier(REQUESTED).value()));
        refusesWith(PLAIN, "SelfPlainPre", b -> b
                .commonPrecondition(routing().enumOf(SELECTED).eq("ECONOMY")).calc(tier(REQUESTED).value()));
    }

    /**
     * The SEMANTIC-INDEX half of the same class, including the shape that reads the computed field as the
     * index KEY rather than as the indexed field — kernel-measured as the projected class too, which a
     * reading of the key as a plain lookup value would have got wrong.
     */
    @Test
    void aSemanticIndexProjectedSelfReadNamesTheProjectedClassToo() {
        BoundModel m = routing();
        NumberRef distance = m.num("/Routing/Legs/Distance");
        PrecheckException indexed = assertThrows(PrecheckException.class, () ->
                m.computation(distance).name("SelfIndexed")
                        .calc(distance.forIndex("L-1").expression())
                        .message(en("nope")).build());
        assertEquals(Optional.of(PROJECTED), indexed.diagnostic().code(), indexed::getMessage);

        StringRef plannedLeg = m.str("/Routing/PlannedLeg");
        PrecheckException keyed = assertThrows(PrecheckException.class, () ->
                m.computation(plannedLeg.field()).name("SelfIsTheKey")
                        .calc(m.str("/Routing/Legs/LegRef").forIndex(plannedLeg.field()).expression())
                        .message(en("nope")).build());
        assertEquals(Optional.of(PROJECTED), keyed.diagnostic().code(), keyed::getMessage);
    }

    @Test
    void requiresAMessage() {
        var builder = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
                .name("NoMsgComp")
                .calc(m.num("/Subscription/Billing/BaseFee").value());
        assertThrows(IllegalStateException.class, builder::build);
    }

}
