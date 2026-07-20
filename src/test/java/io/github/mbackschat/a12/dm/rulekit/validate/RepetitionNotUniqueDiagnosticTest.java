package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Corrective guidance distinguishes the RNU count law from an illegal negative-presence Or branch. */
class RepetitionNotUniqueDiagnosticTest {

    @Test
    void atMostOneCodePointsToOneRnuLeaf() {
        StructuralKernelFix.Hint hint = StructuralKernelFix
                .forCode("MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE")
                .orElseThrow();

        assertTrue(hint.fix().contains("exactly one RepetitionNotUnique"));
        assertTrue(hint.explain().contains("structural"));
    }

    @Test
    void negativeIterationCodeExplainsTheOrBranchInsteadOfBlamingRnuComposition() {
        StructuralKernelFix.Hint hint = StructuralKernelFix
                .forCode("MVK_NEG_CONDITION_IN_ITERATION")
                .orElseThrow();

        assertTrue(hint.fix().contains("Or branch"));
        assertTrue(hint.fix().contains("positively guarded"));
        assertTrue(hint.explain().contains("not a restriction on RepetitionNotUnique"));
    }
}
