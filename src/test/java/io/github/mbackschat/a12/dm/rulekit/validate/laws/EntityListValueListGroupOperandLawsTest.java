package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * Kernel admission of a <b>GROUP operand in the ENTITY-list value-list quantifier</b> — the carrier whose
 * verdict decides {@code DG35}'s type question, and the one row the ledger names as the blocker.
 *
 * <p><b>Why this carrier and not the one already measured.</b> [KF187] measures the three plural value-list
 * quantifiers as ADMITTING a group operand, but only in their <em>literal</em> form
 * ({@code … In "a", "b"} — {@code FieldListValueListCondition}). The sibling {@code FieldListEntityListCondition}
 * puts <em>entity paths</em> on the value side, and it is a different carrier. KF187's own finding is that
 * carriers inside one family can disagree — two of them do — so the literal form's ACCEPT transfers to the entity
 * form by exactly the analogy that finding forbids.
 *
 * <p><b>What hung on it, and how it resolved.</b> The fix for {@code DG35} is to add {@code GroupListRef} to
 * {@code ValueListOperand}'s permits. That type is shared, so widening it also admits a group into <em>both</em>
 * slots of {@code FieldListEntityListCondition}. Had this carrier refused, the widening would have made the
 * typed surface author a shape the kernel rejects — "never broader than its measured basis" violated in the
 * direction hardest to see, since nothing fails until a user writes it — and the answer would instead have been
 * a narrower sealed type for the literal record's slot.
 *
 * <p><b>It accepts, in both slots</b>, so the shared widening is the correct fix and now has a measured basis
 * rather than a convenient one. Recorded because <b>the prediction going in was the opposite</b>: the ledger row
 * this test closes described the widening as "the tempting fix is the wrong one", reasoning from an unmeasured
 * carrier. That reasoning was the thing to check, and checking it cost one run.
 *
 * <p>Both slots are measured separately, because they are separate slots and nothing says they share a gate.
 * The all-fields control establishes the carrier itself is authorable on this fixture, so each verdict below is
 * attributable to the group operand rather than to the entity-list spelling.
 */
class EntityListValueListGroupOperandLawsTest {

    /** A non-repeatable group whose expansion is two String fields — same base kind as the value side. */
    private static final String GROUP = "/Order/ShippingAddress";

    private static final String GUARD = "FieldFilled(/Order/Quantity) And ";

    /**
     * <b>THE CONTROL, and it runs first for a reason.</b> If the entity-list form were unauthorable here for an
     * unrelated reason, both rows below would reject and would look like a group-operand gate.
     */
    @Test
    void theEntityListFormItselfIsAuthorableOnThisFixture() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "AtLeastOneFieldValueIncludedInValueList(/Order/ShippingAddress/Street, "
                        + "/Order/BillingAddress/PostalCode In /Order/CustomerName, /Order/ProductCode)");
    }

    /** The left-hand ({@code fields}) slot. */
    @Test
    void theEntityListFormAdmitsAGroupInTheFieldSlot() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "AtLeastOneFieldValueIncludedInValueList(" + GROUP
                        + " In /Order/CustomerName, /Order/ProductCode)");
    }

    /** The right-hand ({@code valueFields}) slot — a separate slot, so a separate row. */
    @Test
    void theEntityListFormAdmitsAGroupInTheValueSlot() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "AtLeastOneFieldValueIncludedInValueList(/Order/CustomerName, /Order/ProductCode In "
                        + GROUP + ")");
    }
}
