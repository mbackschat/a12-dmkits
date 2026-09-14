package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — <b>the two places a model's declared BASE YEAR is not optional</b>.
 *
 * <p>Two unrelated checkers ask the same one-line question of the model. {@code
 * CheckKonstanteReferenzjahrImpl.check} refuses the {@code BaseYear} constant outright without one, and
 * {@code CheckDatumKonstruktImpl.checkJahresParam} refuses a {@code Date} construction that omits its year
 * parameter. The pairing is worth locking together because the same declaration switches both on, and
 * because a third place — the year-agreement walk of the range-overlap operators ({@link
 * DateRangeOperandListLawsTest}) — is disabled by the same declaration rather than enabled by it.
 *
 * <p>Own domain (an e-commerce order).
 */
class BaseYearRequirementLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_REL = "../Reference";

    private static final String NO_BASE_YEAR = "MVK_NO_BASE_YEAR";
    private static final String MISSING_YEAR = "MVK_MISSING_YEAR";

    private static final String ORDER = order(null);
    private static final String ORDER_WITH_BASE_YEAR = order(2024);

    private static String order(Integer baseYear) {
        var draft = DocumentModelDraft.named("order-base-year").locale("en_US");
        if (baseYear != null) {
            draft = draft.baseYear(baseYear);
        }
        return draft
                .rootGroup("Order", order -> order
                        .stringField("Reference", f -> { })
                        .numberField("Day", f -> f.min("0").maxLength(2))
                        .dateField("OrderedOn", f -> f.format("dd.MM.yyyy"))
                        .timeField("PickupTime", f -> f.format("HH:mm:ss")))
                .build().toJson();
    }

    private static String without(String condition) {
        return KernelLaws.verdictModel(ORDER, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
    }

    private static String with(String condition) {
        return KernelLaws.verdictModel(ORDER_WITH_BASE_YEAR, GROUP, ERROR_REL,
                "FieldFilled(Reference) And " + condition);
    }

    /** The constant is refused by its own checker before anything reads what it was compared against. */
    @Test
    void whereTheBaseYearConstantNeedsTheDeclaration() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("compared against a date, declared", with("[OrderedOn] > BaseYear"));
        actual.put("compared against a date, undeclared", without("[OrderedOn] > BaseYear"));
        actual.put("inside an extraction, declared", with("YearFromDate(BaseYear) > 2020"));
        actual.put("inside an extraction, undeclared", without("YearFromDate(BaseYear) > 2020"));
        actual.put("shifted, undeclared", without("[OrderedOn] > AddDays(BaseYear, 7)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("compared against a date, declared", KernelLaws.ADMITTED);
        expected.put("compared against a date, undeclared", NO_BASE_YEAR);
        expected.put("inside an extraction, declared", KernelLaws.ADMITTED);
        // The constant's own checker runs wherever the constant stands, so the carrier does not matter —
        // three carriers measured rather than one, because "the constant is refused" is a claim about the
        // constant and a single carrier could not tell it apart from that carrier's own gate.
        expected.put("inside an extraction, undeclared", NO_BASE_YEAR);
        expected.put("shifted, undeclared", NO_BASE_YEAR);
        assertEquals(expected, actual, "where the base-year constant needs the declaration");
    }

    /** The year-less construction, and the gate that answers ahead of it. */
    @Test
    void whereAYearLessDateConstructionNeedsTheDeclaration() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a complete construction, undeclared — the control",
                without("[OrderedOn] == Date(\"01\", \"02\", \"2024\")"));
        actual.put("a year-less construction, undeclared", without("[OrderedOn] == Date(\"01\", \"02\")"));
        actual.put("a year-less construction, declared", with("[OrderedOn] == Date(\"01\", \"02\")"));
        actual.put("a year-less construction naming a field, undeclared",
                without("[OrderedOn] == Date(Day, \"02\")"));
        actual.put("a year-less construction whose DAY is out of range, undeclared",
                without("[OrderedOn] == Date(\"32\", \"02\")"));
        actual.put("a two-part TIME construction, undeclared",
                without("[PickupTime] == Time(\"10\", \"30\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a complete construction, undeclared — the control", KernelLaws.ADMITTED);
        expected.put("a year-less construction, undeclared", MISSING_YEAR);
        expected.put("a year-less construction, declared", KernelLaws.ADMITTED);
        expected.put("a year-less construction naming a field, undeclared", MISSING_YEAR);
        // The day parameter is checked before the year one, so a construction wrong in both answers with the
        // day's class — the row that stops a reader asking the cheap arity question first.
        expected.put("a year-less construction whose DAY is out of range, undeclared",
                "MVK_WRONG_DATE_FORMAT_FOR_OP");
        // The time construction takes any prefix of its three slots and has no year to omit, so the same
        // two-argument shape is admitted there — the discriminator against "a short construction needs one".
        expected.put("a two-part TIME construction, undeclared", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "where a year-less date construction needs the declaration");
    }
}
