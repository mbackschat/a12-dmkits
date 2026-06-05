package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS §1 — Boolean/Confirm comparability codes. */
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
}
