package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — <b>{@code NumberOfDifferentValues} types its list from the LEADING field, and each of
 * the four arms that leader can pick refuses the rest of the list with a DIFFERENT class</b>.
 *
 * <p>{@code CheckFeldListenAnzahlOpImpl.check}'s third case reads
 * {@code CheckEntityListenUtils.firstType} — the operand type of the first EXPANDED field — and branches on
 * it. So the same list is admitted under one leader and refused under another, and which code it draws is
 * decided before any member is looked at.
 *
 * <p>This is the same leader-decides-the-arm shape the extrema carry, with two differences that matter: the
 * string arm here folds CUSTOM and ENUM in with STRING rather than refusing them, and a leader none of the
 * three arms claims has a class of its own instead of falling into the numeric one.
 *
 * <p>Own domain (an insurance claim intake).
 */
class DistinctValueOperandKindLawsTest {

    private static final String GROUP = "/Claim";
    private static final String ERROR_REL = "../Reference";

    private static final String NOT_STRING_ENUM = "MVK_STRING_ENUM_AND_NON_STRING_ENUM";
    private static final String NOT_NUMBER = "MVK_NUMBER_AND_NON_NUMBER";
    private static final String NO_ARM = "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED";

    private static final String CLAIM = DocumentModelDraft.named("claim-distinct-values").locale("en_US")
            .typeDef("PolicyNumber", t -> t.string(s -> s.maxLength(20)))
            .rootGroup("Claim", claim -> claim
                    .stringField("Reference", f -> { })
                    .stringField("Notes", f -> { })
                    .stringField("FreeNote", f -> f.lineBreaks().noValueValidation())
                    .numberField("Amount", f -> f.fractionDigits(2))
                    .numberField("Excess", f -> f.fractionDigits(2))
                    .enumField("Severity", f -> f.value("LOW").value("HIGH").category("Band", "A", "B"))
                    .booleanField("Settled", f -> { })
                    .confirmField("Acknowledged", f -> { })
                    .dateField("ReportedOn", f -> f.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", f -> f.format("yyyy-MM-dd"))
                    .timeField("CallStart", f -> f.format("HH:mm:ss"))
                    .dateTimeField("LoggedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("CoverMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateRangeField("CoverWindow", f -> f.format("dd.MM.yyyy").separator("-"))
                    .customField("Adjuster", f -> f.type("AdjusterCode"))
                    .typedefField("PolicyRef", "PolicyNumber")
                    .group("Ledger", g -> g
                            .numberField("LedgerAmount", f -> f.fractionDigits(2))
                            .numberField("LedgerFee", f -> f.fractionDigits(2))))
            .build().toJson();

    private static String verdict(String list) {
        return KernelLaws.verdictModel(CLAIM, GROUP, ERROR_REL,
                "FieldFilled(Reference) And NumberOfDifferentValues(" + list + ") > 0");
    }

    /** Which arm the LEADER picks, with the rest of the list held at one string sibling throughout. */
    @Test
    void whichArmTheLeadingFieldPicks() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a STRING leader", verdict("Notes, Reference"));
        actual.put("an ENUM leader", verdict("Severity, Notes"));
        actual.put("a CUSTOM leader", verdict("Adjuster, Notes"));
        actual.put("a string TYPEDEF leader", verdict("PolicyRef, Notes"));
        actual.put("a NUMBER leader", verdict("Amount, Notes"));
        actual.put("a DATE leader", verdict("ReportedOn, Notes"));
        actual.put("a BOOLEAN leader", verdict("Settled, Notes"));
        actual.put("a CONFIRM leader", verdict("Acknowledged, Notes"));
        actual.put("a DATE-RANGE leader", verdict("CoverWindow, Notes"));
        actual.put("a GROUP leader, whose first expanded field is a number",
                verdict("Ledger, Notes"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Three declared kinds share the string arm, so all four of these rows are the same arm.
        expected.put("a STRING leader", KernelLaws.ADMITTED);
        expected.put("an ENUM leader", KernelLaws.ADMITTED);
        expected.put("a CUSTOM leader", KernelLaws.ADMITTED);
        expected.put("a string TYPEDEF leader", KernelLaws.ADMITTED);
        expected.put("a NUMBER leader", NOT_NUMBER);
        expected.put("a DATE leader", "MVK_DATE_AND_NONDATE");
        // No arm claims these three, and the else branch has a class of its own rather than falling into
        // the numeric one — which is the row a reader assembling arms from the code's name would miss.
        expected.put("a BOOLEAN leader", NO_ARM);
        expected.put("a CONFIRM leader", NO_ARM);
        expected.put("a DATE-RANGE leader", NO_ARM);
        // `firstType` reads the first EXPANDED field, so a group leader is its own first member.
        expected.put("a GROUP leader, whose first expanded field is a number", NOT_NUMBER);
        assertEquals(expected, actual, "which arm the leading field picks");
    }

    /** What the string arm admits behind a string-like leader. */
    @Test
    void whatTheStringArmAdmitsInTheRestOfTheList() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("another string", verdict("Notes, Reference"));
        actual.put("an enumeration", verdict("Notes, Severity"));
        actual.put("a custom declaration", verdict("Notes, Adjuster"));
        actual.put("a string typedef", verdict("Notes, PolicyRef"));
        actual.put("a number", verdict("Notes, Amount"));
        actual.put("a date", verdict("Notes, ReportedOn"));
        actual.put("a boolean", verdict("Notes, Settled"));
        actual.put("a confirm", verdict("Notes, Acknowledged"));
        actual.put("a time", verdict("Notes, CallStart"));
        actual.put("a date-time", verdict("Notes, LoggedAt"));
        actual.put("a date fragment", verdict("Notes, CoverMonth"));
        actual.put("a date range", verdict("Notes, CoverWindow"));
        actual.put("a string waiving value validation", verdict("Notes, FreeNote"));
        actual.put("a number BESIDE one waiving value validation", verdict("Notes, Amount, FreeNote"));
        actual.put("a number under an ENUM leader", verdict("Severity, Amount"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("another string", KernelLaws.ADMITTED);
        expected.put("an enumeration", KernelLaws.ADMITTED);
        expected.put("a custom declaration", KernelLaws.ADMITTED);
        expected.put("a string typedef", KernelLaws.ADMITTED);
        expected.put("a number", NOT_STRING_ENUM);
        expected.put("a date", NOT_STRING_ENUM);
        expected.put("a boolean", NOT_STRING_ENUM);
        expected.put("a confirm", NOT_STRING_ENUM);
        // The four temporal kinds are measured one by one: the member test is two POSITIVE predicates,
        // so what it refuses is their complement.
        expected.put("a time", NOT_STRING_ENUM);
        expected.put("a date-time", NOT_STRING_ENUM);
        expected.put("a date fragment", NOT_STRING_ENUM);
        expected.put("a date range", NOT_STRING_ENUM);
        // The value-validation gate sits BEHIND the kind walk on this arm, and on this arm alone.
        expected.put("a string waiving value validation", "MVK_INVALID_RAW_TYPE");
        // …and BEHIND it over the WHOLE list rather than per field, so a kind defect later in the list
        // still answers first.
        expected.put("a number BESIDE one waiving value validation", NOT_STRING_ENUM);
        expected.put("a number under an ENUM leader", NOT_STRING_ENUM);
        assertEquals(expected, actual, "what the string arm admits in the rest of the list");
    }

    /** The numeric arm, and the three gates that are asked before any arm is picked at all. */
    @Test
    void theNumericArmAndWhatOutrunsTheLeader() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("another number", verdict("Amount, Excess"));
        actual.put("a string under a number leader", verdict("Amount, Notes"));
        actual.put("an enumeration under a number leader", verdict("Amount, Severity"));
        actual.put("a boolean under a number leader", verdict("Amount, Settled"));
        actual.put("a lone unstarred field", verdict("Notes"));
        actual.put("a lone unstarred field of a kind NO arm claims", verdict("Settled"));
        actual.put("one field named twice", verdict("Notes, Notes"));
        actual.put("a category access", verdict("Severity -> Band, Notes"));
        actual.put("a category access beside a duplicate", verdict("Severity -> Band, Notes, Notes"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("another number", KernelLaws.ADMITTED);
        expected.put("a string under a number leader", NOT_NUMBER);
        expected.put("an enumeration under a number leader", NOT_NUMBER);
        expected.put("a boolean under a number leader", NOT_NUMBER);
        // The arity gate runs before the leader is read, so a lone field of an unclaimed kind answers here
        // rather than with the else branch's class.
        expected.put("a lone unstarred field", "MVK_PARAMSIZE_INVALIDN");
        expected.put("a lone unstarred field of a kind NO arm claims", "MVK_PARAMSIZE_INVALIDN");
        expected.put("one field named twice", "MVK_DUPLICATE_PARAM1");
        // `checkNoCategories` is the FIRST statement of the method, ahead even of building the reference
        // list — so it outruns the duplicate gate, which every other gate of this construct sits behind.
        expected.put("a category access", "MVK_CATEGORY_NOT_ALLOWED");
        expected.put("a category access beside a duplicate", "MVK_CATEGORY_NOT_ALLOWED");
        assertEquals(expected, actual, "the numeric arm, and what outruns the leader");
    }
}
