package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §6 — runtime behaviour of {@code Valid}/{@code Invalid} over a constructed date
 * (BA "Valid or invalid dates"). They are <b>not</b> logical complements: {@code Invalid} fires when a
 * referenced part is <em>unspecified</em> OR the parts don't form a real date; {@code Valid} fires only
 * when all parts are specified AND the date is real. So an unspecified part fires {@code Invalid} but not
 * {@code Valid} — prefix {@code AllFieldsFilled(...)} to fire only on a complete-but-impossible date.
 */
class DateValidityLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ROW = Set.of("/Order");
    private static final String DATE = "Date(BirthDay, BirthMonth, BirthYear)";

    private static final String INV =
            RuntimeLaws.withRule(ORDER, "/Order", "R", "../BirthDay", "INV", "Invalid(" + DATE + ")");
    private static final String VAL =
            RuntimeLaws.withRule(ORDER, "/Order", "R", "../BirthDay", "VAL", "Valid(" + DATE + ")");

    private static final Map<String, String> ONLY_DAY = Map.of("/Order/BirthDay", "15");
    private static final Map<String, String> IMPOSSIBLE =
            Map.of("/Order/BirthDay", "31", "/Order/BirthMonth", "2", "/Order/BirthYear", "2000");
    private static final Map<String, String> REAL =
            Map.of("/Order/BirthDay", "15", "/Order/BirthMonth", "6", "/Order/BirthYear", "2000");

    @Test
    void invalidFiresWhenAReferencedPartIsUnspecified() {
        assertTrue(RuntimeLaws.fires(INV, "INV", ROW, ONLY_DAY), "Invalid fires on an unspecified part");
    }

    @Test
    void invalidFiresOnAnImpossibleDate() {
        assertTrue(RuntimeLaws.fires(INV, "INV", ROW, IMPOSSIBLE), "Invalid fires on 31 February");
    }

    @Test
    void invalidDoesNotFireOnACompleteRealDate() {
        assertFalse(RuntimeLaws.fires(INV, "INV", ROW, REAL), "a real date is not Invalid");
    }

    @Test
    void validRequiresAllPartsFilled_soAnUnspecifiedPartDoesNotFireValid() {
        assertFalse(RuntimeLaws.fires(VAL, "VAL", ROW, ONLY_DAY), "Valid needs all parts specified");
        assertTrue(RuntimeLaws.fires(VAL, "VAL", ROW, REAL), "a complete real date is Valid");
    }
}
