package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — <b>the field-list operations share one entity list and one arity gate, and then each
 * asks a DIFFERENT question of the declarations in it</b>. {@code CheckFeldListenOpImpl.check} is a switch on
 * the operator token over a list the gates ahead of it have already accepted, so the class a model draws is
 * decided by the CALLER and not by the list.
 *
 * <p><b>{@code FirstFilledValue} refuses a CONFIRM declaration outright</b>, in any position and whatever the
 * rest of the list is — {@code checkNoBoolY} walks every expanded field ahead of the type-matching gate. The
 * code names a "booly" and the kernel predicate is {@code FieldUtils.isYes}, which tests {@code IYesType}
 * alone; {@code IYesNoType} is a sibling interface and not a subtype, so a BOOLEAN declaration clears this
 * gate entirely. Reading the code's name as "no boolean kinds" is wrong in exactly that cell.
 *
 * <p><b>After it, the list must be type-homogeneous</b> — {@code MVK_VARYING_TYPES_NOT_ALLOWED} — and the
 * unit compared is the OPERATION RESULT type rather than the declared kind: the arm calls
 * {@code checkAllFieldsMatchingOperationType}, not {@code checkSameBaseType}, so every field is mapped through
 * {@code OperationResultTypeMap.getOpTypeForField} and matched against the FIRST field's mapping. ENUM folds
 * into STRING and a CUSTOM declaration is string-like to begin with, so the string-like block admits mixtures
 * the declared kinds do not.
 *
 * <p><b>The temporal rows of this file's grid are CONFOUNDED and must not be read as a kind law.</b> That
 * mapping sends every {@code IDateType} to one {@code DATETIME} result base type <i>carrying its declared
 * format</i>, so inside the date-bearing family the format decides and the kind is not consulted — while
 * {@code IDateRangeType} gets a result base type of its own. Every temporal field here happens to carry a
 * format bound 1:1 to its kind, which makes the two readings indistinguishable from this grid alone.
 * {@link FirstFilledValueFamilyLawsTest} owns that law and separates them.
 *
 * <p><b>{@code Sum} asks the flat numeric question</b> — every expanded field {@code INumberType}, otherwise
 * {@code MVK_NO_NUMBER} — with no leader and no escape arm, which is what separates it from the extrema.
 *
 * <p><b>The extrema are the third arm and are measured next door</b> in
 * {@link ExtremumOperandKindGridLawsTest}: a NUMBER or non-sortable leader draws {@code MVK_NOT_SORTABLE} and
 * a temporal leader hands the list to the component-set gate. That grid is not repeated here; this class
 * measures the two arms it does not cover.
 *
 * <p><b>Each row's comparand is matched to its leader's class</b>, the confound
 * {@link ExtremumOperandKindGridLawsTest} names: under a comparand of the wrong class the comparison's own
 * gate reports first and the operand gate is never reached.
 *
 * <p>Own domain (an insurance claim intake). The CUSTOM declaration carries an unregistered type name, which
 * the static model gate accepts.
 */
class FieldListOperandKindLawsTest {

    private static final String GROUP = "/Claim";
    private static final String ERROR_REL = "../Reference";

    private static final String NO_BOOLY = "MVK_NO_BOOLY_ALLOWED";
    private static final String VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED";
    private static final String NO_NUMBER = "MVK_NO_NUMBER";

