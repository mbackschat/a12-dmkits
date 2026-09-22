package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * Behavioral law — <b>calendar-aware date shifting around the leap day</b> (RT-SEMANTICS-LEDGER:
 * {@code BedingungsOperatorHelper.addiereJahre/addiereMonate}; KERNEL-FINDINGS §6 supplement).
 * {@code AddYears}/{@code AddMonths} are not plain {@code Calendar.add}:
 *
 * <ul>
 *   <li>{@code AddYears} corrects the Feb-28 boundary: <b>28.02.1999 + 1 year = 29.02.2000</b> (a
 *       Feb-28 in a non-leap year shifted into a leap year lands on the leap day, not Feb-28);</li>
 *   <li>{@code AddMonths} clamps an end-of-month overflow: <b>31.01.2008 + 1 month = 29.02.2008</b>
 *       (no carry into March).</li>
 * </ul>
 *
 * <p>Field-vs-field comparisons sidestep the §6 date-literal format question.
 */
class DateArithmeticLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL and rendered to the canonical string fed to the kernel.
    private final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return RuntimeLaws.render(c);
    }

    @Test
    void addYearsFromFeb28OfANonLeapYearLandsOnTheLeapDay() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "YearApart", "../OrderDate", "YEAR_APART",
                render(Dsl.addYears(m.date("/Order/OrderDate"), 1L).eq(m.date("/Order/DeliveryDate"))));

        assertTrue(RuntimeLaws.fires(model, "YEAR_APART", ORDER_ROW,
                Map.of("/Order/OrderDate", "1999-02-28", "/Order/DeliveryDate", "2000-02-29")),
                "28.02.1999 + 1 year = 29.02.2000 — the kernel corrects onto the leap day");
        assertFalse(RuntimeLaws.fires(model, "YEAR_APART", ORDER_ROW,
                Map.of("/Order/OrderDate", "1999-02-28", "/Order/DeliveryDate", "2000-02-28")),
                "…and therefore NOT 28.02.2000, which plain Calendar.add would produce");
    }

    @Test
    void addMonthsClampsAnEndOfMonthOverflow() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "MonthApart", "../OrderDate", "MONTH_APART",
                render(Dsl.addMonths(m.date("/Order/OrderDate"), 1L).eq(m.date("/Order/DeliveryDate"))));

        assertTrue(RuntimeLaws.fires(model, "MONTH_APART", ORDER_ROW,
                Map.of("/Order/OrderDate", "2008-01-31", "/Order/DeliveryDate", "2008-02-29")),
                "31.01.2008 + 1 month = 29.02.2008 — clamped to the shorter month's last day");
        assertFalse(RuntimeLaws.fires(model, "MONTH_APART", ORDER_ROW,
                Map.of("/Order/OrderDate", "2008-01-31", "/Order/DeliveryDate", "2008-03-02")),
                "…there is no carry into March");
    }
}
