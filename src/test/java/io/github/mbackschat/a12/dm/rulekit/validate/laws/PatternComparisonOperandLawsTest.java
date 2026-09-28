package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * What a PATTERN comparison demands of its two sides, in the order it asks.
 *
 * <p>{@code CheckVergleichsBedingungImpl.checkPatternOp} returns before every type-agreement arm and asks
 * four questions of its own, each with its own class. The first is about the SHAPE of the left side rather
 * than its type — it must be a plain field read, not an expression and not a written-out value — which is
 * why a constant on the left draws the shape class and not the type one.
 */
class PatternComparisonOperandLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.stringField("GiftNote", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.enumField("Channel", e -> e.value("WEB").value("STORE"));
                g.dateField("PlacedOn", d -> d.format("dd.MM.yyyy"));
            }).build().toJson();

    private static final String SHAPE = "MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON";

    private static final String KIND = "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON";

    private static final String NOT_CONSTANT = "MVK_MISSING_CONST_IN_PATTERN_COMP";

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    /** The four questions, in A12's own order. */
    @Test
    void whatAPatternComparisonDemandsOfItsSides() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text declaration against a written-out pattern — the control",
                verdict("[GiftNote] PatternMatched \"a+\""));
        actual.put("the negated operator", verdict("[GiftNote] PatternViolated \"a+\""));
        actual.put("a CALL on the left", verdict("Length(GiftNote) PatternMatched \"a+\""));
        actual.put("a written-out value on the left", verdict("\"x\" PatternMatched [GiftNote]"));
        actual.put("a number declaration on the left", verdict("[Quantity] PatternMatched \"a+\""));
        actual.put("an enumeration on the left", verdict("[Channel] PatternMatched \"a+\""));
        actual.put("a date on the left", verdict("[PlacedOn] PatternMatched \"a+\""));
        actual.put("a number declaration on the right", verdict("[GiftNote] PatternMatched [Quantity]"));
        actual.put("a written-out number on the right", verdict("[GiftNote] PatternMatched 1"));
        actual.put("a TEXT DECLARATION on the right", verdict("[GiftNote] PatternMatched [Reference]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a text declaration against a written-out pattern — the control", KernelLaws.ADMITTED);
        expected.put("the negated operator", KernelLaws.ADMITTED);
        expected.put("a CALL on the left", SHAPE);
        // The shape question is asked first, so a written-out value on the left is refused for BEING an
        // expression rather than for its type — the row that separates the first two classes.
        expected.put("a written-out value on the left", SHAPE);
        expected.put("a number declaration on the left", KIND);
        expected.put("an enumeration on the left", KIND);
        expected.put("a date on the left", KIND);
        expected.put("a number declaration on the right", KIND);
        expected.put("a written-out number on the right", KIND);
        // Both sides are text and the right is still refused — for not being written out, which is the one
        // question left once the two type questions have passed.
        expected.put("a TEXT DECLARATION on the right", NOT_CONSTANT);
        assertEquals(expected, actual, "what a pattern comparison demands of its sides");
    }

    /** The last question: the written-out value has to be a pattern A12's admission gate takes. */
    @Test
    void whichWrittenOutPatternsTheGateAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a plain quantifier", verdict("[GiftNote] PatternMatched \"a+\""));
        actual.put("an ESCAPED star before a quantifier",
                verdict("[GiftNote] PatternMatched \"a\\\\*+\""));
        actual.put("a possessive quantifier", verdict("[GiftNote] PatternMatched \"a++\""));
        actual.put("an empty pattern", verdict("[GiftNote] PatternMatched \"\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a plain quantifier", KernelLaws.ADMITTED);
        // The gate reads the SOURCE TEXT, so escaping the star does not exempt the adjacency.
        expected.put("an ESCAPED star before a quantifier", "MVK_INVALID_PATTERN");
        expected.put("a possessive quantifier", "MVK_INVALID_PATTERN");
        // An empty pattern compiles and clears the blacklist, so the gate takes it.
        expected.put("an empty pattern", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which written-out patterns the gate admits");
    }

    /**
     * The first question is about SHAPE, and a keyed read passes it: {@code [Field For key]} is a plain field
     * read to this gate, not a call. The type question still follows, so a keyed NUMBER cell draws the kind class.
     * Kernel text is the admission oracle; DG71's typed indexed-cell factory is checked separately.
     */
    @Test
    void aKeyedFieldReadIsAPlainFieldReadToTheShapeQuestion() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a keyed text cell", keyedVerdict("[/Order/Parcels/Label For \"P1\"] PatternMatched \"a+\""));
        actual.put("the negated operator", keyedVerdict("[/Order/Parcels/Label For \"P1\"] PatternViolated \"a+\""));
        actual.put("a keyed number cell", keyedVerdict("[/Order/Parcels/Weight For \"P1\"] PatternMatched \"a+\""));
        actual.put("a call over the keyed cell",
                keyedVerdict("FieldValueAsString(/Order/Parcels/Weight For \"P1\") PatternMatched \"a+\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a keyed text cell", KernelLaws.ADMITTED);
        expected.put("the negated operator", KernelLaws.ADMITTED);
        expected.put("a keyed number cell", KIND);
        expected.put("a call over the keyed cell", SHAPE);
        assertEquals(expected, actual, "a keyed read is a field read; the kind question still applies");
    }

    @Test
    void keyedKindAndPatternAdmissionPrecedence() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("string and valid pattern", keyedVerdict("[/Order/Parcels/Label For \"P1\"] PatternMatched \"a+\""));
        actual.put("string and invalid pattern", keyedVerdict("[/Order/Parcels/Label For \"P1\"] PatternMatched \"a++\""));
        actual.put("number and valid pattern", keyedVerdict("[/Order/Parcels/Weight For \"P1\"] PatternMatched \"a+\""));
        actual.put("number and invalid pattern", keyedVerdict("[/Order/Parcels/Weight For \"P1\"] PatternMatched \"a++\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("string and valid pattern", KernelLaws.ADMITTED);
        expected.put("string and invalid pattern", "MVK_INVALID_PATTERN");
        expected.put("number and valid pattern", KIND);
        expected.put("number and invalid pattern", KIND);
        assertEquals(expected, actual, "kind precedes pattern admission on the keyed number cell");

        BoundModel model = BoundModel.ofJson(PARCELS_JSON);
        assertDoesNotThrow(() -> Dsl.matches(model.num("/Order/Parcels/Weight")
                .semanticIndex("P1").index(), "a++"),
                "typed construction must let the kernel report the preceding kind class");
    }

    /** Parcels is an index group, so its fields can be read under a key. */
    private static final String PARCELS_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.group("Parcels", p -> p.repeatable(5).indexField("Code")
                        .stringField("Code", f -> { })
                        .stringField("Label", f -> { })
                        .numberField("Weight", n -> n.fractionDigits(0)));
            }).build().toJson();

    private static String keyedVerdict(String comparison) {
        return KernelLaws.verdictModel(PARCELS_JSON, GROUP, "../Reference", "FieldFilled(Reference) And " + comparison);
    }

    /** The gate that answers ahead of the whole arm. */
    @Test
    void theArmYieldsToTheConstantGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two written-out values", verdict("\"x\" PatternMatched \"a+\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two written-out values", "MVK_INVALID_COMPARE_TO_CONST");
        assertEquals(expected, actual, "the arm yields to the constant gate");
    }
}
