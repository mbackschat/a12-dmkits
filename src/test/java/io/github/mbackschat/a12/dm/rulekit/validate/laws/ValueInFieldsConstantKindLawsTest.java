package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * Which field types each CONSTANT KIND of {@code NumberOfValueInFields} admits in its list.
 *
 * <p>The operator's whole check is one method, and its body is a switch on the constant: a boolean, a string
 * and a number each send the same field list down a different type gate, so the class a model draws is
 * decided by what was written on the LEFT of the {@code In}. The grid varies the constant and the list
 * independently for exactly that reason — a single column would read as a property of the fields.
 *
 * <p>The two boolean arms are not mirror images, which is the finding {@code KF113} already locks on the
 * Confirm carrier: {@code True} accepts a Yes-only declaration and {@code False} does not. This table adds
 * the arms' other halves — what each says about a field that is neither — and the string and number arms.
 */
class ValueInFieldsConstantKindLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.stringField("GiftNote", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.numberField("Backordered", n -> n.fractionDigits(0));
                g.field("Expedited", FieldKind.BOOLEAN);
                g.field("GiftWrapped", FieldKind.BOOLEAN);
                g.field("TermsAccepted", FieldKind.CONFIRM);
                g.field("NewsletterOptIn", FieldKind.CONFIRM);
                g.field("PlacedOn", FieldKind.DATE);
                g.enumField("Channel", e -> {
                    e.value("WEB");
                    e.value("STORE");
                });
                g.group("Packing", p -> {
                    p.field("Fragile", FieldKind.BOOLEAN);
                    p.group("Label", l -> l.stringField("Courier", f -> { }));
                });
            }).build().toJson();

    private static final BoundModel ORDER = BoundModel.ofJson(ORDER_JSON);

    private static String verdict(NumberExpr call) {
        Condition guarded = Dsl.fieldFilled(ORDER.field("/Order/Reference")).and(call.gt(0));
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                new DslRenderer(ConditionLanguage.EN, GROUP).render(guarded));
    }

    /** A condition written out, for the operand shapes the typed builders deliberately cannot express. */
    private static String rawVerdict(String call) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference", "FieldFilled(Reference) And " + call);
    }

    private static FieldRef f(String name) {
        return ORDER.field("/Order/" + name);
    }

    /** {@code True} and {@code False} over the same four lists, so each refusal is attributable to the arm. */
    @Test
    void whichDeclarationsEachBooleanConstantAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("True over two Boolean declarations",
                verdict(Dsl.numberOfValueInFields(true, f("Expedited"), f("GiftWrapped"))));
        actual.put("False over two Boolean declarations",
                verdict(Dsl.numberOfValueInFields(false, f("Expedited"), f("GiftWrapped"))));
        actual.put("True over two Confirm declarations",
                verdict(Dsl.numberOfValueInFields(true, f("TermsAccepted"), f("NewsletterOptIn"))));
        actual.put("False over two Confirm declarations",
                verdict(Dsl.numberOfValueInFields(false, f("TermsAccepted"), f("NewsletterOptIn"))));
        actual.put("True over two text declarations",
                verdict(Dsl.numberOfValueInFields(true, f("GiftNote"), f("Reference"))));
        actual.put("False over two text declarations",
                verdict(Dsl.numberOfValueInFields(false, f("GiftNote"), f("Reference"))));
        actual.put("True over two number declarations",
                verdict(Dsl.numberOfValueInFields(true, f("Quantity"), f("Backordered"))));
        actual.put("False over a date declaration beside a Boolean one",
                verdict(Dsl.numberOfValueInFields(false, f("PlacedOn"), f("Expedited"))));
        actual.put("False over an enumeration declaration beside a Boolean one",
                verdict(Dsl.numberOfValueInFields(false, f("Channel"), f("Expedited"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("True over two Boolean declarations", KernelLaws.ADMITTED);
        expected.put("False over two Boolean declarations", KernelLaws.ADMITTED);
        expected.put("True over two Confirm declarations", KernelLaws.ADMITTED);
        expected.put("False over two Confirm declarations", "MVK_NO_TYPEYESNO");
        expected.put("True over two text declarations", "MVK_NO_TYPE_YES_OR_YESNO");
        expected.put("False over two text declarations", "MVK_NO_TYPEYESNO");
        expected.put("True over two number declarations", "MVK_NO_TYPE_YES_OR_YESNO");
        expected.put("False over a date declaration beside a Boolean one", "MVK_NO_TYPEYESNO");
        expected.put("False over an enumeration declaration beside a Boolean one", "MVK_NO_TYPEYESNO");
        assertEquals(expected, actual, "which declarations each boolean constant admits");
    }

    /**
     * The FALL-THROUGH arm: a constant that is none of the four the switch handles.
     *
     * <p>{@code CheckAnzahlWertOpImpl.check} tests {@code Falsch}, {@code Wahr}, a string literal and a
     * number in that order and throws {@code MVK_INVALID_CONST} after them (source-read 2026-09-15,
     * kernel-core-parser). The grammar's {@code commonKonstante} carries two further alternatives —
     * {@code Heute} and {@code Jetzt}, the point-in-time constants — so the arm is REACHABLE, which a
     * reading of the check method alone cannot say. The typed builder has no overload that writes one here,
     * so the shape arrives only as authored text, which is what {@link #rawVerdict} exists for.
     *
     * <p>The number row is the control: the same call shape with a constant the switch DOES handle, so a
     * refusal below is the constant's and not the call's.
     */
    @Test
    void whichConstantKindsTheSwitchDoesNotHandleAtAll() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Today", rawVerdict("NumberOfValueInFields(Today In Expedited, GiftWrapped) > 0"));
        actual.put("Now", rawVerdict("NumberOfValueInFields(Now In Expedited, GiftWrapped) > 0"));
        actual.put("a number over the same list — the control",
                rawVerdict("NumberOfValueInFields(0 In Expedited, GiftWrapped) > 0"));
        actual.put("the same call fully admitted — the liveness control",
                rawVerdict("NumberOfValueInFields(0 In Quantity, Backordered) > 0"));
        // The boundary the two rows above must NOT be widened past: a bare NAME in the constant slot is
        // not a sixth grammar alternative, so whatever A12 answers there belongs to another family.
        actual.put("a field NAME in the constant slot",
                rawVerdict("NumberOfValueInFields(Reference In Expedited, GiftWrapped) > 0"));
        actual.put("an undeclared name in the constant slot",
                rawVerdict("NumberOfValueInFields(NoSuchThing In Expedited, GiftWrapped) > 0"));
        // A path whose STAR is illegal — Packing does not repeat — so the slot holds a spec that resolves
        // and whose wildcard a later phase would refuse. Which class answers says which phase runs first.
        actual.put("a resolvable spec with an ILLEGAL star in the constant slot",
                rawVerdict("NumberOfValueInFields(Packing*/Fragile In Expedited, GiftWrapped) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Today", "MVK_INVALID_CONST");
        expected.put("Now", "MVK_INVALID_CONST");
        // Not silence but the switch's number arm answering, so the call shape reached it and the two rows
        // above are the constant's doing rather than the operand list's.
        expected.put("a number over the same list — the control", "MVK_NO_NUMBER");
        expected.put("the same call fully admitted — the liveness control", KernelLaws.ADMITTED);
        // A PARSE class, and the same one whether the name is declared or not — so the slot admits no
        // identifier at all and the two rows above are not the start of an "everything else" rule. A family
        // keyed on "not one of the four" would name its own class on both of these, which is the wrong
        // class on a correct rejection.
        expected.put("a field NAME in the constant slot", "MVK_UNEXPECTED_TOKEN");
        expected.put("an undeclared name in the constant slot", "MVK_UNEXPECTED_TOKEN");
        // The PARSE answers before any reference phase does, so the illegal star is never reached — which
        // is why a checker that stands down here draws one class either way rather than two.
        expected.put("a resolvable spec with an ILLEGAL star in the constant slot", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "the constant kinds the switch falls through on");
    }

    /**
     * EVERY spelling the constant slot admits, and what it answers to one it does not.
     *
     * <p>The grammar's {@code konstante} is {@code commonKonstante} alone — six alternatives and no
     * operation — so a checker may refuse the position outright. That is a REFUSAL, which makes the admitted
     * half the load-bearing half: the number alternative carries an optional sign and an optional fractional
     * part, the string one carries A12's doubled-quote escape, and a checker that missed any of them would
     * refuse a model A12 builds. The list is taken from the production rather than from the shapes this
     * estate happened to have written.
     *
     * <p>The number list is counted against NUMBER declarations and the rest against declarations their own
     * arm admits, so every admitted row is an unqualified {@code ADMITTED} rather than a type class standing
     * in for one.
     */
    @Test
    void everySpellingTheConstantSlotAdmitsAndOneItDoesNot() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an integer", rawVerdict("NumberOfValueInFields(0 In Quantity, Backordered) > 0"));
        actual.put("a NEGATIVE integer", rawVerdict("NumberOfValueInFields(-1 In Quantity, Backordered) > 0"));
        actual.put("a decimal", rawVerdict("NumberOfValueInFields(1.5 In Quantity, Backordered) > 0"));
        actual.put("a negative decimal",
                rawVerdict("NumberOfValueInFields(-1.5 In Quantity, Backordered) > 0"));
        actual.put("a string", rawVerdict("NumberOfValueInFields(\"WEB\" In GiftNote, Reference) > 0"));
        actual.put("a string carrying A12's DOUBLED quote",
                rawVerdict("NumberOfValueInFields(\"say \"\"hi\"\"\" In GiftNote, Reference) > 0"));
        actual.put("an EMPTY string", rawVerdict("NumberOfValueInFields(\"\" In GiftNote, Reference) > 0"));
        actual.put("True", rawVerdict("NumberOfValueInFields(True In Expedited, GiftWrapped) > 0"));
        actual.put("False", rawVerdict("NumberOfValueInFields(False In Expedited, GiftWrapped) > 0"));
        actual.put("an OPERATION in the slot",
                rawVerdict("NumberOfValueInFields(Sum(Quantity) In Quantity, Backordered) > 0"));
        actual.put("a parenthesized number in the slot",
                rawVerdict("NumberOfValueInFields((0) In Quantity, Backordered) > 0"));
        actual.put("an EMPTY slot", rawVerdict("NumberOfValueInFields( In Quantity, Backordered) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an integer", KernelLaws.ADMITTED);
        expected.put("a NEGATIVE integer", KernelLaws.ADMITTED);
        expected.put("a decimal", KernelLaws.ADMITTED);
        expected.put("a negative decimal", KernelLaws.ADMITTED);
        expected.put("a string", KernelLaws.ADMITTED);
        expected.put("a string carrying A12's DOUBLED quote", KernelLaws.ADMITTED);
        expected.put("an EMPTY string", KernelLaws.ADMITTED);
        expected.put("True", KernelLaws.ADMITTED);
        expected.put("False", KernelLaws.ADMITTED);
        // The slot takes the bare production and nothing built from it: even PARENTHESES around a legal
        // constant are refused, which is the row that separates "a constant" from "an expression yielding
        // one" and is why a checker may refuse the position rather than only its four typed arms.
        expected.put("an OPERATION in the slot", "MVK_UNEXPECTED_TOKEN");
        expected.put("a parenthesized number in the slot", "MVK_UNEXPECTED_TOKEN");
        // Empty is not a tenth alternative either, so the position is refused for absence exactly as for
        // the wrong token — which is why the checker reports it rather than treating a missing constant as
        // a shape it could not read.
        expected.put("an EMPTY slot", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "the constant slot's admitted vocabulary");
    }

    /** The string and number arms, and the enumeration membership the string arm additionally checks. */
    @Test
    void whichDeclarationsTheStringAndNumberConstantsAdmit() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a string constant over two text declarations",
                verdict(Dsl.numberOfValueInFields("X", f("GiftNote"), f("Reference"))));
        actual.put("a string constant over two number declarations",
                verdict(Dsl.numberOfValueInFields("X", f("Quantity"), f("Backordered"))));
        actual.put("a string constant over two Boolean declarations",
                verdict(Dsl.numberOfValueInFields("X", f("Expedited"), f("GiftWrapped"))));
        actual.put("a member of the enumeration, beside a text declaration",
                verdict(Dsl.numberOfValueInFields("WEB", f("Channel"), f("GiftNote"))));
        actual.put("a non-member of the enumeration, beside a text declaration",
                verdict(Dsl.numberOfValueInFields("MAIL", f("Channel"), f("GiftNote"))));
        actual.put("a number constant over two number declarations",
                verdict(Dsl.numberOfValueInFields(0L, f("Quantity"), f("Backordered"))));
        actual.put("a number constant over two text declarations",
                verdict(Dsl.numberOfValueInFields(0L, f("GiftNote"), f("Reference"))));
        actual.put("a number constant over two Boolean declarations",
                verdict(Dsl.numberOfValueInFields(0L, f("Expedited"), f("GiftWrapped"))));
        actual.put("a string constant over a date declaration beside a text one",
                verdict(Dsl.numberOfValueInFields("X", f("PlacedOn"), f("GiftNote"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a string constant over two text declarations", KernelLaws.ADMITTED);
        expected.put("a string constant over two number declarations", "MVK_NO_STRING_OR_ENUM");
        expected.put("a string constant over two Boolean declarations", "MVK_NO_STRING_OR_ENUM");
        expected.put("a member of the enumeration, beside a text declaration", KernelLaws.ADMITTED);
        expected.put("a non-member of the enumeration, beside a text declaration",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION");
        expected.put("a number constant over two number declarations", KernelLaws.ADMITTED);
        expected.put("a number constant over two text declarations", "MVK_NO_NUMBER");
        expected.put("a number constant over two Boolean declarations", "MVK_NO_NUMBER");
        expected.put("a string constant over a date declaration beside a text one", "MVK_NO_STRING_OR_ENUM");
        assertEquals(expected, actual, "which declarations the string and number constants admit");
    }

    /**
     * The three gates that run BEFORE the constant branch, so a list failing one draws the earlier class.
     *
     * <p>Every row is written as raw condition text rather than through {@link Dsl}, for two distinct
     * reasons. A lone field and an unstarred root group are shapes the typed builders cannot express at all
     * — the multi-field overloads require two operands — so only stored DM-JSON carries them, which is
     * exactly the input the portable checker reads. The two duplicate rows the builders CAN express are
     * strings because the typed surface now pre-checks that arm and would refuse before the kernel could be
     * asked; the kernel's verdict is what this tier exists to hold, and the client refusal is locked
     * separately in {@code dsl.EntityListDuplicateGateTest}.
     */
    @Test
    void theArityDuplicateAndRootGroupGatesPreEmptTheConstantBranch() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the same text declaration listed twice, under False",
                rawVerdict("NumberOfValueInFields(False In GiftNote, GiftNote) > 0"));
        actual.put("the same Boolean declaration listed twice, under False",
                rawVerdict("NumberOfValueInFields(False In Expedited, Expedited) > 0"));
        actual.put("a group beside a field it contains, under False",
                rawVerdict("NumberOfValueInFields(False In Packing, Packing/Fragile) > 0"));
        actual.put("a LONE text declaration, under False",
                rawVerdict("NumberOfValueInFields(False In GiftNote) > 0"));
        actual.put("the ROOT GROUP named from a nested rule, under False",
                KernelLaws.verdictModel(ORDER_JSON, "/Order/Packing", "../../Reference",
                        "FieldFilled(Fragile) And NumberOfValueInFields(False In Order) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the same text declaration listed twice, under False", "MVK_DUPLICATE_PARAM1");
        expected.put("the same Boolean declaration listed twice, under False", "MVK_DUPLICATE_PARAM1");
        expected.put("a group beside a field it contains, under False", "MVK_DUPLICATE_PARAM2");
        expected.put("a LONE text declaration, under False", "MVK_PARAMSIZE_INVALIDN");
        // The root-group gate exists in the same method but is not reachable from a condition in a
        // single-root model: the reference reader refuses the root group's own name before the operator is
        // checked, so `MVK_ROOT_GROUP_REFERENCED` stays unmeasured on this carrier rather than asserted.
        expected.put("the ROOT GROUP named from a nested rule, under False", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "the gates that pre-empt the constant branch");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The same model with a custom stored representation spliced onto the two Boolean declarations.
     *
     * <p>[prefix] is a parameter so one row can misspell the key: an unknown property is the control that
     * says whether the reader takes the declared representation at all, and without it a uniform ADMITTED
     * column cannot be told apart from a splice that never arrived.
     */
    private static String customBooleanVerdict(String prefix, boolean constant, String expedited,
            String giftWrapped) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(ORDER_JSON);
            fieldType(root, "Expedited").put(prefix + "TrueValue", expedited)
                    .put(prefix + "FalseValue", expedited + "N");
            fieldType(root, "GiftWrapped").put(prefix + "TrueValue", giftWrapped)
                    .put(prefix + "FalseValue", giftWrapped + "N");
            String json = root.toString();
            BoundModel bound = BoundModel.ofJson(json);
            Condition guarded = Dsl.fieldFilled(bound.field("/Order/Reference"))
                    .and(Dsl.numberOfValueInFields(constant, bound.field("/Order/Expedited"),
                            bound.field("/Order/GiftWrapped")).gt(0));
            return KernelLaws.verdictModel(json, GROUP, "../Reference",
                    new DslRenderer(ConditionLanguage.EN, GROUP).render(guarded));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the draft's own output must parse", unreadable);
        }
    }

    private static ObjectNode fieldType(JsonNode node, String name) {
        if (node.isObject() && name.equals(node.path("name").asText()) && node.has("Field")) {
            return (ObjectNode) node.get("Field").get("fieldType");
        }
        for (JsonNode child : node) {
            ObjectNode found = child.isContainerNode() ? fieldType(child, name) : null;
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * That a MISMATCHED stored representation does not reach this gate through a consistency check.
     *
     * <p>The kernel's gate compares the two declarations' true (or false) representation and refuses a
     * mismatch, so these rows would be the operator's remaining two classes. They are not: every row is
     * admitted, mismatch and match alike.
     *
     * <p><b>No control can show the splice arrived, which is why the mechanism is carried by a source read
     * rather than by this table.</b> The model reader ignores an unknown property, so a deliberately
     * misspelled key is admitted exactly as the correct one is — measured, and the reason that row is not
     * here. The chain that explains the column: {@code DocumentModelServiceUtils.convert(model, variant,
     * problemReporter)} passes {@code considerNotInD = false}, and on that setting
     * {@code TypeConverter.convertBoolean}/{@code convertConfirm} substitute the literals {@code true} and
     * {@code false} for whatever the declaration wrote, so the two representations compared are always
     * equal.
     *
     * <p>Consequence for the portable checker: {@code MVK_DIFFERENT_YES_VALUE} and
     * {@code MVK_DIFFERENT_NO_VALUE} are unreachable on this surface, and a family reporting them would
     * refuse models A12 admits.
     */
    @Test
    void aMismatchedStoredRepresentationDoesNotReachTheGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DIFFERENT yes representations, under True",
                customBooleanVerdict("notInDCustom", true, "Y", "J"));
        actual.put("the SAME yes representation, under True",
                customBooleanVerdict("notInDCustom", true, "Y", "Y"));
        actual.put("DIFFERENT no representations, under False",
                customBooleanVerdict("notInDCustom", false, "Y", "J"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DIFFERENT yes representations, under True", KernelLaws.ADMITTED);
        expected.put("the SAME yes representation, under True", KernelLaws.ADMITTED);
        expected.put("DIFFERENT no representations, under False", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a mismatched stored representation does not reach the gate");
    }

    /**
     * A GROUP operand is expanded to the fields it contains, and the expansion reaches NESTED groups.
     *
     * <p>The nested row is the one that discriminates: {@code Packing} declares one Boolean directly and one
     * text field inside a subgroup, so an expansion stopping at the group's own members would admit it under
     * {@code False} and a recursive one refuses it.
     */
    @Test
    void aGroupOperandIsExpandedThroughItsNestedGroupsToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("False over a group whose SUBGROUP carries a text declaration",
                verdict(Dsl.numberOfValueInFields(false, ORDER.flattenGroup("/Order/Packing"))));
        actual.put("a string constant over the same group",
                verdict(Dsl.numberOfValueInFields("X", ORDER.flattenGroup("/Order/Packing"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("False over a group whose SUBGROUP carries a text declaration", "MVK_NO_TYPEYESNO");
        expected.put("a string constant over the same group", "MVK_NO_STRING_OR_ENUM");
        assertEquals(expected, actual, "a group operand is expanded through its nested groups too");
    }
}
