package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * What a Confirm or Boolean declaration may be compared to, and with which operator.
 *
 * <p>{@code CheckVergleichsBedingungImpl.checkYesAndYesNoOperations} runs after the operator gates
 * ({@code KF382}) and asks a different question: not whether the operator suits the sides, but whether a
 * side that is one of A12's two boolean KINDS has a partner it can mean anything against. The two kinds are
 * not interchangeable and each answers with a class of its own.
 *
 * <p><b>The asymmetry is the finding.</b> A Confirm declaration carries a yes-representation and no
 * no-representation, so it may stand against {@code True} and another Confirm and nothing else — not
 * {@code False}, and not a Boolean. A Boolean carries both, so it stands against either constant and another
 * Boolean. And two Confirms may only be compared for INEQUALITY, because equality between two things that
 * can each only be true decides nothing.
 *
 * <p>Every refused row is raw condition text: the typed handles are kind-typed
 * ({@code ConfirmRef} has no {@code isFalse()}, {@code BoolRef} compares only to literals), so these shapes
 * reach a checker only as stored DM-JSON.
 */
class BooleanComparisonAgreementLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.dateField("PlacedOn", d -> d.format("yyyy-MM-dd"));
                g.enumField("Channel", e -> e.value("WEB").value("STORE"));
                g.field("Expedited", FieldKind.BOOLEAN);
                g.field("GiftWrapped", FieldKind.BOOLEAN);
                g.field("TermsAccepted", FieldKind.CONFIRM);
                g.field("NewsletterOptIn", FieldKind.CONFIRM);
            }).build().toJson();

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    /** What a CONFIRM declaration may stand against. */
    @Test
    void whatAConfirmDeclarationMayBeComparedTo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("against True", verdict("[TermsAccepted] == True"));
        actual.put("against False", verdict("[TermsAccepted] == False"));
        actual.put("against another Confirm, for INEQUALITY",
                verdict("[TermsAccepted] != [NewsletterOptIn]"));
        actual.put("against another Confirm, for EQUALITY",
                verdict("[TermsAccepted] == [NewsletterOptIn]"));
        actual.put("against a Boolean", verdict("[TermsAccepted] == [Expedited]"));
        actual.put("against a text constant", verdict("[TermsAccepted] == \"y\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("against True", KernelLaws.ADMITTED);
        expected.put("against False", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("against another Confirm, for INEQUALITY", KernelLaws.ADMITTED);
        expected.put("against another Confirm, for EQUALITY", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("against a Boolean", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("against a text constant", "MVK_INVALID_COMPARE_TO_YES");
        assertEquals(expected, actual, "what a Confirm declaration may be compared to");
    }

    /** What a BOOLEAN declaration may stand against — a wider set, and a different class when it cannot. */
    @Test
    void whatABooleanDeclarationMayBeComparedTo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("against True", verdict("[Expedited] == True"));
        actual.put("against False", verdict("[Expedited] == False"));
        actual.put("against another Boolean, for EQUALITY", verdict("[Expedited] == [GiftWrapped]"));
        actual.put("against another Boolean, for INEQUALITY", verdict("[Expedited] != [GiftWrapped]"));
        actual.put("against a text constant", verdict("[Expedited] == \"y\""));
        actual.put("against a number declaration", verdict("[Expedited] == [Quantity]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("against True", KernelLaws.ADMITTED);
        expected.put("against False", KernelLaws.ADMITTED);
        expected.put("against another Boolean, for EQUALITY", KernelLaws.ADMITTED);
        expected.put("against another Boolean, for INEQUALITY", KernelLaws.ADMITTED);
        expected.put("against a text constant", "MVK_INVALID_COMPARE_TO_YESNO");
        expected.put("against a number declaration", "MVK_INVALID_COMPARE_TO_YESNO");
        assertEquals(expected, actual, "what a Boolean declaration may be compared to");
    }

    /**
     * Which half of the Confirm arm the EQUALITY OPERATOR moves, and which half it leaves alone.
     *
     * <p>[KF270] measured the Confirm arm under {@code ==} alone and read it as "refuses every field on the
     * right". The operator is a second axis inside that arm: {@code !=} admits a Confirm partner. These rows
     * separate the two readings it could otherwise carry — that {@code !=} lifts the whole arm, and that two
     * DISTINCT Confirms are what {@code ==} refuses rather than the pairing itself.
     */
    @Test
    void theOperatorIsASecondAxisInsideTheConfirmArm() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a Confirm against ITSELF, for equality", verdict("[TermsAccepted] == [TermsAccepted]"));
        actual.put("a Confirm against ITSELF, for inequality", verdict("[TermsAccepted] != [TermsAccepted]"));
        actual.put("a Confirm against a Boolean, for inequality", verdict("[TermsAccepted] != [GiftWrapped]"));
        actual.put("a Confirm against a text constant, for inequality",
                verdict("[TermsAccepted] != \"y\""));
        actual.put("a Confirm against False, for inequality", verdict("[TermsAccepted] != False"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a Confirm against ITSELF, for equality", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("a Confirm against ITSELF, for inequality", KernelLaws.ADMITTED);
        expected.put("a Confirm against a Boolean, for inequality", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("a Confirm against a text constant, for inequality", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("a Confirm against False, for inequality", "MVK_INVALID_COMPARE_TO_YES");
        assertEquals(expected, actual, "the operator is a second axis inside the Confirm arm");
    }

    /**
     * The same two classes with the sides SWAPPED, and on carriers the first two grids never put there.
     *
     * <p>Every row above writes the boolean declaration on the left, which would make a left-biased reading
     * indistinguishable from the symmetric one A12 actually applies; and every partner above is a text or
     * number carrier, which leaves the date and enumeration ones untested.
     */
    @Test
    void theGatesAreSymmetricAndHoldOnEveryPartnerCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("True on the LEFT of a Confirm", verdict("True == [TermsAccepted]"));
        actual.put("False on the LEFT of a Boolean", verdict("False == [Expedited]"));
        actual.put("a text constant on the LEFT of a Confirm", verdict("\"y\" == [TermsAccepted]"));
        actual.put("a Boolean on the LEFT of a Confirm", verdict("[Expedited] == [TermsAccepted]"));
        actual.put("a Confirm against a DATE declaration", verdict("[TermsAccepted] == [PlacedOn]"));
        actual.put("a Confirm against an ENUMERATION declaration", verdict("[TermsAccepted] == [Channel]"));
        actual.put("a Boolean against a DATE declaration", verdict("[Expedited] == [PlacedOn]"));
        actual.put("a Boolean against an ENUMERATION declaration", verdict("[Expedited] == [Channel]"));
        actual.put("a Confirm against Today", verdict("[TermsAccepted] == Today"));
        actual.put("two written-out constants", verdict("True == False"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("True on the LEFT of a Confirm", KernelLaws.ADMITTED);
        expected.put("False on the LEFT of a Boolean", KernelLaws.ADMITTED);
        expected.put("a text constant on the LEFT of a Confirm", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("a Boolean on the LEFT of a Confirm", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("a Confirm against a DATE declaration", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("a Confirm against an ENUMERATION declaration", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("a Boolean against a DATE declaration", "MVK_INVALID_COMPARE_TO_YESNO");
        expected.put("a Boolean against an ENUMERATION declaration", "MVK_INVALID_COMPARE_TO_YESNO");
        expected.put("a Confirm against Today", "MVK_INVALID_COMPARE_TO_YES");
        expected.put("two written-out constants", "MVK_INVALID_COMPARE_TO_CONST");
        assertEquals(expected, actual, "the gates are symmetric and hold on every partner carrier");
    }

    /** The operator gates of {@code KF382} run first, so an ordering comparison never reaches these. */
    @Test
    void theOperatorGatesAreAskedFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a Confirm ordered against a Boolean", verdict("[TermsAccepted] < [Expedited]"));
        actual.put("a Boolean ordered against a number", verdict("[Expedited] < [Quantity]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a Confirm ordered against a Boolean", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("a Boolean ordered against a number", "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(expected, actual, "the operator gates are asked first");
    }
}
