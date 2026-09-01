package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * Kernel laws — <b>the condition directives the grammar carries outside the operator surface</b>
 * (GRAMMAR-LEDGER queue: {@code preamble}, {@code regelKontextEntity}; source:
 * {@code CheckPreambleImpl}, {@code CheckRegelKontextEntityImpl}).
 *
 * <ul>
 *   <li>{@code @SuppressWarning(NAME)} (DE {@code @UnterdrueckeWarnung}) prefixes a condition and
 *       suppresses exactly <b>one</b> supported warning: {@code MVK_INVALID_COMPARE_DEC_PLACES} —
 *       the numeric equality derived-scale gate. This class uses a differing-scale field pair as its
 *       focused witness; NumberLawsTest locks the complete unsuppressed rule.
 *       Any other name draws {@code MVK_INVALID_SUPPRESSED_WARNING}.</li>
 *   <li>{@code RuleGroup} (DE {@code RegelKontext}) references the rule's containing group as an
 *       entity; it admits the ordinary semantic-index suffix when that group has an index field, while a star
 *       remains rejected (the grammar permits the star only for a nicer error).</li>
 * </ul>
 */
class DirectiveLawsTest {

    @Test
    void suppressWarningLiftsTheEqualityScaleGate() {
        // Quantity (scale 0) == UnitPrice (scale 2) is the locked MVK_INVALID_COMPARE_DEC_PLACES
        // rejection (NumberLawsTest); the directive is the kernel's own escape hatch for it
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                "@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES) [Quantity] == [UnitPrice]");
    }

    @Test
    void onlyTheScaleGateIsSuppressible() {
        // The attempted warning name is quoted in the message; the terminal bracketed token is the
        // rejection's actual diagnostic code.
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                "@SuppressWarning(MVK_NO_WILDCARD) [Quantity] == [UnitPrice]",
                "MVK_INVALID_SUPPRESSED_WARNING");
    }

    @Test
    void ruleGroupReferencesTheRulesContainingGroup() {
        // the enclosing group counts as referencing the error field
        KernelLaws.accepts("/Subscription/Billing", "../InvoiceEmail",
                "GroupFilled(RuleGroup)");
    }

    /** The accept/refuse pair proves {@code For} selects the RuleGroup entity rather than being ignored syntax. */
    @Test
    void aSemanticIndexSuffixSelectsTheRulesContainingGroup() {
        KernelLaws.acceptsOrder("/Order/Items", "../Count",
                "GroupFilled(RuleGroup For \"SKU-1\")");
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "GroupFilled(RuleGroup For \"SKU-1\")", "MVK_NO_INDEX_FIELD");
    }

    @Test
    void aStarOnRuleGroupIsRejected() {
        KernelLaws.rejects("/Subscription/Billing", "../InvoiceEmail",
                "GroupFilled(RuleGroup*)",
                "MVK_INVALID_WILDCARD_FOR_RULEGROUP");
    }
}
