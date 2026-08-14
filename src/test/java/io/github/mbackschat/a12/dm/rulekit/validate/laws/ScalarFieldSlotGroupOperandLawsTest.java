package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * Kernel admission of a <b>GROUP operand in a single-field slot</b> — the four operators whose interpreter arms
 * resolve their operand through {@code pathOf} without a group branch ({@code IG136}'s last triage item).
 *
 * <p><b>Why this is measured rather than reasoned.</b> Those four sites look safe by inspection: each names one
 * scalar field, so "a group cannot arrive here" is the obvious disposition. That disposition is a
 * <b>reachability</b> claim, and this repo's standing rule is that a reachability claim stays a hypothesis until
 * someone tries to author the shape — a rule written after {@code IF287} found twenty-five sites where the
 * obvious reading was wrong. {@code KF187} sharpens it further: group-operand admission is per-CARRIER, and two
 * carriers there disagree with their own family member, so no verdict transfers by analogy.
 *
 * <p>The consequence of each outcome is different, which is what makes the run worth it:
 *
 * <ul>
 *   <li><b>Kernel REJECTS</b> — the site is unreachable, {@code pathOf}'s narrow contract is correct there, and
 *       the disposition is now measured rather than assumed. Nothing to fix.</li>
 *   <li><b>Kernel ACCEPTS</b> — a live {@code IF287} instance: the interpreter would read a value at a path that
 *       holds none, and the failure is silent rather than an exception.</li>
 * </ul>
 *
 * <p><b>Every rejection carries its own field control</b>, asserted to ACCEPT on the same fixture. Without one, a
 * rejection cannot be attributed to the group operand rather than to the spelling — the trap that put three rows
 * in the excluded pile of our own admission sweep.
 */
class ScalarFieldSlotGroupOperandLawsTest {

    /** A non-repeatable group holding two String fields — the shape a single-field slot might silently accept. */
    private static final String GROUP = "/Order/ShippingAddress";

    private static final String GUARD = "FieldFilled(/Order/Quantity) And ";

    // ---- Valid(field, "Type") — the ValidType overload, operand slot: string-or-enumeration-field ----

    @Test
    void validTypeRefusesAGroupOperand() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                GUARD + "Valid(" + GROUP + ", \"EMail\")", "MVK_NO_GROUPS_ALLOWED");
    }

    @Test
    void validTypeAcceptsTheFieldControl() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "Valid(/Order/CustomerName, \"EMail\")");
    }

    // ---- Invalid(field, "Type") — the complement carrier, measured separately per KF187 ----

    @Test
    void invalidTypeRefusesAGroupOperand() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                GUARD + "Invalid(" + GROUP + ", \"IBAN\")", "MVK_NO_GROUPS_ALLOWED");
    }

    @Test
    void invalidTypeAcceptsTheFieldControl() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "Invalid(/Order/ProductCode, \"IBAN\")");
    }

    // ---- FieldValueAsString(field) ----

    @Test
    void fieldValueAsStringRefusesAGroupOperand() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                GUARD + "FieldValueAsString(" + GROUP + ") == \"0\"", "MVK_NO_GROUPS_ALLOWED");
    }

    @Test
    void fieldValueAsStringAcceptsTheFieldControl() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "FieldValueAsString(/Order/Quantity) == \"0\"");
    }

    // ---- ValueAsDate(field, FirstDay) ----

    @Test
    void valueAsDateRefusesAGroupOperand() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                GUARD + "ValueAsDate(" + GROUP + ", FirstDay) > \"01.01.2020\"", "MVK_NO_GROUPS_ALLOWED");
    }

    @Test
    void valueAsDateAcceptsTheFieldControl() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "ValueAsDate(/Order/ApproxDate, FirstDay) > \"01.01.2020\"");
    }

    // ---- RangeAsString(field, start, end) — the `rangeSubstring` arm ----

    @Test
    void rangeAsStringRefusesAGroupOperand() {
        KernelLaws.rejectsOrder("/Order", "../Quantity",
                GUARD + "RangeAsString(" + GROUP + ", 1, 3) == \"BAD\"", "MVK_NO_GROUPS_ALLOWED");
    }

    @Test
    void rangeAsStringAcceptsTheFieldControl() {
        KernelLaws.acceptsOrder("/Order", "../Quantity",
                GUARD + "RangeAsString(/Order/ProductCode, 1, 3) == \"BAD\"");
    }
}
