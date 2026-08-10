package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/** KERNEL-FINDINGS §7 — pattern-comparison slot rules + string-literal escaping. */
class PatternLawsTest {

    @Test
    void lookbehindIsOutsideThePortableSubset() {
        // The regex gate is Java Pattern.compile PLUS additional kernel admission restrictions — lookbehind is
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
    void aNumericRightOperandIsRejectedAsTheWrongPatternComparisonType() {
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] PatternMatched 1", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON");
    }

    @Test
    void aNumberFieldOnTheLeftIsRejectedAsTheWrongPatternComparisonType() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "[Quantity] PatternMatched \"A+\"", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON");
    }

    @Test
    void patternViolatedHasTheSameThreeSlotRejections() {
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] PatternViolated [InvoiceEmail]", "MVK_MISSING_CONST_IN_PATTERN_COMP");
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] PatternViolated 1", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON");
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "[Quantity] PatternViolated \"A+\"", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON");
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

    @Test
    void aPatternLiteralPreservesBackslashesAndDoubledQuotes() {
        KernelLaws.accepts("/Subscription/Billing", "../InvoiceEmail",
                "[InvoiceEmail] PatternMatched \"say \"\"hi\"\"\\d\"");
    }
}
