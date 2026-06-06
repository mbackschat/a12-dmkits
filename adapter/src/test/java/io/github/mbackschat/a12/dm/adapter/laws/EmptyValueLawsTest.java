package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>non-specified (empty) values</b> (KERNEL-SEMANTICS §2): an empty <b>number</b>
 * field has a default of {@code 0} substituted in a comparison, so {@code [Quantity] < 100} <em>fires</em>
 * on an empty Quantity (0 &lt; 100). The documented mitigation is a {@code FieldFilled} guard.
 *
 * <p><b>Precondition (verified, the subtle part):</b> the substitution applies to an empty field
 * <em>within a filled group row</em> — a row made non-empty by at least one sibling value. An
 * <em>entirely</em> empty row (group instance with no field values) is not evaluated, so the same rule
 * does not fire there. The tests pin both halves so the distinction can't silently regress.
 *
 * <p>The type-asymmetry is the trap — the <em>same</em> comparison shape behaves differently by type
 * when the field is empty (all verified here, in a filled row):
 *
 * <table><caption>empty value in a comparison</caption>
 *   <tr><th>type</th><th>example</th><th>empty behaviour</th></tr>
 *   <tr><td>number</td><td>{@code [Quantity] < 100}</td><td>fires — {@code 0} substituted</td></tr>
 *   <tr><td>confirm</td><td>{@code [TermsConfirmed] != True}</td><td>fires — treated as {@code False}</td></tr>
 *   <tr><td>boolean</td><td>{@code [ExpressShipping] == True}</td><td>not evaluated</td></tr>
 *   <tr><td>string</td><td>{@code [ProductCode] == "ABC"}</td><td>not evaluated</td></tr>
 * </table>
 *
 * <p>The empty <b>date</b> case (also "not evaluated") additionally needs the date-literal syntax of
 * KERNEL-FINDINGS §6 and is deferred to its own increment.
 */
class EmptyValueLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");
    /** A sibling value that makes the /Order row genuinely filled (so its empty fields are evaluated). */
    private static final Map<String, String> FILLED_ROW = Map.of("/Order/CustomerName", "Acme");

    @Test
    void emptyNumberInAFilledRowIsSubstitutedWithZero() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyLow", "../Quantity", "QTY_LOW",
                "[Quantity] < 100");

        // row filled by a sibling, Quantity empty → treated as 0 → 0 < 100 → fires
        assertTrue(RuntimeLaws.fires(model, "QTY_LOW", ORDER_ROW, FILLED_ROW),
                "an empty number in a filled row is substituted with 0 (0 < 100), so the rule fires");
        // a real value of 200 is not < 100 → does not fire
        assertFalse(RuntimeLaws.fires(model, "QTY_LOW", ORDER_ROW, Map.of("/Order/Quantity", "200")),
                "a filled value of 200 is not < 100, so the rule does not fire");
    }

    @Test
    void anEntirelyEmptyRowIsNotEvaluated() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyLow", "../Quantity", "QTY_LOW",
                "[Quantity] < 100");

        // group row present but with NO field values at all → not evaluated → no 0-substitution, no fire
        assertFalse(RuntimeLaws.fires(model, "QTY_LOW", ORDER_ROW, Map.of()),
                "an entirely empty row is not evaluated, so the 0-substitution does not occur");
    }

    @Test
    void emptyConfirmIsTreatedAsFalse() {
        // a Confirm is a checkbox whose only value is True; unspecified counts as False in a comparison,
        // so "not confirmed" ([Confirm] != True) FIRES on an empty TermsConfirmed (in a filled row)
        String model = RuntimeLaws.withRule(ORDER, "/Order", "MustConfirm", "../TermsConfirmed", "NOT_CONFIRMED",
                "[TermsConfirmed] != True");

        assertTrue(RuntimeLaws.fires(model, "NOT_CONFIRMED", ORDER_ROW, Set.of("/Order/TermsConfirmed"), FILLED_ROW),
                "an empty confirm is treated as False, so [Confirm] != True fires");
        assertFalse(RuntimeLaws.fires(model, "NOT_CONFIRMED", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/TermsConfirmed", "true")),
                "a confirmed (true) value is not != True, so the rule does not fire");
    }

    @Test
    void emptyBooleanIsNotEvaluated() {
        // unlike confirm, an empty Boolean leaves the comparison NOT evaluated — the asymmetry trap
        String model = RuntimeLaws.withRule(ORDER, "/Order", "MustExpress", "../ExpressShipping", "NOT_EXPRESS",
                "[ExpressShipping] == True");

        assertFalse(RuntimeLaws.fires(model, "NOT_EXPRESS", ORDER_ROW, Set.of("/Order/ExpressShipping"), FILLED_ROW),
                "an empty boolean is not evaluated, so the comparison does not fire (unlike an empty confirm)");
        assertTrue(RuntimeLaws.fires(model, "NOT_EXPRESS", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/ExpressShipping", "true")),
                "a true boolean is evaluated and fires == True");
    }

    @Test
    void emptyStringIsNotEvaluated() {
        // an empty string field leaves the comparison not evaluated (and there are no empty strings:
        // [F] == "" is never satisfied — KERNEL-SEMANTICS §2)
        String model = RuntimeLaws.withRule(ORDER, "/Order", "CodeIsAbc", "../ProductCode", "CODE_ABC",
                "[ProductCode] == \"ABC\"");

        assertFalse(RuntimeLaws.fires(model, "CODE_ABC", ORDER_ROW, Set.of("/Order/ProductCode"), FILLED_ROW),
                "an empty string is not evaluated, so the comparison does not fire");
        assertTrue(RuntimeLaws.fires(model, "CODE_ABC", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/ProductCode", "ABC")),
                "a matching string value fires");
    }

    @Test
    void fieldFilledGuardSuppressesTheEmptyZeroError() {
        // the documented mitigation (§2): guard the comparison with FieldFilled
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyLowGuarded", "../Quantity", "QTY_LOW_G",
                "FieldFilled(Quantity) And [Quantity] < 100");

        assertFalse(RuntimeLaws.fires(model, "QTY_LOW_G", ORDER_ROW, FILLED_ROW),
                "the FieldFilled guard suppresses the empty-as-0 false positive even in a filled row");
        assertTrue(RuntimeLaws.fires(model, "QTY_LOW_G", ORDER_ROW, Map.of("/Order/Quantity", "50")),
                "a filled value of 50 is < 100, so the guarded rule fires");
    }
}
