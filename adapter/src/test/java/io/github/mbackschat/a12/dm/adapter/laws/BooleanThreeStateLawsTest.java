package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>Boolean is a three-state field type</b> (KERNEL-SEMANTICS §1): an unset
 * Boolean is a distinct state from {@code True} and {@code False}. The full matrix (verified, in a
 * filled row):
 *
 * <table><caption>which check is satisfied per state</caption>
 *   <tr><th>state</th><th>{@code == True}</th><th>{@code == False}</th><th>{@code FieldFilled}</th></tr>
 *   <tr><td>unset</td><td>—</td><td>—</td><td>—</td></tr>
 *   <tr><td>True</td><td>✓</td><td>—</td><td>✓</td></tr>
 *   <tr><td>False</td><td>—</td><td>✓</td><td>✓</td></tr>
 * </table>
 *
 * <p>The traps this pins: {@code [Flag] == True} is <b>not</b> equivalent to {@code FieldFilled(Flag)}
 * (they diverge for a {@code False} value), and {@code [Flag] == False} does <b>not</b> mean "unchecked"
 * — an unset Boolean satisfies neither equality. (Contrast a Confirm, whose unset value <em>is</em>
 * treated as False — `EmptyValueLawsTest`.)
 */
class BooleanThreeStateLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");
    private static final String FLAG = "/Order/ExpressShipping";

    private static final String EQ_TRUE = RuntimeLaws.withRule(ORDER, "/Order", "R", "../ExpressShipping",
            "EQ_TRUE", "[ExpressShipping] == True");
    private static final String EQ_FALSE = RuntimeLaws.withRule(ORDER, "/Order", "R", "../ExpressShipping",
            "EQ_FALSE", "[ExpressShipping] == False");
    private static final String FILLED = RuntimeLaws.withRule(ORDER, "/Order", "R", "../ExpressShipping",
            "FILLED", "FieldFilled(ExpressShipping)");

    @Test
    void unsetBooleanSatisfiesNeitherEqualityNorFilled() {
        Set<String> empty = Set.of(FLAG);                       // present but valueless = unset
        Map<String, String> row = Map.of("/Order/CustomerName", "Acme");
        assertEquals(false, RuntimeLaws.fires(EQ_TRUE, "EQ_TRUE", ORDER_ROW, empty, row), "unset !== True");
        assertEquals(false, RuntimeLaws.fires(EQ_FALSE, "EQ_FALSE", ORDER_ROW, empty, row), "unset !== False");
        assertEquals(false, RuntimeLaws.fires(FILLED, "FILLED", ORDER_ROW, empty, row), "unset is not FieldFilled");
    }

    @Test
    void trueBooleanSatisfiesEqTrueAndFilledButNotEqFalse() {
        Map<String, String> row = Map.of("/Order/CustomerName", "Acme", FLAG, "true");
        assertEquals(true, RuntimeLaws.fires(EQ_TRUE, "EQ_TRUE", ORDER_ROW, row), "true == True");
        assertEquals(false, RuntimeLaws.fires(EQ_FALSE, "EQ_FALSE", ORDER_ROW, row), "true !== False");
        assertEquals(true, RuntimeLaws.fires(FILLED, "FILLED", ORDER_ROW, row), "true is FieldFilled");
    }

    @Test
    void falseBooleanSatisfiesEqFalseAndFilledButNotEqTrue() {
        Map<String, String> row = Map.of("/Order/CustomerName", "Acme", FLAG, "false");
        assertEquals(false, RuntimeLaws.fires(EQ_TRUE, "EQ_TRUE", ORDER_ROW, row), "false !== True");
        assertEquals(true, RuntimeLaws.fires(EQ_FALSE, "EQ_FALSE", ORDER_ROW, row), "false == False");
        assertEquals(true, RuntimeLaws.fires(FILLED, "FILLED", ORDER_ROW, row),
                "false is FieldFilled — so [Flag] == True ≠ FieldFilled(Flag)");
    }
}
