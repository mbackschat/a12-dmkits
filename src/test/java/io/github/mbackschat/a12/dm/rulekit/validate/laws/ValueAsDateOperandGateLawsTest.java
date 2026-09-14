package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — what {@code ValueAsDate} demands of the field it reads, and in what order.
 *
 * <p>{@code CheckFeldOpMitDatumSpecialImpl.check} runs four gates over one operand and each of them throws,
 * so the classes form a chain rather than a set. {@link ValueAsDatePlacementLawsTest} owns where the call may
 * stand and {@link ValueAsDateLocusLawsTest} owns which rule may read a repeating row; what no grid had put
 * to the kernel is which DECLARATIONS the precision gate takes, and which class answers when the operand
 * trips two gates at once.
 *
 * <p>The precision gate is {@code FieldUtils.isDatePartialKnown}, which is {@code instanceof IDateType} and
 * then {@code partlyKnownDate() != BEKANNT}. Both halves matter and only one of them is visible from the
 * operator's name: A12's ADO layer gives {@code Time}, {@code DateTime} and {@code DateFragment} the same
 * {@code IDateType} interface a plain date carries, so those three reach the second half and are refused by
 * the precision rather than by the kind — which is why the whole temporal family is measured here and not
 * only the two declarations an author would guess at.
 *
 * <p>Own domain (an e-commerce returns desk).
 */
class ValueAsDateOperandGateLawsTest {

