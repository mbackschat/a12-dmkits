package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * The value-list quantifier's kind gate is ONE class over seven kinds, and it does not read the slot's
 * SHAPE — a homogeneous group, a two-field list and a sole field draw the same verdict.
 *
 * <p>Measured to discharge a12-kernel-lean's {@code SPEC-2026-09-22-03}, whose acceptance asks for the
 * complete refused denominator, for the shape-blindness, and for NUMBER's membership in the admitted set
 * with the class it draws later.
 *
 * <p><b>Why the shape axis needs its own grid.</b> A group slot is ERASED before the gate answers: the
 * carrier expands it and judges the expansion, so a reader who classifies a group by its own rule has no
 * way to tell that from a carrier that waves group-reached operands past the gate. The two-field list and
 * the sole field are the same question asked with the expansion already written out, which is why all three
 * belong in one table rather than in three.
 *
 * <p><b>The admitted set has FOUR members, one more than the diagnostic names.</b> STRING, ENUMERATION and
 * NUMBER are in the code's own name; a CUSTOM declaration is admitted too, because it shares the string's base
 * type. So the refused denominator below is the complement of four kinds, not of three, and
 * {@code ValueKind}'s eleven authorable members are what makes that a closed statement rather than a list.
 *
 * <p><b>NUMBER is the separator, not an eighth refusal.</b> Every refused row here pairs a slot against a
 * STRING literal list, so "the slot disagrees with the literals" predicts the whole refused half equally
 * well. The Number rows discriminate: they clear this gate against those same String literals and are
 * refused one gate later, by the comparison class — which no account keyed on disagreement can produce.
 *
 * <p><b>Authoring, and what it reveals.</b> Every shape the typed kit can express is authored through it and
 * rendered. That is not only hygiene here: the kit's own layering answers half the question this grid asks.
 * The GROUP arm ({@link Dsl#atLeastOneFieldValueIncludedInValueList(List, io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef)})
 * takes any group and delegates the kind gate, because a group has no kind to type it with — so every group
 * row below is typed, including the refused kinds. The FIELD arm is typed BY KIND ({@code StringRef} /
 * {@code EnumRef}), which is exactly how it erases a wrong-kind slot without consulting a model — so the
 * refused kinds' two-field and sole-field rows have no typed spelling and reach {@link #verdict(String)}.
 * That erasure is the kernel's refusal made static, which is the design working; the rows below measure that
 * the kernel does refuse what the kit erases.
 *
 * <p>Own domain (an insurance claim).
 */
class ValueListSlotShapeBlindnessLawsTest {

    private static final String GROUP = "/Claim";

    private static final String ONLY_THREE_KINDS = "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED";
    private static final String TYPES_FOR_COMPARISON = "MVK_INVALID_TYPES_FOR_COMPARISON";

    private static final String CLAIM = DocumentModelDraft.named("claim-value-list-slot-shapes").locale("en_US")
            .rootGroup("Claim", c -> c
                    .stringField("Reference", f -> { })
                    .numberField("Threshold", f -> f.fractionDigits(2))
                    .group("Texts", g -> g
                            .stringField("Primary", f -> { })
                            .stringField("Secondary", f -> { }))
                    .group("Amounts", g -> g
                            .numberField("Assessed", f -> f.fractionDigits(2))
                            .numberField("Reserved", f -> f.fractionDigits(2)))
                    .group("Tiers", g -> g
                            .enumField("Band", e -> e.value("BASIC").value("PLUS"))
                            .enumField("Grade", e -> e.value("BASIC").value("PREMIUM")))
                    .group("Dates", g -> g
                            .dateField("Reported", f -> f.format("yyyy-MM-dd"))
                            .dateField("Closed", f -> f.format("yyyy-MM-dd")))
                    .group("Times", g -> g
                            .timeField("Opened", f -> f.format("HH:mm:ss"))
                            .timeField("Escalated", f -> f.format("HH:mm:ss")))
                    .group("Stamps", g -> g
                            .dateTimeField("Recorded", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                            .dateTimeField("Revised", f -> f.format("yyyy-MM-dd'T'HH:mm:ss")))
                    .group("Months", g -> g
                            .dateFragmentField("CoverFrom", f -> f.fragmentFormat("yyyy-MM"))
                            .dateFragmentField("CoverTo", f -> f.fragmentFormat("yyyy-MM")))
                    .group("Windows", g -> g
                            .dateRangeField("Cover", f -> f.format("yyyy-MM-dd").separator("/"))
                            .dateRangeField("Extension", f -> f.format("yyyy-MM-dd").separator("/")))
                    .group("Flags", g -> g
                            .booleanField("Fraudulent", f -> { })
                            .booleanField("Reopened", f -> { }))
                    .group("Confirmations", g -> g
                            .confirmField("TermsAccepted", f -> { })
                            .confirmField("PrivacyAccepted", f -> { }))
                    .group("Serials", g -> g
                            .customField("Primary", f -> f.type("PolicySerial"))
                            .customField("Secondary", f -> f.type("PolicySerial")))
                    // Two DIFFERENT registered type names, for the row that separates "CUSTOM enters through
                    // isLikeString as a predefined type" from "CUSTOM is its own family keyed by type name".
                    .group("Mixtures", g -> g
                            .customField("Serial", f -> f.type("PolicySerial"))
                            .customField("Account", f -> f.type("PolicyAccount")))
                    // The one REPEATABLE group, and the only respect in which it differs from `Dates`. Every
                    // other group above is non-repeatable, which was an unnamed constant across the whole
                    // grid until a cold review named it; `theScopeGateOutranksTheKindGate` is what keeps the
                    // grid's denominator honest about the scope it was measured at.
                    .group("Visits", g -> g.repeatable(50)
                            .dateField("Seen", f -> f.format("yyyy-MM-dd"))
                            .dateField("Reviewed", f -> f.format("yyyy-MM-dd")))
                    // The repeatable twin of `Texts`, i.e. of the ADMITTED kind. It is what shows the scope
                    // gate refusing a slot the kind gate would have passed; the `Visits` rows alone move from
                    // one refusal to another, which two independent gates could also produce.
                    .group("Notes", g -> g.repeatable(50)
                            .stringField("Body", f -> { })
                            .stringField("Addendum", f -> { })))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(CLAIM);

    /** The root group, navigated relatively off, so {@code /Claim} is written once for the whole file. */
    private static final GroupInfo C = M.groupAt(GROUP).orElseThrow();

    private static final FieldRef REFERENCE = C.field("Reference");
    private static final NumberRef THRESHOLD = C.num("Threshold");

    private static final List<String> XY = List.of("x", "y");

    /** The typed path: one guard, one call, rendered by the kit rather than spelled out here. */
    private static String verdict(Condition call) {
        return KernelLaws.verdictModel(CLAIM, GROUP, "../Reference",
                Dsl.fieldFilled(REFERENCE).and(call));
    }

    /** The erased path, for a field-arm shape the kind-typed overloads have no spelling for. */
    private static String verdict(String rawCall) {
        return KernelLaws.verdictModel(CLAIM, GROUP, "../Reference", "FieldFilled(Reference) And " + rawCall);
    }

    /**
     * The three slot spellings for one kind, as the kernel receives them — the SINGLE source of the grid's
     * shape axis, so the rows and the witness below cannot drift apart.
     *
     * <p>The group row is authored typed and rendered through the harness; the two field rows are raw because
     * the kind-typed arm erases them. All three come back as text so one assertion can prove they are three.
     */
    private static List<String> theThreeSlotSpellings(String group, String first, String second) {
        return List.of(
                KernelLaws.render(GROUP, Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup(group))),
                "AtLeastOneFieldValueIncludedInValueList(" + group + "/" + first + ", "
                        + group + "/" + second + " In \"x\", \"y\")",
                "AtLeastOneFieldValueIncludedInValueList(" + group + "/" + first + " In \"x\", \"y\")");
    }

    /**
     * One refused kind in all three shapes, evaluated from {@link #theThreeSlotSpellings}.
     *
     * <p><b>It reads them from there rather than rebuilding them, and that is the point.</b> A cold review
     * planted a mutant collapsing the two-fields row onto the sole-field one, and the grid passed: three
     * labels, three identical conditions, nothing able to notice. With one source, the same mutant now makes
     * the distinctness witness fail, so the shape axis is no longer as strong as trust in its row labels.
     */
    private static void refusedKindShapes(Map<String, String> into, String kind, String group, String first,
            String second) {
        List<String> spellings = theThreeSlotSpellings(group, first, second);
        into.put(kind + " — a homogeneous group", verdict(spellings.get(0)));
        into.put(kind + " — the same two fields listed out", verdict(spellings.get(1)));
        into.put(kind + " — a sole field of that kind", verdict(spellings.get(2)));
    }

    private static void expectThree(Map<String, String> into, String kind, String code) {
        into.put(kind + " — a homogeneous group", code);
        into.put(kind + " — the same two fields listed out", code);
        into.put(kind + " — a sole field of that kind", code);
    }

    /**
     * The complete refused denominator, each kind in all three shapes. The String rows are the liveness
     * control, and they are fully typed: without them a uniformly refusing gate would produce this table too.
     */
    @Test
    void sevenKindsDrawOneClassAndTheSlotShapeDoesNotChangeIt() {
        // THE SHAPE AXIS'S OWN WITNESS, before any verdict is read. Three labels are not three shapes, and a
        // grid whose rows happened to carry one condition under three names would agree with itself perfectly.
        List<String> spellings = theThreeSlotSpellings("Dates", "Reported", "Closed");
        assertEquals(3, Set.copyOf(spellings).size(),
                () -> "the three slot shapes must be three distinct conditions, got " + spellings);

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text kind — the control — a homogeneous group",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup("Texts"))));
        actual.put("a text kind — the control — the same two fields listed out",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(XY,
                        C.str("Texts/Primary"), C.str("Texts/Secondary"))));
        actual.put("a text kind — the control — a sole field of that kind",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.str("Texts/Primary"))));
        refusedKindShapes(actual, "DATE", "Dates", "Reported", "Closed");
        refusedKindShapes(actual, "TIME", "Times", "Opened", "Escalated");
        refusedKindShapes(actual, "DATETIME", "Stamps", "Recorded", "Revised");
        refusedKindShapes(actual, "DATEFRAGMENT", "Months", "CoverFrom", "CoverTo");
        refusedKindShapes(actual, "DATERANGE", "Windows", "Cover", "Extension");
        refusedKindShapes(actual, "BOOLEAN", "Flags", "Fraudulent", "Reopened");
        refusedKindShapes(actual, "CONFIRM", "Confirmations", "TermsAccepted", "PrivacyAccepted");

        Map<String, String> expected = new LinkedHashMap<>();
        expectThree(expected, "a text kind — the control", KernelLaws.ADMITTED);
        expectThree(expected, "DATE", ONLY_THREE_KINDS);
        // The four temporal kinds and DATERANGE are each measured rather than transferred from DATE: the gate
        // reads three POSITIVE predicates, so its refused half is a complement, and a complement is what a
        // portable checker may not infer from one member.
        expectThree(expected, "TIME", ONLY_THREE_KINDS);
        expectThree(expected, "DATETIME", ONLY_THREE_KINDS);
        expectThree(expected, "DATEFRAGMENT", ONLY_THREE_KINDS);
        expectThree(expected, "DATERANGE", ONLY_THREE_KINDS);
        expectThree(expected, "BOOLEAN", ONLY_THREE_KINDS);
        expectThree(expected, "CONFIRM", ONLY_THREE_KINDS);
        assertEquals(expected, actual, "one class over seven kinds, in three slot shapes");
    }

    /**
     * NUMBER clears the kind gate in all three shapes and is refused one gate later — the row that fixes the
     * gate's identity, because a rival account keyed on slot-versus-literal disagreement predicts every
     * refused row above and gets this one wrong.
     */
    @Test
    void numberIsInsideTheAdmittedSetAndIsRefusedByTheLaterComparisonClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        refusedKindShapes(actual, "NUMBER against String literals", "Amounts", "Assessed", "Reserved");
        // Typed throughout: the entity-valued arm takes FieldOperand/ValueListOperand, and a bare group scope
        // is one. `num(…).field()` narrows the kind-typed ref to the operand this arm wants — the ref stays
        // the named handle, and the arm delegates the kind gate exactly as the group arm does.
        actual.put("NUMBER against a number field — a homogeneous group",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(List.of(THRESHOLD.field()),
                        C.flattenGroup("Amounts"))));
        actual.put("NUMBER against a number field — the same two fields listed out",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(List.of(THRESHOLD.field()),
                        C.num("Amounts/Assessed").field(), C.num("Amounts/Reserved").field())));
        actual.put("NUMBER against a number field — a sole field of that kind",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(List.of(THRESHOLD.field()),
                        C.num("Amounts/Assessed").field())));
        actual.put("an enumeration, its own member — a homogeneous group",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("BASIC"),
                        C.flattenGroup("Tiers"))));
        actual.put("an enumeration, its own member — the same two fields listed out",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("BASIC"),
                        C.enumOf("Tiers/Band"), C.enumOf("Tiers/Grade"))));
        actual.put("an enumeration, its own member — a sole field of that kind",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("BASIC"),
                        C.enumOf("Tiers/Band"))));
        // The FOURTH admitted kind, and the one the gate's own NAME omits. Raw on the field arm for the same
        // reason NUMBER is: the kind-typed literal overloads take a String or an Enum slot and a CUSTOM
        // declaration is neither, so the kit erases a shape the kernel admits — over-strict, unlike the seven
        // refused kinds where the erasure and the kernel agree.
        actual.put("a CUSTOM-typed field — a homogeneous group",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup("Serials"))));
        actual.put("a CUSTOM-typed field — the same two fields listed out",
                verdict("AtLeastOneFieldValueIncludedInValueList(Serials/Primary, Serials/Secondary"
                        + " In \"x\", \"y\")"));
        actual.put("a CUSTOM-typed field — a sole field of that kind",
                verdict("AtLeastOneFieldValueIncludedInValueList(Serials/Primary In \"x\", \"y\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expectThree(expected, "NUMBER against String literals", TYPES_FOR_COMPARISON);
        // The positive control is field-valued because this carrier has no number LITERAL at all — see the
        // grid below. That makes it the stronger control anyway: the slot clears both gates.
        expectThree(expected, "NUMBER against a number field", KernelLaws.ADMITTED);
        // The third member of the admitted set, on the same three shapes, so the set is witnessed and not
        // merely named.
        expectThree(expected, "an enumeration, its own member", KernelLaws.ADMITTED);
        // …and the FOURTH, which `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` does not name: a CUSTOM declaration
        // shares the string's base type, so `isLikeString` admits it. The code's name is a three-kind list and
        // the admitted set has four members — a reader who takes the name for the set gets this wrong, which
        // is why it is measured here rather than inferred from the scalar carrier.
        expectThree(expected, "a CUSTOM-typed field", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "NUMBER is in the set, and the class it draws is the later one");
    }

    /**
     * <b>CUSTOM enters as a PREDEFINED TYPE, not as a family keyed by its registered type name.</b>
     *
     * <p>The rows above measure one custom type against String literals, which a "CUSTOM is its own family"
     * account predicts just as well. Two DIFFERENT registered type names in one slot is what separates them:
     * a name-keyed family would refuse the pair. The kernel's gate asks
     * {@code isLikeString(f) = t instanceof IStringType || t instanceof IPredefinedType} per expanded field,
     * so the type name is never consulted — this row is that reading's observable consequence.
     */
    @Test
    void twoDifferentCustomTypeNamesShareTheSlotBecauseTheNameIsNotRead() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a group of two DIFFERENT custom types", verdict(
                Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup("Mixtures"))));
        actual.put("the same two listed out",
                verdict("AtLeastOneFieldValueIncludedInValueList(Mixtures/Serial, Mixtures/Account"
                        + " In \"x\", \"y\")"));
        actual.put("a CUSTOM beside a STRING — across the kind boundary",
                verdict("AtLeastOneFieldValueIncludedInValueList(Serials/Primary, Texts/Primary In \"x\", \"y\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a group of two DIFFERENT custom types", KernelLaws.ADMITTED);
        expected.put("the same two listed out", KernelLaws.ADMITTED);
        // …and the disjunct is why this one passes too: both sides satisfy `isLikeString`, by different arms.
        expected.put("a CUSTOM beside a STRING — across the kind boundary", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the registered type name is not part of this gate");
    }

    /**
     * …and the reason the control above had to be field-valued: on the QUANTIFIER carrier a number literal
     * has no spelling. Its scalar twin takes one in the same model, which is what makes this an asymmetry
     * between two carriers rather than a property of the value list.
     *
     * <p>Worth locking because it is invisible from either carrier alone, and because the two refusals sit at
     * different layers: after {@code In}, a bare {@code 1} is read as a PATH and reaches the entity gate,
     * while {@code 1.00} does not survive the parser at all. The kit agrees on both halves — it offers no
     * number-literal spelling on the quantifier arm and offers {@code NumberRef.in(long…)} on the scalar one.
     */
    @Test
    void theQuantifiersValueListHasNoNumberLiteralWhileItsScalarTwinDoes() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an integer literal after In",
                verdict("AtLeastOneFieldValueIncludedInValueList(Amounts/Assessed In 1, 2)"));
        actual.put("a decimal literal after In",
                verdict("AtLeastOneFieldValueIncludedInValueList(Amounts/Assessed In 1.00, 2.00)"));
        actual.put("the same numbers quoted",
                verdict("AtLeastOneFieldValueIncludedInValueList(Amounts/Assessed In \"1\", \"2\")"));
        actual.put("the scalar twin, same field, same literals",
                verdict(C.num("Amounts/Assessed").in(1, 2)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an integer literal after In", "MVK_INVALID_ENTITY");
        expected.put("a decimal literal after In", "MVK_UNEXPECTED_TOKEN");
        // Quoting makes it parse and makes it a STRING, so it clears the kind gate and dies at the comparison
        // one — the same class the String-literal rows above draw.
        expected.put("the same numbers quoted", TYPES_FOR_COMPARISON);
        expected.put("the scalar twin, same field, same literals", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the number literal is a scalar-carrier spelling only");
    }

    /**
     * <b>The SCOPE gate outranks the kind gate, so every row above is a claim about a NON-REPEATABLE carrier
     * and says nothing about a repeatable one.</b>
     *
     * <p>{@code Visits} and {@code Dates} are the same shape — two DATE fields spelled alike — and differ in
     * exactly one builder call, {@code repeatable(50)}. That is the whole experiment: if the kind gate were
     * the first to answer, both would draw {@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED}.
     *
     * <p>This row exists because the grid above shares one property its published denominator never named —
     * no group in it is repeatable — and the omission had already been carried into a peer's specification,
     * where a checker implementing the unqualified row would report the kind gate's code where the kernel
     * reports the scope gate's. Starring the level is what restores the kind gate's reach.
     */
    @Test
    void theScopeGateOutranksTheKindGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a repeatable group, unstarred", verdict(
                Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup("Visits"))));
        actual.put("its two fields listed out, unstarred",
                verdict("AtLeastOneFieldValueIncludedInValueList(Visits/Seen, Visits/Reviewed In \"x\", \"y\")"));
        actual.put("a sole field of it, unstarred",
                verdict("AtLeastOneFieldValueIncludedInValueList(Visits/Seen In \"x\", \"y\")"));
        actual.put("the same group, STARRED",
                verdict("AtLeastOneFieldValueIncludedInValueList(Visits* In \"x\", \"y\")"));
        actual.put("the non-repeatable twin — the control", verdict(
                Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup("Dates"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a repeatable group, unstarred", "MVK_NO_WILDCARD");
        expected.put("its two fields listed out, unstarred", "MVK_NO_WILDCARD");
        expected.put("a sole field of it, unstarred", "MVK_NO_WILDCARD");
        // Starred, the level is bound and the scope gate has nothing to say, so the kind gate answers and
        // draws the grid's class — which is what makes this a gate ORDER and not a second kind rule.
        expected.put("the same group, STARRED", ONLY_THREE_KINDS);
        expected.put("the non-repeatable twin — the control", ONLY_THREE_KINDS);
        assertEquals(expected, actual, "the scope gate answers before the kind gate");
    }

    /**
     * <b>The scope gate refuses a slot the kind gate would have ADMITTED</b> — which is what makes it an
     * outranking rather than two gates that happen to refuse the same rows.
     *
     * <p>{@code Notes} is a repeatable group of the admitted kind. Unstarred it draws the scope gate's class
     * although nothing about it is wrong for the value list; starred, it is admitted outright. The DATE rows
     * above move from one refusal to another, and two unrelated gates could produce that; only a row that
     * ends ADMITTED shows the earlier gate was the one speaking.
     */
    @Test
    void theScopeGateRefusesAnOtherwiseLegalSlot() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a repeatable STRING group, unstarred", verdict(
                Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup("Notes"))));
        actual.put("the same group, STARRED",
                verdict("AtLeastOneFieldValueIncludedInValueList(Notes* In \"x\", \"y\")"));
        actual.put("its non-repeatable twin — the control", verdict(
                Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup("Texts"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a repeatable STRING group, unstarred", "MVK_NO_WILDCARD");
        expected.put("the same group, STARRED", KernelLaws.ADMITTED);
        expected.put("its non-repeatable twin — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the scope gate refuses an otherwise legal slot");
    }
}
