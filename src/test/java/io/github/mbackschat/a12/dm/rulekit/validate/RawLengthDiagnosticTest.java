package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/** The raw-Length corrective is contextual and must never relabel an unrelated internal kernel error. */
class RawLengthDiagnosticTest {

    private static final String MODEL = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", root -> root
                    .stringField("Raw", field -> field.lineBreaks().noValueValidation())
                    .stringField("Ordinary", field -> { }))
            .build().toJson();

    @Test
    void anOrdinaryStringLengthInternalErrorGetsNoRawLengthFix() {
        ValidationResult result = RawLengthDiagnostic.enrich(internalError(), MODEL,
                rule("Length(Ordinary) > 5.0"), ReferenceResolvers.selfContained());

        assertTrue(result.diagnostics().get(0).fix().isEmpty());
    }

    @Test
    void anUnrelatedInternalErrorOnTheRawFieldGetsNoRawLengthFix() {
        ValidationResult result = RawLengthDiagnostic.enrich(internalError(), MODEL,
                rule("FieldFilled(Raw)"), ReferenceResolvers.selfContained());

        assertTrue(result.diagnostics().get(0).fix().isEmpty());
    }

    private static ValidationResult internalError() {
        Diagnostic diagnostic = new Diagnostic(Severity.ERROR, DiagnosticSource.KERNEL,
                Optional.of("MVK_INTERNAL_ERROR"), "unrelated internal failure",
                Diagnostic.Where.path("/Claim/Probe"), Optional.empty(), Optional.empty());
        return ValidationResult.of(List.of(diagnostic), Verification.KERNEL_CONFIRMED);
    }

    private static DraftRule rule(String condition) {
        return new DraftRule("/Claim", "Probe", "../Raw", condition, "X", "ERROR");
    }
}