    private static final String RETURNS = DocumentModelDraft.named("returns-valueasdate").locale("en_US")
            .rootGroup("Returns", r -> r
                    .stringField("Reference", f -> { })
                    .dateField("Limit", f -> f.format("yyyy-MM-dd"))
                    .dateField("ApproxDay", f -> f.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .dateField("ApproxMonth",
                            f -> f.format("dd.MM.yyyy").datePrecision(DatePrecision.MONTH_OPTIONAL))
                    .dateField("ApproxYear", f -> f.format("yyyyMMdd").datePrecision(DatePrecision.YEAR_OPTIONAL))
                    .dateField("ExactDate", f -> f.format("yyyy-MM-dd"))
                    .dateFragmentField("Season", f -> f.fragmentFormat("MM-dd"))
                    .timeField("CutOff", f -> f.format("HH:mm:ss"))
                    .dateTimeField("LoggedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateRangeField("Window", f -> f.format("dd.MM.yyyy").separator("-"))
                    .stringField("Note", f -> { })
                    .numberField("ItemCount", f -> f.fractionDigits(0))
                    .enumField("Grade", e -> e.value("RESALE").value("SCRAP")
                            .category("Band", "LOW", "HIGH"))
                    .booleanField("Expedited", f -> { })
                    .confirmField("Acknowledged", f -> { })
                    .customField("Handler", f -> f.type("HandlerCode"))
                    .group("Desk", d -> d.stringField("DeskCode", f -> { }))
                    .group("Lines", g -> g.repeatable(10)
                            .stringField("LineCode", f -> { })
                            .dateField("LineApprox",
                                    f -> f.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL))
                            .enumField("LineGrade", e -> e.value("KEEP").value("SEND")
                                    .category("Band", "LOW", "HIGH"))))
            .build().toJson();

    private static final String WRONG_KIND = "MVK_INVALID_DATE_TYPE_FOR_OP";

    /** The call read from the form's own rule, which is the locus that iterates nothing. */
    private static String fromTheForm(String operand) {
        return KernelLaws.verdictModel(RETURNS, "/Returns", "../Reference",
                "FieldFilled(Reference) And ValueAsDate(" + operand + ", FirstDay) > [Limit]");
    }

    /** The same call read from a rule declared INSIDE the repeating group, which binds that repetition. */
    private static String fromARow(String operand) {
        return KernelLaws.verdictModel(RETURNS, "/Returns/Lines", "../LineCode",
                "FieldFilled(LineCode) And ValueAsDate(" + operand + ", FirstDay) > [/Returns/Limit]");
    }

    @Test
    void whichDeclarationsValueAsDateReads() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a date declaring DAY_OPTIONAL — the control", fromTheForm("ApproxDay"));
        actual.put("a date declaring MONTH_OPTIONAL", fromTheForm("ApproxMonth"));
        actual.put("a date declaring YEAR_OPTIONAL", fromTheForm("ApproxYear"));
        actual.put("the control read for its LAST day", KernelLaws.verdictModel(RETURNS, "/Returns",
                "../Reference", "FieldFilled(Reference) And ValueAsDate(ApproxDay, LastDay) > [Limit]"));
        actual.put("a date at the FULL default", fromTheForm("ExactDate"));
        actual.put("a date fragment", fromTheForm("Season"));
        actual.put("a time field", fromTheForm("CutOff"));
        actual.put("a datetime field", fromTheForm("LoggedAt"));
        actual.put("a date-range field", fromTheForm("Window"));
        actual.put("a string field", fromTheForm("Note"));
        actual.put("a number field", fromTheForm("ItemCount"));
        actual.put("an enumeration field", fromTheForm("Grade"));
        actual.put("a boolean field", fromTheForm("Expedited"));
        actual.put("a confirm field", fromTheForm("Acknowledged"));
        actual.put("a custom field", fromTheForm("Handler"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a date declaring DAY_OPTIONAL — the control", KernelLaws.ADMITTED);
        expected.put("a date declaring MONTH_OPTIONAL", KernelLaws.ADMITTED);
        expected.put("a date declaring YEAR_OPTIONAL", KernelLaws.ADMITTED);
        expected.put("the control read for its LAST day", KernelLaws.ADMITTED);
        // All three precisions pass one gate, so the operand's own class is the same for every declaration
        // that fails it — the operator has no second class distinguishing "not a date" from "a complete one".
        expected.put("a date at the FULL default", WRONG_KIND);
        expected.put("a date fragment", WRONG_KIND);
        expected.put("a time field", WRONG_KIND);
        expected.put("a datetime field", WRONG_KIND);
        expected.put("a date-range field", WRONG_KIND);
        expected.put("a string field", WRONG_KIND);
        expected.put("a number field", WRONG_KIND);
        expected.put("an enumeration field", WRONG_KIND);
        expected.put("a boolean field", WRONG_KIND);
        expected.put("a confirm field", WRONG_KIND);
        expected.put("a custom field", WRONG_KIND);
        assertEquals(expected, actual, "which declarations ValueAsDate reads");
    }

    /**
     * Which gate answers when the operand trips more than one.
     *
     * <p>Every pairing that can be written at all is here: a group cannot carry a category, and a category
     * access can only land on an enumeration, whose precision is wrong by construction — so the category row
     * IS the category-against-precision pairing rather than needing one of its own.
     */
    @Test
    void theOrderAmongTheOperandGates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a non-repeating GROUP", fromTheForm("Desk"));
        actual.put("a repeating group carrying its star", fromTheForm("Lines*"));
        actual.put("a STAR over a field whose precision is right", fromTheForm("Lines*/LineApprox"));
        actual.put("a STAR over a field whose precision is wrong", fromTheForm("Lines*/LineCode"));
        actual.put("a STAR over a category access", fromTheForm("Lines*/LineGrade -> Band"));
        actual.put("a CATEGORY access", fromTheForm("Grade -> Band"));
        actual.put("an unstarred read into the repetition, precision right", fromTheForm("Lines/LineApprox"));
        actual.put("an unstarred read into the repetition, precision wrong", fromTheForm("Lines/LineCode"));
        actual.put("the ROOT group", fromTheForm("/Returns"));
        actual.put("a name nothing declares", fromTheForm("Nowhere"));
        actual.put("the row's own field read from the row's rule", fromARow("LineApprox"));
        actual.put("the row's wrong-precision field read from the row's rule", fromARow("LineCode"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a non-repeating GROUP", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a repeating group carrying its star", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a STAR over a field whose precision is right", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a STAR over a field whose precision is wrong", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a STAR over a category access", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a CATEGORY access", "MVK_CATEGORY_NOT_ALLOWED");
        // The reference reader sits OUTSIDE this method and pre-empts all four of its classes, so a checker
        // reading stored models may say nothing at all about an unstarred read into a repetition — not even
        // that the field it reaches could never pass the precision gate.
        expected.put("an unstarred read into the repetition, precision right", "MVK_NO_WILDCARD");
        expected.put("an unstarred read into the repetition, precision wrong", "MVK_NO_WILDCARD");
        // Predicted `MVK_ROOT_GROUP_REFERENCED` and measured the plain group class: the root class is raised
        // by the operators that ACCEPT a group and exclude the root one, so a method with no group arm at
        // all never reaches it. The name gate is the one that does outrun this chain.
        expected.put("the ROOT group", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a name nothing declares", "MVK_INVALID_ENTITY");
        // …and the same two reads from the row's OWN rule reach the gates, which is what makes the two rows
        // above a pre-emption rather than a property of the operand's spelling.
        expected.put("the row's own field read from the row's rule", KernelLaws.ADMITTED);
        expected.put("the row's wrong-precision field read from the row's rule", WRONG_KIND);
        assertEquals(expected, actual, "the order among the operand gates");
    }
}
