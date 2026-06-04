package io.github.mbackschat.a12.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS §7 — pattern-comparison slot rules + string-literal escaping. */
class PatternLawsTest {

    @Test
    void lookbehindIsOutsideThePortableSubset() {
        // The regex gate is Java Pattern.compile PLUS a portable-subset check — lookbehind is
        // Java-valid yet rejected, so rulekit's Pattern.compile pre-check is necessary-only.
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] PatternMatched \"(?<=a)b\"", "MVK_INVALID_PATTERN");
    }

    @Test
    void theRightSideMustBeAStringConstant() {
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] PatternMatched [InvoiceEmail]", "MVK_MISSING_CONST_IN_PATTERN_COMP");
    }

    @Test
    void stringLiteralsEscapeByQuoteDoubling() {
        // StringLiteral : '"' ( '""' | ~["] )* '"' — embedded quotes are DOUBLED...
        KernelLaws.accepts("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] == \"say \"\"hi\"\"\"");
        // ... and backslash-escaping a quote is a parse error.
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] == \"say \\\"hi\\\"\"");
    }
}
