package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                            .customField("Secondary", f -> f.type("PolicySerial"))))
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
     * One refused kind in all three shapes: the group row typed through the delegating arm, the two field
     * rows raw because the kind-typed arm erases them.
     */
    private static void refusedKindShapes(Map<String, String> into, String kind, String group, String first,
            String second) {
        into.put(kind + " — a homogeneous group",
                verdict(Dsl.atLeastOneFieldValueIncludedInValueList(XY, C.flattenGroup(group))));
        into.put(kind + " — the same two fields listed out",
                verdict("AtLeastOneFieldValueIncludedInValueList(" + group + "/" + first + ", "
                        + group + "/" + second + " In \"x\", \"y\")"));
        into.put(kind + " — a sole field of that kind",
                verdict("AtLeastOneFieldValueIncludedInValueList(" + group + "/" + first + " In \"x\", \"y\")"));
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
}
