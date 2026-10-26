package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Verification;

/**
 * {@link RuleValidator#checkTogether} asks the kernel once for several drafts and attributes each finding to the
 * draft the kernel names, so a caller can read one verdict per draft without one kernel pass per draft.
 *
 * <p>The oracle is the one-draft {@link RuleValidator#check}: each draft's {@code MVK_ERROR_FIELD_NOT_REFERENCED}
 * must agree with it. The drafts share one condition and one error code, as the error-field suggestion's
 * candidates do; only the error field differs, and only the field read solely inside the unmarked {@code Having}
 * body is refused (KERNEL-FINDINGS KF488), so the pair discriminates per-draft attribution from a shared verdict.
 */
class RuleValidatorCheckTogetherTest {

    private static final String MODEL = DocumentModelDraft.named("check-together").locale("en_US")
            .rootGroup("Shipment", s -> s
                    .group("Received", r -> r.repeatable(20)
                            .field("Quantity", FieldKind.NUMBER)
                            .field("Note", FieldKind.STRING)))
            .build().toJson();
    private static final String GROUP = "/Shipment/Received";
    private static final String CONDITION = "Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Note] == \"x\") > 0";
    private static final String NOT_REFERENCED = "MVK_ERROR_FIELD_NOT_REFERENCED";

    private static DraftRule draft(String name, String errorField) {
        return new DraftRule(GROUP, name, "../" + errorField, CONDITION, "RECEIVED_SUM", "ERROR");
    }

    private static boolean refusedAlone(DraftRule draft) {
        return new RuleValidator().check(MODEL, draft).diagnostics().stream()
                .anyMatch(d -> d.code().filter(NOT_REFERENCED::equals).isPresent());
    }

    @Test
    void eachFindingIsAttributedToTheDraftTheKernelNamesAndAgreesWithTheOneDraftCheck() {
        DraftRule onNote = draft("OnNote", "Note");
        DraftRule onQuantity = draft("OnQuantity", "Quantity");
        ValidationResult together = new RuleValidator()
                .checkTogether(MODEL, List.of(onNote, onQuantity), ReferenceResolvers.selfContained());

        Set<String> refused = together.diagnostics().stream()
                .filter(d -> d.code().filter(NOT_REFERENCED::equals).isPresent())
                .map(d -> d.where().rule())
                .collect(Collectors.toSet());

        assertEquals(Set.of(GROUP + "/OnNote"), refused, together::toString);
        assertEquals(List.of(true, false), List.of(refusedAlone(onNote), refusedAlone(onQuantity)),
                "the one-draft oracle no longer splits the pair, so this row stopped discriminating");
        assertEquals(java.util.Optional.of(Verification.KERNEL_CONFIRMED), together.consistencyGate());
    }

    @Test
    void draftsSharingAPathAreRefusedBecauseTheKernelCouldNotTellThemApart() {
        assertThrows(IllegalArgumentException.class, () -> new RuleValidator().checkTogether(MODEL,
                List.of(draft("Same", "Note"), draft("Same", "Quantity")), ReferenceResolvers.selfContained()));
    }
}
