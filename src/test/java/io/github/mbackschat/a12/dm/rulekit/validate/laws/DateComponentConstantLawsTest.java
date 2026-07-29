package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — a {@code Date(...)} / {@code Time(...)} component sits in the kernel's
 * {@code datumParam} position, whose grammar is {@code entitySpec | datumExtractOp | StringLiteral}. There is
 * <b>no bare-number alternative</b>: a numeric constant component must be QUOTED, and the bare spelling draws
 * {@code MVK_INVALID_ENTITY} because the kernel reads the digits as an entity name.
 *
 * <p>This pair is the kernel side of a defect in our own authoring path: {@code DslRenderer} rendered a
 * {@link io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral} component bare, so {@code Dsl.date(number(30),
 * number(9), number(1916))} produced a condition the kernel refuses. Nothing exercised the shape end-to-end,
 * which is why it survived. The rendering itself is locked in
 * {@code dsl.RenderConditionTest.rendersConstantDateAndTimeComponentsQuoted}.
 *
 * <p>Also locks the combined shape a12-kernel-lean's SPEC-2026-07-29-01 asks for — a left-hand Date shift
 * compared against a constructed Date — whose joint authorability had not previously been measured on either
 * side.
 */
class DateComponentConstantLawsTest {

    @Test
    void aQuotedNumericComponentIsAdmitted() {
        accepts("[OrderDate] == Date(\"1\", \"10\", \"1916\")");
        accepts("[OrderDate] == Date(\"1\", \"1\", \"19\", \"63\")");
    }

    @Test
    void aBareNumericComponentIsRejectedAsAnEntityName() {
        rejects("[OrderDate] == Date(1, 10, 1916)", "MVK_INVALID_ENTITY");
    }

    /** A Date SHIFT on the left and a CONSTRUCTED Date on the right compose — measured, not assumed. */
    @Test
    void aDateShiftComparedAgainstAConstructedDateIsAdmitted() {
        accepts("AddDays(Date(\"30\", \"9\", \"1916\"), 1) == Date(\"1\", \"10\", \"1916\")");
        accepts("AddMonths(Date(\"1\", \"9\", \"1916\"), 1) == Date(\"1\", \"10\", \"1916\")");
        accepts("AddYears(Date(\"1\", \"10\", \"1915\"), 1) == Date(\"1\", \"10\", \"1916\")");
        // the inverse controls, so the comparison operator itself is not the admitted half
        accepts("AddDays(Date(\"30\", \"9\", \"1916\"), 1) != Date(\"1\", \"10\", \"1916\")");
        accepts("AddYears(Date(\"1\", \"10\", \"1915\"), 1) != Date(\"1\", \"10\", \"1916\")");
    }

    /** Each half is separately admitted, so a future rejection is attributable to the composition. */
    @Test
    void bothHalvesAreIndependentlyAdmitted() {
        accepts("AddDays(Date(\"30\", \"9\", \"1916\"), 1) == [OrderDate]");
        accepts("AddDays(OrderDate, 1) == Date(\"1\", \"10\", \"1916\")");
    }

    private static void accepts(String condition) {
        KernelLaws.acceptsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition);
    }

    private static void rejects(String condition, String code) {
        KernelLaws.rejectsOrder("/Order", "../CustomerName", "FieldFilled(CustomerName) And " + condition, code);
    }
}
