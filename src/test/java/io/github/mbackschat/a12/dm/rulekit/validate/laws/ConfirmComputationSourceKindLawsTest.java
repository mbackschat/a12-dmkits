package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Accept/reject — <b>the boolean constants against a CONFIRM computation target are asymmetric</b>, and this is
 * the measured basis for the only client-side refusal that encodes it.
 *
 * <p>A CONFIRM field has no false: the kernel accepts {@code True} as a computation operation on one and
 * rejects {@code False} with {@code MVK_INVALID_COMPARE_TO_YES} — "It is only allowed to compare equality of a
 * field of type confirm with another field of type confirm or to compare equality or unequality with the
 * constant True." A BOOLEAN target takes both.
 *
 * <p><b>Why the raw {@link DraftComputation} route is load-bearing here.</b> Our typed builder used to refuse
 * every BOOLEAN operation into a CONFIRM target on plain kind equality, so measuring through it would only have
 * re-read our own opinion. These rows go through raw operation strings, which reach the kernel without passing
 * that pre-check — the only way an over-rejection is visible at all. The last two tests then assert the typed
 * builder agrees with what the kernel said, in both directions.
 *
 * <p>Own domain: a claim intake with an escalation flag and an acknowledgement confirmation.
 */
class ConfirmComputationSourceKindLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String CONFIRM_TARGET = "../Acknowledged";
    private static final String BOOLEAN_TARGET = "../Escalated";

    private static final String MODEL = DocumentModelDraft.named("confirm-computation-kinds").locale("en_US")
            .rootGroup("Claim", claim -> claim
                    .field("Escalated", FieldKind.BOOLEAN)
                    .field("Acknowledged", FieldKind.CONFIRM))
            .build().toJson();

    @Test
    void theKernelAcceptsTrueIntoAConfirmTarget() {
        ValidationResult result = check(CONFIRM_TARGET, "True");
        assertTrue(result.ok(), () -> "True must be a legal CONFIRM computation: " + result.diagnostics());
    }

    @Test
    void theKernelRejectsFalseIntoAConfirmTargetBecauseConfirmHasNoFalse() {
        ValidationResult result = check(CONFIRM_TARGET, "False");
        assertFalse(result.ok(), "False into a CONFIRM target must be rejected");
        assertEquals(List.of("MVK_INVALID_COMPARE_TO_YES"),
                result.diagnostics().stream().flatMap(d -> d.code().stream()).toList(),
                () -> "unexpected diagnostics: " + result.diagnostics());
    }

    /** The polarity control: without it the asymmetry above could be a property of boolean constants at large. */
    @Test
    void theKernelAcceptsBothConstantsIntoABooleanTarget() {
        for (String constant : List.of("True", "False")) {
            ValidationResult result = check(BOOLEAN_TARGET, constant);
            assertTrue(result.ok(),
                    () -> constant + " must be a legal BOOLEAN computation: " + result.diagnostics());
        }
    }

    @Test
    void ourTypedBuilderPermitsExactlyWhatTheKernelAccepts() {
        BoundModel model = BoundModel.ofJson(MODEL);
        var accepted = model.computation(model.confirm("/Claim/Acknowledged")).name("AckComp")
                .calc(new BoolLiteral(true)).message(Dsl.en("Acknowledged is derived.")).build();
        assertTrue(VALIDATOR.check(MODEL, accepted.draft()).ok(),
                "the builder's True/CONFIRM output must also pass the kernel");

        PrecheckException refused = org.junit.jupiter.api.Assertions.assertThrows(PrecheckException.class,
                () -> model.computation(model.confirm("/Claim/Acknowledged")).name("AckComp")
                        .calc(new BoolLiteral(false)).message(Dsl.en("nope")).build());
        assertEquals(Optional.of("MVK_INVALID_COMPARE_TO_YES"), refused.diagnostic().code(),
                () -> "the refusal must carry the kernel's own code, not a kind-mismatch message: "
                        + refused.getMessage());
    }

    private static ValidationResult check(String computedFieldRelPath, String operation) {
        return VALIDATOR.check(MODEL, new DraftComputation("/Claim", "KindProbe", computedFieldRelPath,
                null, List.of(new DraftComputation.Alternative(null, operation))));
    }
}
