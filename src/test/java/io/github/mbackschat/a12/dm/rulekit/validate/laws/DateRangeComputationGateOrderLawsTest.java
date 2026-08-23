package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS §6/§11 — a DATE_RANGE computation passes <b>two</b> gates, and they are <b>ordered</b>:
 * the operand list is checked for one common DECLARATION PROFILE first, and only then is the result checked
 * against the TARGET's date-COMPONENT SET (SPEC-2026-08-23-03).
 *
 * <p><b>The two gates read different things, which is why the order is observable.</b> The list gate is
 * lexical — {@code dd.MM.yyyy}+{@code -} and {@code yyyy-MM-dd}+{@code /} are two profiles of the one
 * component set {@code {YEAR, MONTH, DAY}}, and a list mixing them is refused
 * {@code MVK_VARYING_TYPES_NOT_ALLOWED} even though every one of its members is individually assignable to
 * the target. The target gate is not lexical: a uniform dotted list computes into an ISO target.
 *
 * <p>{@link #theListGateDecidesBeforeTheTargetIsConsulted} is the row that makes "ordered" a measurement
 * rather than a description. Its three-source list plants the odd member so that it matches the target
 * EXACTLY; an implementation free to report either refusal first would be entitled to answer
 * {@code MVK_INVALID_COMPARE_TO_DATE_RANGE} there, and would hand a consumer the wrong repair — change the
 * target, rather than change the odd source.
 *
 * <p><b>This corrects prose this project published.</b> KERNEL-FINDINGS' 2026-08-19 entry read "sources and
 * target must share one date-COMPONENT SET — not one lexical profile", which is true of the target gate and
 * false of the list gate. The contradicting measurement was already in this repository:
 * [KF71]'s 2026-08-15 correction had established for the temporal family that the {@code FirstFilledValue}
 * operand gate reads the DECLARED FORMAT, and closed DATE_RANGE into that claim by name. The two live statements disagreed until this table put
 * them side by side.
 *
 * <p>Own domain (e-commerce promotion windows).
 */
class DateRangeComputationGateOrderLawsTest {

    private static final String VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED";
    private static final String TARGET_MISMATCH = "MVK_INVALID_COMPARE_TO_DATE_RANGE";

    /**
     * Both admitted spellings of three component sets, plus the two exact profiles — the pairs are KF81's
     * enumerated declaration grid, and the point of declaring both spellings of each set is that a checker
     * enumerating one per set cannot express the other target at all.
     */
    private static final String PROMO = DocumentModelDraft.named("promo-windows").locale("en_US")
            .rootGroup("Promo", p -> p
                    .stringField("Campaign", s -> { })
                    .dateRangeField("IsoRunA", r -> r.format("yyyy-MM-dd").separator("/"))
                    .dateRangeField("IsoRunB", r -> r.format("yyyy-MM-dd").separator("/"))
                    .dateRangeField("EuRunA", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("EuRunB", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("IsoTarget", r -> r.format("yyyy-MM-dd").separator("/"))
                    .dateRangeField("MonthSlashA", r -> r.format("MM").separator("/"))
                    .dateRangeField("MonthSlashB", r -> r.format("MM").separator("/"))
                    .dateRangeField("MonthHalvedA", r -> r.format("MM").separator(""))
                    .dateRangeField("MonthHalvedB", r -> r.format("MM").separator(""))
                    .dateRangeField("MonthSlashTarget", r -> r.format("MM").separator("/"))
                    .dateRangeField("MonthHalvedTarget", r -> r.format("MM").separator(""))
                    .dateRangeField("DayMonthSlashA", r -> r.format("MM-dd").separator("/"))
                    .dateRangeField("DayMonthSlashB", r -> r.format("MM-dd").separator("/"))
                    .dateRangeField("DottedDayMonthTarget", r -> r.format("dd.MM").separator("-"))
                    .dateFragmentField("FromMonth", f -> f.fragmentFormat("MM"))
                    .dateFragmentField("ToMonth", f -> f.fragmentFormat("MM"))
                    .dateFragmentField("FromMonthDay", f -> f.fragmentFormat("MM-dd"))
                    .dateFragmentField("ToMonthDay", f -> f.fragmentFormat("MM-dd")))
            .build().toJson();

    private static final io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel M =
            io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(PROMO);

    private static final String ROOT = "/Promo/";

    /** The kernel's verdict for {@code FirstFilledValue(sources…)} assigned to {@code target}. */
    private static String listInto(String target, String first, String second, String... rest) {
        var head = M.dateRange(ROOT + first).field();
        var next = M.dateRange(ROOT + second).field();
        var tail = new io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand[rest.length];
        for (int i = 0; i < rest.length; i++) {
            tail[i] = M.dateRange(ROOT + rest[i]).field();
        }
        return verdict(target, Dsl.firstFilledDateRange(head, next, tail).expression());
    }

    /** The kernel's verdict for a {@code DateRange(start, end)} construction assigned to {@code target}. */
    private static String constructionInto(String target, String start, String end) {
        return verdict(target,
                Dsl.dateRange(M.dateFragment(ROOT + start), M.dateFragment(ROOT + end)).expression());
    }

    private static String verdict(String target, Calculation operation) {
        Computation computation = M.computation(M.dateRange(ROOT + target))
                .name("WindowAssign")
                .calc(operation)
                .message(Dsl.en("Promotion window assignment probe."))
                .build();
        return KernelLaws.computationVerdict(PROMO, computation);
    }

    /**
     * The ordering row. The odd source matches the target byte for byte, so a target-first checker admits the
     * list; the list gate reports instead, and it reports the same class as the plain two-source cross.
     */
    @Test
    void theListGateDecidesBeforeTheTargetIsConsulted() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("uniform dotted list into an ISO target — control", listInto("IsoTarget", "EuRunA", "EuRunB"));
        actual.put("uniform ISO list into an ISO target — control", listInto("IsoTarget", "IsoRunA", "IsoRunB"));
        actual.put("three sources, the odd one matching the target exactly",
                listInto("IsoTarget", "EuRunA", "EuRunB", "IsoRunA"));
        actual.put("two sources, one of each spelling", listInto("IsoTarget", "EuRunA", "IsoRunA"));
        actual.put("mixed COMPONENT SETS, the first matching the target",
                listInto("MonthSlashTarget", "MonthSlashA", "EuRunA"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("uniform dotted list into an ISO target — control", KernelLaws.ADMITTED);
        expected.put("uniform ISO list into an ISO target — control", KernelLaws.ADMITTED);
        expected.put("three sources, the odd one matching the target exactly", VARYING);
        expected.put("two sources, one of each spelling", VARYING);
        expected.put("mixed COMPONENT SETS, the first matching the target", VARYING);
        assertEquals(expected, actual,
                "the operand list is decided before the target, so a target-matching odd source does not admit it");
    }

    /** …and with a UNIFORM list the target gate is the one that reports, on its own class. */
    @Test
    void aUniformListIsThenDecidedByTheTargetsComponentSet() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("month list into a full-date target", listInto("IsoTarget", "MonthSlashA", "MonthSlashB"));
        actual.put("full-date list into a month target", listInto("MonthSlashTarget", "EuRunA", "EuRunB"));
        actual.put("month-day list into a month target",
                listInto("MonthSlashTarget", "DayMonthSlashA", "DayMonthSlashB"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("month list into a full-date target", TARGET_MISMATCH);
        expected.put("full-date list into a month target", TARGET_MISMATCH);
        expected.put("month-day list into a month target", TARGET_MISMATCH);
        assertEquals(expected, actual, "a uniform list reaches the target gate, which reads the component set");
    }

    /**
     * Both spellings of each component set participate on either side. The yearless sets are the ones a
     * one-spelling-per-set implementation cannot express: {@code MM}+{@code ""} and {@code dd.MM}+{@code -}
     * are legal declarations with no lexical sibling in the target position of the earlier illustration.
     */
    @Test
    void bothSpellingsOfAComponentSetCrossOnTheListShape() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("empty-separator month list into a slash month target",
                listInto("MonthSlashTarget", "MonthHalvedA", "MonthHalvedB"));
        actual.put("slash month list into an empty-separator month target",
                listInto("MonthHalvedTarget", "MonthSlashA", "MonthSlashB"));
        actual.put("slash month-day list into a dotted day-month target",
                listInto("DottedDayMonthTarget", "DayMonthSlashA", "DayMonthSlashB"));
        actual.put("same-spelling month list — control", listInto("MonthSlashTarget", "MonthSlashA", "MonthSlashB"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("empty-separator month list into a slash month target", KernelLaws.ADMITTED);
        expected.put("slash month list into an empty-separator month target", KernelLaws.ADMITTED);
        expected.put("slash month-day list into a dotted day-month target", KernelLaws.ADMITTED);
        expected.put("same-spelling month list — control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the target gate reads the component set, so either spelling assigns");
    }

    /** The construction shape crosses the same way, and is refused on the same component-set gate. */
    @Test
    void bothSpellingsOfAComponentSetCrossOnTheConstructionShape() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("month construction into an empty-separator month target",
                constructionInto("MonthHalvedTarget", "FromMonth", "ToMonth"));
        actual.put("month construction into a slash month target",
                constructionInto("MonthSlashTarget", "FromMonth", "ToMonth"));
        actual.put("month-day construction into a dotted day-month target",
                constructionInto("DottedDayMonthTarget", "FromMonthDay", "ToMonthDay"));
        actual.put("month-day construction into a month target",
                constructionInto("MonthSlashTarget", "FromMonthDay", "ToMonthDay"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("month construction into an empty-separator month target", KernelLaws.ADMITTED);
        expected.put("month construction into a slash month target", KernelLaws.ADMITTED);
        expected.put("month-day construction into a dotted day-month target", KernelLaws.ADMITTED);
        expected.put("month-day construction into a month target", TARGET_MISMATCH);
        assertEquals(expected, actual,
                "a construction reaches both spellings of its own component set and no other set");
    }
}
