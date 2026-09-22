package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.OpListMath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * KERNEL-FINDINGS §6 — which operand kinds {@code Min}/{@code Max} admit, and which code the kernel reports
 * for each way they can disagree; and the one code {@code Abs} reports for every kind it turns away. This is
 * the measured basis for {@code OpListMath}'s four client-side refusals: rulekit refuses these shapes before
 * the kernel ever sees them, so without this file nothing would notice if the kernel's rule and ours drifted
 * apart, and an over-strict arm would make a legal model unreadable rather than merely unauthorable.
 *
 * <p><b>Typed-surface boundary.</b> Every row here is a raw-String condition by necessity — that is the
 * point of the file. Authoring through the typed builder would measure {@code OpListMath}'s own gate
 * instead of the kernel's, which is the one thing these laws exist to rule out.
 *
 * <p><b>Why the grid has both operand orders.</b> The FIRST operand fixes the class, and the code names that
 * class rather than the offender's — KF249's position law, measured on this spelling for the NUMBER/DATE
 * pair, and completed by KF263 on the field-list {@code MinValue}/{@code MaxValue} only. This file carries
 * KF263's grid to the bracketed spelling rulekit gates. A grid whose temporal rows all lead with the temporal
 * operand cannot tell "the first operand decides" from "either does", which is how this gate first shipped
 * the wrong code for a NUMBER head. The positive controls carry the other half: all four admitted kinds are
 * paired with themselves, so no refusal below can pass by the whole operator being rejected anyway.
 */
class OpListMathKindGateLawsTest {

