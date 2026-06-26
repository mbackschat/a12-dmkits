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
 * same-precedence child is grouped only where associativity demands it (the right operand of left-associative
 * {@code -}/{@code /}; the left operand of right-associative {@code ^}). Without this, {@code times(plus(…))}
 * mis-rendered flat as {@code … * … + …}.
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
        // left-associative '-': the RIGHT same-precedence operand is grouped (a - {b - c} != a - b - c)
        assertEquals("2 - { 3 - 4 } > 0",
                r(Dsl.number("2").minus(Dsl.number("3").minus(Dsl.number("4"))).gt(0)));
        // right-associative '^': the LEFT same-precedence operand is grouped ({a ^ b} ^ c)
        assertEquals("{ 2 ^ 3 } ^ 4 > 0",
                r(Dsl.number("2").toThePowerOf(Dsl.number("3")).toThePowerOf(Dsl.number("4")).gt(0)));
        // but '^' right-associates, so the RIGHT same-precedence operand is NOT grouped (a ^ b ^ c)
        assertEquals("2 ^ 3 ^ 4 > 0",
                r(Dsl.number("2").toThePowerOf(Dsl.number("3").toThePowerOf(Dsl.number("4"))).gt(0)));
    }
}
