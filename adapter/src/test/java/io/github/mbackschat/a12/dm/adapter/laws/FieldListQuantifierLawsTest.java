package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>the field-list "not …" quantifiers are not complements</b> (KERNEL-SEMANTICS §1,
 * "There is no generic negation operator"). Over a two-field list, the three negative quantifiers
 * disagree precisely at the edges — choosing the wrong one changes which documents the rule reports:
 *
 * <table><caption>fires? by number of filled fields (of 2)</caption>
 *   <tr><th>filled</th><th>{@code NotAllFieldsFilled}</th><th>{@code FieldsNotCollectivelyFilled}</th><th>{@code NotExactlyOneFieldFilled}</th></tr>
 *   <tr><td>0 (none)</td><td>✓</td><td><b>—</b></td><td>✓</td></tr>
 *   <tr><td>1</td><td>✓</td><td>✓</td><td>—</td></tr>
 *   <tr><td>2 (all)</td><td>—</td><td>—</td><td>✓</td></tr>
 * </table>
 *
 * <p>The all-empty column is the trap: {@code NotAllFieldsFilled} and {@code NotExactlyOneFieldFilled}
 * <em>include</em> the all-empty case, but {@code FieldsNotCollectivelyFilled} <em>excludes</em> it
 * (it needs at least one filled). (Verified with the /Order row itself filled by a sibling, so the
 * list fields' emptiness is what's under test, not the row's presence.)
 */
class FieldListQuantifierLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");
    private static final String LIST = "ProductCode, BackorderQuantity";

    private static final String NAF = RuntimeLaws.withRule(ORDER, "/Order", "R", "../ProductCode",
            "NAF", "NotAllFieldsFilled(" + LIST + ")");
    private static final String FNC = RuntimeLaws.withRule(ORDER, "/Order", "R", "../ProductCode",
            "FNC", "FieldsNotCollectivelyFilled(" + LIST + ")");
    private static final String NEO = RuntimeLaws.withRule(ORDER, "/Order", "R", "../ProductCode",
            "NEO", "NotExactlyOneFieldFilled(" + LIST + ")");

    @Test
    void allEmpty_notAllAndNotExactlyOneFire_butCollectivelyDoesNot() {
        Set<String> bothEmpty = Set.of("/Order/ProductCode", "/Order/BackorderQuantity");
        Map<String, String> row = Map.of("/Order/CustomerName", "Acme");
        assertEquals(true, RuntimeLaws.fires(NAF, "NAF", ORDER_ROW, bothEmpty, row), "NotAllFieldsFilled includes all-empty");
        assertEquals(false, RuntimeLaws.fires(FNC, "FNC", ORDER_ROW, bothEmpty, row),
                "FieldsNotCollectivelyFilled EXCLUDES all-empty (needs ≥1 filled) — the trap");
        assertEquals(true, RuntimeLaws.fires(NEO, "NEO", ORDER_ROW, bothEmpty, row), "NotExactlyOneFieldFilled: 0 ≠ 1");
    }

    @Test
    void exactlyOneFilled_notAllAndCollectivelyFire_butNotExactlyOneDoesNot() {
        Set<String> bqEmpty = Set.of("/Order/BackorderQuantity");
        Map<String, String> row = Map.of("/Order/CustomerName", "Acme", "/Order/ProductCode", "ABC");
        assertEquals(true, RuntimeLaws.fires(NAF, "NAF", ORDER_ROW, bqEmpty, row), "not all filled");
        assertEquals(true, RuntimeLaws.fires(FNC, "FNC", ORDER_ROW, bqEmpty, row), "≥1 but not all filled");
        assertEquals(false, RuntimeLaws.fires(NEO, "NEO", ORDER_ROW, bqEmpty, row), "exactly one filled → does not fire");
    }

    @Test
    void bothFilled_onlyNotExactlyOneFires() {
        Map<String, String> row = Map.of("/Order/CustomerName", "Acme",
                "/Order/ProductCode", "ABC", "/Order/BackorderQuantity", "3");
        assertEquals(false, RuntimeLaws.fires(NAF, "NAF", ORDER_ROW, row), "all filled");
        assertEquals(false, RuntimeLaws.fires(FNC, "FNC", ORDER_ROW, row), "all filled");
        assertEquals(true, RuntimeLaws.fires(NEO, "NEO", ORDER_ROW, row), "2 ≠ 1 → fires");
    }
}
