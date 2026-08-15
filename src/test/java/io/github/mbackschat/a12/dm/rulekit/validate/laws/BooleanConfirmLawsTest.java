package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §1 — Boolean/Confirm comparability codes.
 *
 * <p>Each law is stated as "compares ONLY to X", so both halves are asserted: the refusal names the code, and
 * {@link #theAllowedComparandsAreAdmitted} shows X itself is accepted. Without that half the class would pass
 * unchanged against a kernel that refused every comparand, or a fixture whose fields did not exist — a matrix of
 * refusals alone is satisfied by a field that can do nothing at all (control added 2026-08-15).
 */
class BooleanConfirmLawsTest {

    @Test
    void confirmComparesOnlyToTrue() {
        KernelLaws.rejects("/Subscription", "../TermsAccepted",
                "[TermsAccepted] == False", "MVK_INVALID_COMPARE_TO_YES");
    }

    @Test
    void booleanComparesOnlyToTrueFalseOrBoolean() {
        KernelLaws.rejects("/Subscription", "../AutoRenew",
                "[AutoRenew] == \"yes\"", "MVK_INVALID_COMPARE_TO_YESNO");
    }

    /** The positive half of both "only" laws — the comparands each kind DOES admit. */
    @Test
    void theAllowedComparandsAreAdmitted() {
        KernelLaws.accepts("/Subscription", "../TermsAccepted", "[TermsAccepted] == True");
        KernelLaws.accepts("/Subscription", "../AutoRenew", "[AutoRenew] == True");
        KernelLaws.accepts("/Subscription", "../AutoRenew", "[AutoRenew] == False");
    }
}
