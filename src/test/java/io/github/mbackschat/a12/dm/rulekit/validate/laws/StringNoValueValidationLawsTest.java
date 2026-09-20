package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS KF36 — the co-requirement the {@code noValueValidation} flag carries: a string field
 * skipping per-value validation must also permit line breaks, or the kernel refuses the MODEL with
 * {@code MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK}.
 *
 * <p>modelkit refuses the pair at {@code build()} (API-FINDINGS 47), which is why the illegal shape cannot be
 * authored through the typed surface and the fixture is corrupted after emission instead. That refusal is the
 * reason this law exists: a client-side refusal states a rule the kernel already has, and until the kernel was
 * asked here the rule rested on a probe nothing re-ran.
 */
class StringNoValueValidationLawsTest {

    /** The kernel-legal pair, as modelkit emits it. */
    private static final String LEGAL = DocumentModelDraft.named("no-value-validation").locale("en_US")
            .rootGroup("Doc", d -> d
                    .field("Error", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING)
                    .stringField("Payload", s -> s.noValueValidation().lineBreaks()))
            .build().toJson();

    /**
     * The same model with the line-break permission taken back out. modelkit cannot build this, so the key is
     * removed from the emitted JSON — and the removal is asserted, because a corruption that silently failed
     * to apply would leave this law measuring the legal model and passing for the wrong reason.
     */
    private static final String ILLEGAL =
            LEGAL.replaceAll("\\s*\"lineBreaksPermitted\"\\s*:\\s*true\\s*,", "");

    @Test
    void theCorruptionUnderTestActuallyApplied() {
        assertNotEquals(LEGAL, ILLEGAL, "the line-break key was not where this law expected it");
        assertEquals(-1, ILLEGAL.indexOf("lineBreaksPermitted"), "a second copy of the key survived");
    }

    @Test
    void noValueValidationWithoutLineBreaksIsRefusedByTheKernel() {
        KernelLaws.rejectsModel(ILLEGAL, "/Doc", "../Error", "FieldFilled(Error)",
                "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK");
    }

    /** The control that isolates the line-break key as the only variable between the two models. */
    @Test
    void theSamePairWithLineBreaksIsAccepted() {
        KernelLaws.acceptsModel(LEGAL, "/Doc", "../Error", "FieldFilled(Error)");
    }
}
