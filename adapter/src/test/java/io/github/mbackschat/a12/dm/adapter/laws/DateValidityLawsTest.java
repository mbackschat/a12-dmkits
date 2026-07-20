package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §6 — runtime behaviour of {@code Valid}/{@code Invalid} over a constructed date.
 * Their strong-Kleene truth projections are exact complements. The full verdict is not derivable from
 * truth alone: {@code Invalid} is OMISSION for an unspecified part and VALUE for a present-but-unreal
 * date, while malformed makes both predicates Unknown. Prefix {@code AllFieldsFilled(...)} to fire only
 * on a complete-but-impossible date.
 */
class DateValidityLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL and rendered to the canonical string fed to the kernel.
    private static final BoundModel M = BoundModel.ofJson(ORDER);
    // Date(BirthDay, BirthMonth, BirthYear) — construction from the three number fields.
    private static final DateExpr DATE = Dsl.date(
            M.num("/Order/BirthDay").asNumber(), M.num("/Order/BirthMonth").asNumber(), M.num("/Order/BirthYear").asNumber());

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    private static final String INV =
            RuntimeLaws.withRule(ORDER, "/Order", "R", "../BirthDay", "INV", render(Dsl.invalid(DATE)));
    private static final String VAL =
            RuntimeLaws.withRule(ORDER, "/Order", "R", "../BirthDay", "VAL", render(Dsl.valid(DATE)));

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
