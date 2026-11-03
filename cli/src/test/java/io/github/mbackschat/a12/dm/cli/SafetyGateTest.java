package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * The refactor safety gate (CLI-SPEC §6): check the specific refactor's preconditions BEFORE any
 * mutation, refuse on the first failure, only proceed when all pass. This locks the mechanism
 * (ordering, all-pass) and the first concrete precondition shared by safe-delete and safe-rename —
 * the RK_REFERENCED reference gate. The end-to-end refusal path is covered by the remove verb tests.
 */
class SafetyGateTest {

    @Test
    void firstFailureIsEmptyWhenEveryCheckPasses() {
        SafetyGate gate = new SafetyGate()
                .require(Optional::empty)
                .require(Optional::empty);
        assertTrue(gate.firstFailure().isEmpty(), "all preconditions pass → the refactor is safe");
    }

    @Test
    void firstFailureReturnsTheFirstFailingCheckInRegistrationOrder() {
        Refusal first = SafetyChecks.referencedBy("/Order/A", Set.of("/Order/R1"), Set.of(), Set.of(), "fix A");
        Refusal second = SafetyChecks.referencedBy("/Order/B", Set.of("/Order/R2"), Set.of(), Set.of(), "fix B");
        SafetyGate gate = new SafetyGate()
                .require(Optional::empty)
                .require(() -> Optional.of(first))
                .require(() -> Optional.of(second));

        Refusal failure = gate.firstFailure().orElseThrow();
        assertSame(first, failure, "the first failing precondition (registration order) wins");
    }

    @Test
    void referencedByCarriesTheReferrersAndACorrectiveFix() {
        Refusal refusal = SafetyChecks.referencedBy("/Order/DeliveryDate",
                Set.of("/Order/DeliveryNotBeforeOrder"), Set.of("/Order/LeadTimeComp"), Set.of(),
                "rename the referrers first");

        assertEquals("RK_REFERENCED", refusal.diagnostic().code().orElseThrow());
        assertEquals("rename the referrers first", refusal.diagnostic().fix().orElseThrow());
        assertEquals("/Order/DeliveryDate", refusal.diagnostic().where().path().orElseThrow());

        var referencedBy = refusal.data().get("referencedBy");
        assertEquals("/Order/DeliveryNotBeforeOrder", referencedBy.get("rules").get(0).asText());
        assertEquals("/Order/LeadTimeComp", referencedBy.get("computations").get(0).asText());
    }

    @Test
    void referencedByEmitsAPrecheckErrorDiagnostic() {
        // the safe-delete reference gate's contract (its end-to-end use is covered by the remove verb tests).
        Diagnostic d = SafetyChecks.referencedBy("/X", Set.of("/R"), Set.of(), Set.of(), "do Y").diagnostic();
        assertEquals(io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource.PRECHECK, d.source());
        assertEquals(io.github.mbackschat.a12.dm.core.Severity.ERROR, d.severity());
    }
}
