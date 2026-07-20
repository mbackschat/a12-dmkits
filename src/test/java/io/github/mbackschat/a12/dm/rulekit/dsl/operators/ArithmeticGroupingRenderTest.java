package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Precedence-aware arithmetic rendering (DEV-API-GAPS DG7): the renderer wraps a nested operand in a
 * {@code { … }} group exactly when its operator binds LOOSER than the parent's, so the rendered string
 * re-parses to the same tree. Precedence {@code +}/{@code -} &lt; {@code *}/{@code /} &lt; {@code ^}; a
 * same-precedence child is grouped wherever flattening would change the authored fold tree or erase a
 * division/power authoring-region reset. Every arithmetic node rounds independently, so even {@code +} and
 * {@code *} are not freely associative. Without this, {@code times(plus(…))} mis-rendered flat as
 * {@code … * … + …}, and nested division trees could render into a kernel-illegal plain region. Braces
 * preserve authored/check structure but intentionally do not block the later one-pass evaluator lowering.
 */
class ArithmeticGroupingRenderTest {

    private static String r(Condition c) {
        return new DslRenderer().render(c);
    }

    @Test
    void groupsAnOperandThatBindsLooserThanItsParent() {
        // * over +: the looser-binding nested operand is grouped
        assertEquals("2 * { 3 + 4 } > 0",
                r(Dsl.number("2").times(Dsl.number("3").plus(Dsl.number("4"))).gt(0)));
        // a tighter-binding child needs no group (natural precedence already holds)
        assertEquals("2 + 3 * 4 > 0",
                r(Dsl.number("2").plus(Dsl.number("3").times(Dsl.number("4"))).gt(0)));
    }

    @Test
    void groupsForAssociativityAtEqualPrecedence() {
        // Every RIGHT same-precedence operand is grouped: per-node precision makes the authored fold observable.
        assertEquals("2 - { 3 - 4 } > 0",
                r(Dsl.number("2").minus(Dsl.number("3").minus(Dsl.number("4"))).gt(0)));
        assertEquals("2 + { 3 + 4 } > 0",
                r(Dsl.number("2").plus(Dsl.number("3").plus(Dsl.number("4"))).gt(0)));
        assertEquals("2 * { 3 / 4 } > 0",
                r(Dsl.number("2").times(Dsl.number("3").dividedBy(Dsl.number("4"))).gt(0)));
        // A nested power needs braces on either side; the grammar admits only one direct '^' per power node.
        assertEquals("{ 2 ^ 3 } ^ 4 > 0",
                r(Dsl.number("2").toThePowerOf(Dsl.number("3")).toThePowerOf(Dsl.number("4")).gt(0)));
        assertEquals("2 ^ { 3 ^ 4 } > 0",
                r(Dsl.number("2").toThePowerOf(Dsl.number("3").toThePowerOf(Dsl.number("4"))).gt(0)));
    }

    @Test
    void reconstructsTheBraceResetNeededForNestedDivision() {
        assertEquals("{ 8 / 4 } / 2 > 0",
                r(Dsl.number("8").dividedBy(Dsl.number("4")).dividedBy(Dsl.number("2")).gt(0)));
        assertEquals("{ 8 / 4 * 2 } / 3 > 0",
                r(Dsl.number("8").dividedBy(Dsl.number("4")).times(Dsl.number("2"))
                        .dividedBy(Dsl.number("3")).gt(0)));
        assertEquals("8 * { 4 / 2 } / 3 > 0",
                r(Dsl.number("8").times(Dsl.number("4").dividedBy(Dsl.number("2")))
                        .dividedBy(Dsl.number("3")).gt(0)));
    }

    @Test
    void preservesBothArithmeticLoweringWitnessShapes() {
        assertEquals("2 * { 3 / 4 } * 5 > 0",
                r(Dsl.number("2").times(Dsl.number("3").dividedBy(Dsl.number("4")))
                        .times(Dsl.number("5")).gt(0)));
        assertEquals("{ 2 * { { 3 / 4 } / 5 } + 0 } * 6 > 0",
                r(Dsl.number("2")
                        .times(Dsl.number("3").dividedBy(Dsl.number("4")).dividedBy(Dsl.number("5")))
                        .plus(0).times(Dsl.number("6")).gt(0)));
    }
}
