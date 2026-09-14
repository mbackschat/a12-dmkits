package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * What a TEXT or ENUMERATION declaration may be compared to, and which written-out values an enumeration
 * admits.
 *
 * <p>{@code CheckVergleichsBedingungImpl.checkStringOrEnumType} runs twice, once per side, after the date,
 * date-range and boolean arms. It asks two separable questions, and the separation is the finding: a KIND
 * question that has nothing to do with the enumeration's content, and — only where the partner is a written-out
 * value — a DOMAIN question that never reaches the display-value machinery beside it.
 *
 * <p>The display-value half ({@code MVK_INVALID_COMPARE_ENUM_TO_STRING}, {@code MVK_INVALID_COMPARE_ENUM_TO_ENUM})
 * is measured here as the neighbour it is, not reported by the checker family this grid backs.
 */
class StringEnumComparisonAgreementLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.stringField("GiftNote", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.enumField("Channel", e -> e.value("WEB").value("STORE"));
                g.enumField("Carrier", e -> e.value("ROAD").value("AIR"));
                g.enumField("Tier", e -> e
                        .value("BASIC", l -> l.label("en_US", "Basic plan"))
                        .value("PLUS", l -> l.label("en_US", "Plus plan")));
                g.dateField("PlacedOn", d -> d.format("yyyy-MM-dd"));
                g.field("Expedited", FieldKind.BOOLEAN);
            }).build().toJson();

    private static final String WRONG_KIND = "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING";

    private static final String OUTSIDE_DOMAIN = "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON";

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    /** The KIND question: a text or enumeration side needs a partner that is one or the other. */
    @Test
    void whatATextOrEnumerationDeclarationMayStandAgainst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two text declarations — the control", verdict("[GiftNote] == [Reference]"));
        actual.put("a text declaration against a number declaration", verdict("[GiftNote] == [Quantity]"));
        actual.put("a number declaration against a text one", verdict("[Quantity] == [GiftNote]"));
        actual.put("a text declaration against a written-out number", verdict("[GiftNote] == 1"));
        actual.put("an enumeration against a number declaration", verdict("[Channel] == [Quantity]"));
        actual.put("a number declaration against an enumeration", verdict("[Quantity] == [Channel]"));
        actual.put("an enumeration against a written-out number", verdict("[Channel] == 1"));
        actual.put("a number declaration against a written-out text", verdict("[Quantity] == \"a\""));
        actual.put("a text declaration against a textless enumeration",
                verdict("[GiftNote] == [Channel]"));
        actual.put("two textless enumerations", verdict("[Channel] == [Carrier]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two text declarations — the control", KernelLaws.ADMITTED);
        expected.put("a text declaration against a number declaration", WRONG_KIND);
        expected.put("a number declaration against a text one", WRONG_KIND);
        expected.put("a text declaration against a written-out number", WRONG_KIND);
        expected.put("an enumeration against a number declaration", WRONG_KIND);
        expected.put("a number declaration against an enumeration", WRONG_KIND);
        expected.put("an enumeration against a written-out number", WRONG_KIND);
        // The walk runs once per side, so the TEXT side raises it even when the number is the declaration
        // and the text is the written-out value.
        expected.put("a number declaration against a written-out text", WRONG_KIND);
        expected.put("a text declaration against a textless enumeration", KernelLaws.ADMITTED);
        expected.put("two textless enumerations", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what a text or enumeration declaration may stand against");
    }

    /** The DOMAIN question: a written-out value opposite an enumeration must be one of its values. */
    @Test
    void whichWrittenOutValuesAnEnumerationAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a declared value", verdict("[Channel] == \"WEB\""));
        actual.put("an undeclared value", verdict("[Channel] == \"OUTLET\""));
        actual.put("an undeclared value on the LEFT", verdict("\"OUTLET\" == [Channel]"));
        actual.put("an undeclared value under inequality", verdict("[Channel] != \"OUTLET\""));
        actual.put("a declared value of a DISPLAY-BEARING enumeration", verdict("[Tier] == \"BASIC\""));
        actual.put("its DISPLAY value rather than its stored one", verdict("[Tier] == \"Basic plan\""));
        actual.put("an undeclared value of a display-bearing enumeration", verdict("[Tier] == \"GOLD\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a declared value", KernelLaws.ADMITTED);
        expected.put("an undeclared value", OUTSIDE_DOMAIN);
        expected.put("an undeclared value on the LEFT", OUTSIDE_DOMAIN);
        expected.put("an undeclared value under inequality", OUTSIDE_DOMAIN);
        expected.put("a declared value of a DISPLAY-BEARING enumeration", KernelLaws.ADMITTED);
        expected.put("its DISPLAY value rather than its stored one", OUTSIDE_DOMAIN);
        expected.put("an undeclared value of a display-bearing enumeration", OUTSIDE_DOMAIN);
        assertEquals(expected, actual, "which written-out values an enumeration admits");
    }

    /** The arms that answer ahead of this one, and the display-value neighbour beside it. */
    @Test
    void theArmsThatAnswerFirstAndTheNeighbourBeside() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text declaration against a date", verdict("[GiftNote] == [PlacedOn]"));
        actual.put("an enumeration against a boolean", verdict("[Channel] == [Expedited]"));
        actual.put("a text declaration ordered against a number", verdict("[GiftNote] < [Quantity]"));
        actual.put("two written-out values", verdict("\"a\" == 1"));
        actual.put("a DISPLAY-BEARING enumeration against a text declaration",
                verdict("[Tier] == [GiftNote]"));
        actual.put("a display-bearing enumeration against a textless one", verdict("[Tier] == [Channel]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a text declaration against a date", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("an enumeration against a boolean", "MVK_INVALID_COMPARE_TO_YESNO");
        expected.put("a text declaration ordered against a number", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("two written-out values", "MVK_INVALID_COMPARE_TO_CONST");
        expected.put("a DISPLAY-BEARING enumeration against a text declaration",
                "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        expected.put("a display-bearing enumeration against a textless one",
                "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        assertEquals(expected, actual, "the arms that answer first and the neighbour beside");
    }
}