    private static final String CLAIM = DocumentModelDraft.named("claim-intake").locale("en_US")
            .typeDef("PolicyNumber", t -> t.string(s -> s.maxLength(20)))
            .typeDef("ClaimAmount", t -> t.number(n -> n.fractionDigits(2)))
            .rootGroup("Claim", claim -> claim
                    .stringField("Reference", f -> { })
                    .stringField("Notes", f -> { })
                    .stringField("Notes2", f -> { })
                    .numberField("Amount", f -> f.fractionDigits(2))
                    .numberField("Amount2", f -> f.fractionDigits(2))
                    .enumField("Severity", f -> f.value("LOW").value("HIGH"))
                    .enumField("Severity2", f -> f.value("LOW").value("HIGH"))
                    .booleanField("Settled", f -> { })
                    .booleanField("Settled2", f -> { })
                    .confirmField("Acknowledged", f -> { })
                    .confirmField("Acknowledged2", f -> { })
                    .dateField("ReportedOn", f -> f.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", f -> f.format("yyyy-MM-dd"))
                    .timeField("CallStart", f -> f.format("HH:mm:ss"))
                    .timeField("CallEnd", f -> f.format("HH:mm:ss"))
                    .dateTimeField("LoggedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("ClearedAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("CoverMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateFragmentField("RenewMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateRangeField("CoverWindow", f -> f.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("CoverWindow2", f -> f.format("dd.MM.yyyy").separator("-"))
                    .customField("Adjuster", f -> f.type("AdjusterCode"))
                    .customField("Adjuster2", f -> f.type("AdjusterCode"))
                    .typedefField("PolicyRef", "PolicyNumber")
                    .typedefField("PolicyRef2", "PolicyNumber")
                    .typedefField("AmountRef", "ClaimAmount")
                    // one repeatable group per DECLARATION ORDER — a group operand is expanded, and the
                    // reorder is what tells "the walk finds the confirm anywhere" from "the first member
                    // decides", which is the reading the extrema's numeric arm takes.
                    .group("Items", g -> g.repeatable(20)
                            .confirmField("ItemConfirmed", f -> { })
                            .numberField("ItemAmount", f -> f.fractionDigits(2)))
                    .group("Lines", g -> g.repeatable(20)
                            .numberField("LineAmount", f -> f.fractionDigits(2))
                            .confirmField("LineConfirmed", f -> { }))
                    .group("Parts", g -> g.repeatable(20)
                            .numberField("PartAmount", f -> f.fractionDigits(2))
                            .numberField("PartFee", f -> f.fractionDigits(2)))
                    .group("Witnesses", g -> g.repeatable(20)
                            .stringField("WitnessName", f -> { })))
            .build().toJson();

    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(CLAIM, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** A declared kind, two fields of that exact class, and a comparand the class accepts. */
    private record Kind(String label, String first, String second, String comparand) { }

    private static Kind kind(String label, String first, String second) {
        return new Kind(label, first, second, "[" + first + "]");
    }

    private static final Kind STRING = kind("STRING", "Notes", "Notes2");
    private static final Kind NUMBER = new Kind("NUMBER", "Amount", "Amount2", "0");
    private static final Kind ENUM = kind("ENUM", "Severity", "Severity2");
    private static final Kind BOOLEAN = kind("BOOLEAN", "Settled", "Settled2");
    private static final Kind CONFIRM = kind("CONFIRM", "Acknowledged", "Acknowledged2");
    private static final Kind DATE = kind("DATE", "ReportedOn", "ClosedOn");
    private static final Kind TIME = kind("TIME", "CallStart", "CallEnd");
    private static final Kind DATETIME = kind("DATETIME", "LoggedAt", "ClearedAt");
    private static final Kind FRAGMENT = kind("DATE_FRAGMENT", "CoverMonth", "RenewMonth");
    private static final Kind DATE_RANGE = kind("DATE_RANGE", "CoverWindow", "CoverWindow2");
    private static final Kind CUSTOM = kind("CUSTOM", "Adjuster", "Adjuster2");
    private static final Kind TYPEDEF = kind("TYPEDEF", "PolicyRef", "PolicyRef2");

    private static final List<Kind> ALL = List.of(STRING, NUMBER, ENUM, BOOLEAN, CONFIRM, DATE, TIME,
            DATETIME, FRAGMENT, DATE_RANGE, CUSTOM, TYPEDEF);

    /** The string-like block A12 folds together before matching: STRING, ENUM, CUSTOM and a string TYPEDEF. */
    private static final List<Kind> STRING_LIKE = List.of(STRING, ENUM, CUSTOM, TYPEDEF);

    /**
     * The CONFIRM axis, complete over the declared kinds and over both positions: one CONFIRM operand
     * anywhere in the list is the whole law, and it outranks the type-matching gate behind it.
     */
    @Test
    void firstFilledValueRefusesAConfirmDeclarationInEveryPosition() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Kind other : ALL) {
            String leads = "CONFIRM leads, then " + other.label();
            String follows = other.label() + " leads, then CONFIRM";
            actual.put(leads, verdict("FirstFilledValue(" + CONFIRM.first() + ", "
                    + (other == CONFIRM ? CONFIRM.second() : other.first()) + ") == " + CONFIRM.comparand()));
            actual.put(follows, verdict("FirstFilledValue(" + other.first() + ", "
                    + (other == CONFIRM ? CONFIRM.second() : CONFIRM.first()) + ") == " + other.comparand()));
            expected.put(leads, NO_BOOLY);
            expected.put(follows, NO_BOOLY);
        }
        assertEquals(expected, actual, "one CONFIRM operand refuses the whole list, whatever else is in it");
    }

    /**
     * BOOLEAN is the cell the code's name gets wrong. {@code isYes} tests {@code IYesType}; {@code IYesNoType}
     * is a sibling, so a BOOLEAN list clears the gate and is judged by type matching alone.
     */
    @Test
    void firstFilledValueAdmitsABooleanListAndTheCodeNamesTheConfirmKindAlone() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two BOOLEAN", verdict("FirstFilledValue(Settled, Settled2) == [Settled]"));
        actual.put("BOOLEAN then STRING", verdict("FirstFilledValue(Settled, Notes) == [Settled]"));
        actual.put("BOOLEAN then CONFIRM", verdict("FirstFilledValue(Settled, Acknowledged) == [Settled]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two BOOLEAN", KernelLaws.ADMITTED);
        expected.put("BOOLEAN then STRING", VARYING);
        expected.put("BOOLEAN then CONFIRM", NO_BOOLY);
        assertEquals(expected, actual, "the gate reads IYesType alone, so BOOLEAN clears it");
    }

    /**
     * The type-matching gate behind the confirm one: the four string-like kinds mix freely, and a pair drawn
     * from two different result families is refused.
     *
     * <p><b>The two temporal rows at the end do NOT establish a kind law</b>, each moving the kind and the
     * declared format together — see the class note and {@link FirstFilledValueFamilyLawsTest}.
     */
    @Test
    void firstFilledValueMatchesTheOperandBaseTypeAndFoldsEnumAndCustomIntoString() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Kind leader : STRING_LIKE) {
            for (Kind follower : STRING_LIKE) {
                String cell = "string-like: " + leader.label() + " then " + follower.label();
                String second = leader == follower ? leader.second() : follower.first();
                actual.put(cell, verdict("FirstFilledValue(" + leader.first() + ", " + second + ") == "
                        + leader.comparand()));
                expected.put(cell, KernelLaws.ADMITTED);
            }
            String cell = "string-like: " + leader.label() + " then NUMBER";
            actual.put(cell, verdict("FirstFilledValue(" + leader.first() + ", Amount) == " + leader.comparand()));
            expected.put(cell, VARYING);
        }
        for (Kind leader : List.of(NUMBER, DATE, TIME, DATETIME, FRAGMENT, DATE_RANGE)) {
            String same = "same kind: two " + leader.label();
            actual.put(same, verdict("FirstFilledValue(" + leader.first() + ", " + leader.second() + ") == "
                    + leader.comparand()));
            expected.put(same, KernelLaws.ADMITTED);
            String mixed = leader.label() + " then STRING";
            actual.put(mixed, verdict("FirstFilledValue(" + leader.first() + ", Notes) == " + leader.comparand()));
            expected.put(mixed, VARYING);
        }
        actual.put("DATE then TIME", verdict("FirstFilledValue(ReportedOn, CallStart) == [ReportedOn]"));
        expected.put("DATE then TIME", VARYING);
        actual.put("DATE then DATE_FRAGMENT", verdict("FirstFilledValue(ReportedOn, CoverMonth) == [ReportedOn]"));
        expected.put("DATE then DATE_FRAGMENT", VARYING);
        assertEquals(expected, actual, "the base type is matched, with ENUM folded into STRING");
    }

    /**
     * A GROUP operand is expanded to its fields, so a CONFIRM anywhere beneath it is found wherever it was
     * declared. The reorder is the discriminator against "the first member decides".
     */
    @Test
    void aGroupExpansionCarriesItsConfirmDeclarationWhereverItWasDeclared() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a CONFIRM-first expansion", verdict("FirstFilledValue(Items*) == [Items*/ItemAmount]"));
        actual.put("a CONFIRM-last expansion", verdict("FirstFilledValue(Lines*) == [Lines*/LineAmount]"));
        actual.put("an all-NUMBER expansion", verdict("FirstFilledValue(Parts*) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a CONFIRM-first expansion", NO_BOOLY);
        expected.put("a CONFIRM-last expansion", NO_BOOLY);
        expected.put("an all-NUMBER expansion", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the walk expands the group and finds a confirm at any depth in it");
    }

    /**
     * {@code Sum}'s arm: the flat numeric question over every expanded field, with no leader slot and no
     * escape. Every non-NUMBER declaration is refused in either position, and a typedef resolves to its base.
     * A starred String is refused alone and in either position too, against a starred NUMBER admitted beside a
     * NUMBER, so the class reads neither position nor operand form (a12-kernel-lean SPEC-2026-09-23-01).
     */
    @Test
    void sumDemandsANumberDeclarationOfEveryOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Kind other : ALL) {
            if (other == NUMBER) {
                continue;
            }
            String leads = other.label() + " leads a Sum";
            String follows = other.label() + " follows a NUMBER in a Sum";
            actual.put(leads, verdict("Sum(" + other.first() + ", Amount) > 0"));
            actual.put(follows, verdict("Sum(Amount, " + other.first() + ") > 0"));
            expected.put(leads, NO_NUMBER);
            expected.put(follows, NO_NUMBER);
        }
        actual.put("two NUMBER", verdict("Sum(Amount, Amount2) > 0"));
        expected.put("two NUMBER", KernelLaws.ADMITTED);
        actual.put("a NUMBER-based typedef", verdict("Sum(AmountRef, Amount) > 0"));
        expected.put("a NUMBER-based typedef", KernelLaws.ADMITTED);
        actual.put("an all-NUMBER group expansion", verdict("Sum(Parts*) > 0"));
        expected.put("an all-NUMBER group expansion", KernelLaws.ADMITTED);
        actual.put("a mixed group expansion", verdict("Sum(Items*) > 0"));
        expected.put("a mixed group expansion", NO_NUMBER);
        actual.put("a starred STRING alone", verdict("Sum(Witnesses*/WitnessName) > 0"));
        expected.put("a starred STRING alone", NO_NUMBER);
        actual.put("a starred STRING leads a Sum", verdict("Sum(Witnesses*/WitnessName, Amount) > 0"));
        expected.put("a starred STRING leads a Sum", NO_NUMBER);
        actual.put("a starred STRING follows a NUMBER", verdict("Sum(Amount, Witnesses*/WitnessName) > 0"));
        expected.put("a starred STRING follows a NUMBER", NO_NUMBER);
        actual.put("a starred NUMBER leads a Sum", verdict("Sum(Parts*/PartAmount, Amount) > 0"));
        expected.put("a starred NUMBER leads a Sum", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "Sum takes NUMBER declarations only, in every position");
    }
}
