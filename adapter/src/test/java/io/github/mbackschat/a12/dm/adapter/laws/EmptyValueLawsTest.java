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
 *   <tr><td>date</td><td>{@code DifferenceInDays(OrderDate, DeliveryDate) < 0}</td><td>not evaluated</td></tr>
 *   <tr><td>string in a <b>concatenation</b></td><td>{@code [Full] != [First] + " " + [Last]}</td><td><b>evaluated</b> — empty concats to {@code ""}</td></tr>
 * </table>
 *
 * <p>The concatenation row is the subtle one (KERNEL-FINDINGS §2): an empty string in a <em>comparison</em>
 * is not evaluated, but an empty string <em>operand of a concatenation</em> concatenates as {@code ""}, so
 * the concatenated expression is a real value and the comparison <b>is</b> evaluated — the guard the other
 * string case doesn't need becomes necessary here.
 *
 * <p>The empty <b>date</b> case is exercised via {@code DifferenceInDays} (a date-to-date comparison),
 * sidestepping the date-literal syntax of KERNEL-FINDINGS §6.
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
    void lengthOfAnEmptyStringIsZero_evaluatedUnlikeAComparison() {
        // a direct comparison on an empty string is not evaluated (above) — but Length() coerces to
        // a NUMBER, and the kernel returns 0 for an empty string rather than the unknown state
        // (RT-SEMANTICS-LEDGER: BigDecimalUtils.laenge — the comment says exactly this). So the
        // string family has its own "empty-as-0" backdoor, the same trap as the number column.
        String model = RuntimeLaws.withRule(ORDER, "/Order", "CodeShort", "../ProductCode", "CODE_SHORT",
                "Length(ProductCode) < 5");

        assertTrue(RuntimeLaws.fires(model, "CODE_SHORT", ORDER_ROW, Set.of("/Order/ProductCode"), FILLED_ROW),
                "Length of an empty string is 0 (0 < 5), so the rule fires — unlike [F] == \"…\"");
        assertFalse(RuntimeLaws.fires(model, "CODE_SHORT", ORDER_ROW,
                Map.of("/Order/CustomerName", "Acme", "/Order/ProductCode", "ABCDEF")),
                "a 6-char value is not < 5, so the rule does not fire");
    }

    @Test
    void emptyDateIsNotEvaluated() {
        // an empty DATE is not evaluated (like string/boolean, unlike number=0). DifferenceInDays avoids
        // the §6 date-literal syntax: DifferenceInDays(a, b) = b - a (verified), so Delivery before Order
        // gives a negative diff.
        String model = RuntimeLaws.withRule(ORDER, "/Order", "DeliveryBeforeOrder", "../OrderDate",
                "DELIVERY_BEFORE", "DifferenceInDays(OrderDate, DeliveryDate) < 0");

        // both dates filled, DeliveryDate before OrderDate → diff < 0 → fires
        assertTrue(RuntimeLaws.fires(model, "DELIVERY_BEFORE", ORDER_ROW, Map.of("/Order/CustomerName", "Acme",
                "/Order/OrderDate", "2023-06-10", "/Order/DeliveryDate", "2023-06-05")),
                "with both dates filled the comparison evaluates and fires");
        // OrderDate empty (filled row) → the date comparison is NOT evaluated → does not fire
        assertFalse(RuntimeLaws.fires(model, "DELIVERY_BEFORE", ORDER_ROW, Set.of("/Order/OrderDate"),
                Map.of("/Order/CustomerName", "Acme", "/Order/DeliveryDate", "2023-06-05")),
                "an empty date is not evaluated, so the comparison does not fire (unlike an empty number → 0)");
    }

    @Test
    void emptyStringInAConcatenationIsEvaluated() {
        // The asymmetry trap (N6 2026-06-08): a plain string comparison is NOT evaluated when empty
        // (emptyStringIsNotEvaluated above), but an empty string OPERAND OF A CONCATENATION concatenates
        // as "", so [Full] != [First] + " " + [Last] with empty First/Last becomes [Full] != " " — a real
        // value the comparison evaluates and fires on. The mitigation is an AllFieldsFilled guard on the
        // concatenated operands (NOT the empty-as-not-evaluated free pass the direct comparison gets).
        String PERSON = RuntimeLaws.model("models/person.dm.json");
        Set<String> row = Set.of("/Person");
        Set<String> emptyNames = Set.of("/Person/FirstName", "/Person/LastName");

        String unguarded = RuntimeLaws.withRule(PERSON, "/Person", "FullNameMismatch", "../FullName",
                "FULLNAME_MISMATCH", "[FullName] != [FirstName] + \" \" + [LastName]");
        // FullName filled, FirstName/LastName empty → " " concat ≠ "Alice Smith" → FIRES (the trap)
        assertTrue(RuntimeLaws.fires(unguarded, "FULLNAME_MISMATCH", row, emptyNames,
                Map.of("/Person/FullName", "Alice Smith")),
                "an empty string in a concatenation is evaluated (concats to \"\"), so the comparison fires");
        // all three consistent → no violation
        assertFalse(RuntimeLaws.fires(unguarded, "FULLNAME_MISMATCH", row,
                Map.of("/Person/FullName", "Alice Smith", "/Person/FirstName", "Alice", "/Person/LastName", "Smith")),
                "consistent first+space+last does not fire");

        String guarded = RuntimeLaws.withRule(PERSON, "/Person", "FullNameMismatchGuarded", "../FullName",
                "FULLNAME_MISMATCH_G", "AllFieldsFilled(FirstName, LastName) And [FullName] != [FirstName] + \" \" + [LastName]");
        // the gold's guard suppresses the empty-operand false positive
        assertFalse(RuntimeLaws.fires(guarded, "FULLNAME_MISMATCH_G", row, emptyNames,
                Map.of("/Person/FullName", "Alice Smith")),
                "the AllFieldsFilled guard on the operands suppresses the empty-concatenation false positive");
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