    // One group carrying every kind the gate distinguishes. The datetime format needs its quoted 'T'
    // separator — "dd.MM.yyyy HH:mm:ss" is refused at declaration time and quietly poisons every row.
    private static final String POLICY = DocumentModelDraft.named("policy-minmax").locale("en_US")
            .rootGroup("Policy", p -> p
                    .numberField("Premium", n -> n.fractionDigits(0, 0))
                    .numberField("Excess", n -> n.fractionDigits(0, 0))
                    .dateField("StartDate", d -> { })
                    .dateField("EndDate", d -> { })
                    .timeField("Cutoff", t -> { })
                    .timeField("Reopen", t -> { })
                    .dateTimeField("Stamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("Stamp2", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateField("DatedStamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("FragMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateFragmentField("FragDay", f -> f.fragmentFormat("yyyy-MM-dd"))
                    .dateRangeField("Window", r -> r.format("dd.MM.yyyy").separator("-"))
                    .dateRangeField("Window2", r -> r.format("dd.MM.yyyy").separator("-"))
                    .booleanField("Renewed", b -> { })
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS"))
                    .stringField("Holder", s -> s.maxLength(40)))
            .build().toJson();

    private static void verdict(String expected, String errorField, String condition) {
        assertEquals(expected, KernelLaws.verdictModel(POLICY, "/Policy", errorField, condition), condition);
    }

    private static Calculation value(String name) {
        return new FieldValue(BoundModel.ofJson(POLICY).field("/Policy/" + name));
    }

    /** The code rulekit CITES when it refuses the same pair client-side. */
    private static String clientCode(String first, String second) {
        KernelCodeRefusal refusal = assertThrows(KernelCodeRefusal.class,
                () -> new OpListMath(OpListMath.Op.MIN, List.of(value(first), value(second))));
        return refusal.code().name();
    }

    @Test
    void eachAdmittedKindIsAcceptedPairedWithItself() {
        verdict(KernelLaws.ADMITTED, "../Premium",
                "FieldFilled(Premium) And Min([Premium], [Excess]) > 0");
        verdict(KernelLaws.ADMITTED, "../StartDate",
                "FieldFilled(StartDate) And Min([StartDate], [EndDate]) > [StartDate]");
        verdict(KernelLaws.ADMITTED, "../Cutoff",
                "FieldFilled(Cutoff) And Min([Cutoff], [Reopen]) > [Cutoff]");
        verdict(KernelLaws.ADMITTED, "../Stamp",
                "FieldFilled(Stamp) And Min([Stamp], [Stamp2]) > [Stamp]");
        // DATE_RANGE has no admitted self-pair — see aHomogeneousUNSORTABLEPairIsRefusedToo
    }

    @Test
    void twoTemporalKindsThatDisagreeAreAFormatIncompatibility() {
        verdict("MVK_DATEFORMATS_NOT_COMPATIBLE", "../StartDate",
                "FieldFilled(StartDate) And Min([StartDate], [Cutoff]) > [StartDate]");
        verdict("MVK_DATEFORMATS_NOT_COMPATIBLE", "../StartDate",
                "FieldFilled(StartDate) And Min([StartDate], [Stamp]) > [StartDate]");
        verdict("MVK_DATEFORMATS_NOT_COMPATIBLE", "../Stamp",
                "FieldFilled(Stamp) And Min([Stamp], [Cutoff]) > [Stamp]");
    }

    @Test
    void oneTemporalOperandBesideANonTemporalOneIsDateAndNondate() {
        // the comparison is varied across the two candidate kinds so the refusal cannot be attributed to it
        verdict("MVK_DATE_AND_NONDATE", "../StartDate",
                "FieldFilled(StartDate) And Min([StartDate], [Premium]) > 01.01.2019");
        verdict("MVK_DATE_AND_NONDATE", "../Premium",
                "FieldFilled(Premium) And Min([StartDate], [Premium]) > 0");
        verdict("MVK_DATE_AND_NONDATE", "../Cutoff",
                "FieldFilled(Cutoff) And Min([Cutoff], [Premium]) > [Cutoff]");
        verdict("MVK_DATE_AND_NONDATE", "../StartDate",
                "FieldFilled(StartDate) And Min([StartDate], [Holder]) > [StartDate]");
    }

    @Test
    void anUnsortableKindWithNoTemporalOperandIsNotSortable() {
        verdict("MVK_NOT_SORTABLE", "../Holder",
                "FieldFilled(Holder) And Min([Holder], [Premium]) > 0");
        verdict("MVK_NOT_SORTABLE", "../Renewed",
                "FieldFilled(Renewed) And Min([Renewed], [Premium]) > 0");
        verdict("MVK_NOT_SORTABLE", "../Tier",
                "FieldFilled(Tier) And Min([Tier], [Premium]) > 0");
    }

    @Test
    void aNonTemporalHeadIsNotSortableWhateverFollowsIt_includingATemporalOperand() {
        // KF249's cell, and the one this gate first got wrong. A NUMBER head beside a temporal operand is NOT
        // date-and-nondate: the head decides, and a non-temporal head is always MVK_NOT_SORTABLE. Every kind
        // of temporal second operand, so the claim is not read off one carrier.
        verdict("MVK_NOT_SORTABLE", "../Premium",
                "FieldFilled(Premium) And Min([Premium], [StartDate]) > 0");
        verdict("MVK_NOT_SORTABLE", "../Premium",
                "FieldFilled(Premium) And Min([Premium], [Cutoff]) > 0");
        verdict("MVK_NOT_SORTABLE", "../Premium",
                "FieldFilled(Premium) And Min([Premium], [Stamp]) > 0");
        // and with the comparison varied, so the code cannot be attributed to the comparison
        verdict("MVK_NOT_SORTABLE", "../Premium",
                "FieldFilled(Premium) And Min([Premium], [StartDate]) > [Excess]");
    }

    @Test
    void aHomogeneousUNSORTABLEPairIsRefusedToo_theRowThatLocksTheFirstGate() {
        // Without this row the first-operand kind gate is not discriminated at all: every other unsortable
        // row pairs the offender with a NUMBER, so the homogeneity loop produces the same code and subsumes
        // the gate. Two DATE_RANGEs agree in kind, so only the gate can refuse them (cold review F5).
        verdict("MVK_NOT_SORTABLE", "../Window",
                "FieldFilled(Window) And Min([Window], [Window2]) > 0");
    }

    @Test
    void theSameCodeHoldsWhenTheUnsortableOperandComesSECOND() {
        // the arm a first pass got wrong: reversed, these reach the homogeneity branch rather than the
        // first-operand kind gate, and the kernel still reports NOT_SORTABLE
        verdict("MVK_NOT_SORTABLE", "../Premium",
                "FieldFilled(Premium) And Min([Premium], [Holder]) > 0");
        verdict("MVK_NOT_SORTABLE", "../Premium",
                "FieldFilled(Premium) And Min([Premium], [Renewed]) > 0");
        verdict("MVK_NOT_SORTABLE", "../Premium",
                "FieldFilled(Premium) And Min([Premium], [Tier]) > 0");
    }

    @Test
    void absTurnsAwayEveryNonNumberKindWithOneCode_temporalIncluded() {
        // Abs takes one operand, so the order that splits Min/Max into three codes has nothing to vary here:
        // every non-NUMBER kind draws the same MVK_NO_NUMBER, the date family with it. The NUMBER row is the
        // positive control that keeps the rest from passing by Abs being refused outright.
        verdict(KernelLaws.ADMITTED, "../Premium", "FieldFilled(Premium) And Abs([Premium]) > 0");
        for (String kind : List.of("Holder", "Renewed", "Tier", "StartDate", "Cutoff", "Stamp")) {
            verdict("MVK_NO_NUMBER", "../" + kind, "FieldFilled(" + kind + ") And Abs([" + kind + "]) > 0");
        }
    }

    @Test
    void rulekitCitesThatCodeForAbs_andTheTypedFactoryReachesTheGate() {
        // Through Dsl.abs rather than the record constructor: NumberExpr's public constructor wraps any
        // Calculation, so a non-number operand is representable on the typed path and the gate is what
        // stands between it and the kernel. Every kind the kernel row above measured, and no other.
        for (String kind : List.of("Holder", "Renewed", "Tier", "StartDate", "Cutoff", "Stamp")) {
            KernelCodeRefusal refusal = assertThrows(KernelCodeRefusal.class,
                    () -> Dsl.abs(new NumberExpr(value(kind))), kind);
            assertEquals("MVK_NO_NUMBER", refusal.code().name(), kind);
        }
        Dsl.abs(new NumberExpr(value("Premium")));
    }

    @Test
    void rulekitRefusesTheSamePairsWithTheSameCodeTheKernelReports() {
        // The rows above pin the KERNEL; this one pins OUR gate against them, which is what makes the
        // citations in OpListMath measured rather than decorative. Built by direct construction because
        // Dsl.min is overloaded per kind (NumberExpr / DateOperand / TimeOperand / DateTimeOperand), so a
        // mixed pair is unrepresentable through the typed factories — these checks guard the READ path and
        // internal construction, not typed authoring, which is why an over-strict arm here would show up as
        // a legal model becoming unreadable rather than as an authoring complaint.
        assertEquals("MVK_DATEFORMATS_NOT_COMPATIBLE", clientCode("StartDate", "Cutoff"));
        assertEquals("MVK_DATE_AND_NONDATE", clientCode("StartDate", "Premium"));
        assertEquals("MVK_DATE_AND_NONDATE", clientCode("Cutoff", "Premium"));
        assertEquals("MVK_DATE_AND_NONDATE", clientCode("StartDate", "Holder"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Holder", "Premium"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Renewed", "Premium"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Tier", "Premium"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Premium", "Holder"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Premium", "Renewed"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Premium", "Tier"));
        // cold review F8's cell, which the first version of this gate got wrong: a NUMBER head decides
        assertEquals("MVK_NOT_SORTABLE", clientCode("Premium", "StartDate"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Premium", "Cutoff"));
        assertEquals("MVK_NOT_SORTABLE", clientCode("Premium", "Stamp"));
        // homogeneous and unsortable, so only the first-operand kind gate can produce it
        assertEquals("MVK_NOT_SORTABLE", clientCode("Window", "Window2"));
    }

    @Test
    void aDateTimeFormatIsInvisibleToThisGate_soItUnderReportsRatherThanRefusing() {
        // The one row where the two sides disagree, in the SAFE direction. ModelIndex.kindOf keys on the
        // declared kernel type, so a DateType field carrying a datetime format reads as DATE — and the pair
        // below looks homogeneous to us while the kernel calls it MVK_DATEFORMATS_NOT_COMPATIBLE (locked
        // above). Delegation can only under-report, so this is allowed; it is recorded because the opposite
        // direction would not be, and because making kindOf format-aware would turn this row into a refusal
        // that needs its code. The typed accessors do NOT share the blind spot: BoundModel.dateTime(path)
        // checks the format shape, so this is the generic field(path) and read path only.
        new OpListMath(OpListMath.Op.MIN, List.of(value("StartDate"), value("DatedStamp")));
        assertEquals("MVK_DATEFORMATS_NOT_COMPATIBLE",
                KernelLaws.verdictModel(POLICY, "/Policy", "../StartDate",
                        "FieldFilled(StartDate) And Min([StartDate], [DatedStamp]) > [StartDate]"));
    }

    @Test
    void aFragmentIsJudgedByItsComponents_soFoldingItToDateIsRightOnceAndQuietOnce() {
        // The kernel reads the component set, not the kind (KF263), and OpListMath.resultKind folds
        // DATE_FRAGMENT to DATE. Same components as the DATE: both sides admit, so the fold is correct there.
        // Fewer components: the kernel refuses in both positions and our gate says nothing — an under-report,
        // safe, and pinned so that a component-aware gate is forced to cite this code rather than invent one.
        for (String[] pair : new String[][]{{"StartDate", "FragDay"}, {"FragDay", "StartDate"}}) {
            verdict(KernelLaws.ADMITTED, "../" + pair[0], minComparedWithItsHead(pair));
            new OpListMath(OpListMath.Op.MIN, List.of(value(pair[0]), value(pair[1])));
        }
        for (String[] pair : new String[][]{{"StartDate", "FragMonth"}, {"FragMonth", "StartDate"}}) {
            verdict("MVK_DATEFORMATS_NOT_COMPATIBLE", "../" + pair[0], minComparedWithItsHead(pair));
            new OpListMath(OpListMath.Op.MIN, List.of(value(pair[0]), value(pair[1])));
        }
    }

    private static String minComparedWithItsHead(String[] pair) {
        return "FieldFilled(" + pair[0] + ") And Min([" + pair[0] + "], [" + pair[1] + "]) > [" + pair[0] + "]";
    }

    @Test
    void rulekitAdmitsEveryPairTheKernelAdmits() {
        // the direction that catches an OVER-rejection: our gate must not refuse a shape the kernel accepts
        new OpListMath(OpListMath.Op.MIN, List.of(value("Premium"), value("Excess")));
        new OpListMath(OpListMath.Op.MIN, List.of(value("StartDate"), value("EndDate")));
        new OpListMath(OpListMath.Op.MIN, List.of(value("Cutoff"), value("Reopen")));
        new OpListMath(OpListMath.Op.MIN, List.of(value("Stamp"), value("Stamp2")));
    }
}

