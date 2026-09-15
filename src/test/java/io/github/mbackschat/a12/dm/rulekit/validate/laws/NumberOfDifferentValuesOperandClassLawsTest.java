package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — which operand of {@code NumberOfDifferentValues} the homogeneity gate reports,
 * measured 2026-08-31 on kernel 30.8.1 through {@code checkConsistency}.
 *
 * <p><b>Why this file exists.</b> [KF173](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf173) established for
 * {@code FieldValuesNotUnique} that "each gate's SCOPE is the whole operand list, so authored position never
 * selects the reported class". That sentence is true of <i>that</i> operator and was never measured on this
 * one; the two are separate gates, distinguished by the {@code CMP} token in their kind codes. a12-kernel-lean
 * reported the opposite behaviour here from their own probe, so it is measured on our fixtures rather than
 * carried on their report — the rows below are ours.
 *
 * <p><b>The discriminator is the pair, not either row.</b> A single mismatched list cannot separate "the code
 * names the offender" from "the code names the leading class", because with two operands each reading picks a
 * different one of the two and both are consistent with a single observation. Two lists over the <i>same two
 * fields</i> differing in nothing but declaration order separate them: an offender-naming gate reports the
 * same code twice, a first-operand gate flips it. It flips.
 *
 * <p><b>Every condition here is a raw DSL string on purpose.</b> {@code CountAggregate.checkValueComparable}
 * pre-checks this rule and would throw before the kernel ever saw the shape, so the typed builder cannot pose
 * the question. That pre-check classifies by {@code fields.getFirst()} and therefore already agrees with what
 * is measured below; these rows are what make that agreement evidence instead of coincidence.
 */
class NumberOfDifferentValuesOperandClassLawsTest {

    private static final String GROUP = "/Catalogue";
    private static final String ERROR_FIELD = "../Sku";

    private static final String NUMBER_AND_NON_NUMBER = "MVK_NUMBER_AND_NON_NUMBER";
    private static final String STRING_ENUM_AND_NON_STRING_ENUM = "MVK_STRING_ENUM_AND_NON_STRING_ENUM";
    private static final String DATE_AND_NONDATE = "MVK_DATE_AND_NONDATE";
    private static final String NOT_COMPARABLE = "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED";

    /**
     * One product-catalogue group carrying one field of each class the gate partitions, so every pair below
     * differs only in which two fields it names and in what order.
     */
    private static final String CATALOGUE = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Catalogue", c -> {
                c.stringField("Sku", f -> { });
                c.numberField("Weight", f -> f.fractionDigits(0, 3));
                c.stringField("Label", f -> { });
                c.dateField("Released", f -> f.format("dd.MM.yyyy"));
                c.field("Discontinued", FieldKind.BOOLEAN);
            }).build().toJson();

    /** Guard the aggregate so the probe rule references its own error field (MVK_ERROR_FIELD_NOT_REFERENCED). */
    private static String guarded(String operands) {
        return "FieldFilled(Sku) And NumberOfDifferentValues(" + operands + ") > 0";
    }

    private static void rejects(String operands, String expected) {
        KernelLaws.rejectsModel(CATALOGUE, GROUP, ERROR_FIELD, guarded(operands), expected);
    }

    /**
     * The whole finding, in one pair: the same two fields, swapped, report different codes. Each row also
     * pins the code the rival reading predicts as ABSENT, so a kernel that emitted both would fail here
     * rather than satisfy whichever assertion was written first.
     */
    @Test
    void theReportedClassFollowsTheFIRSTOperandAndNotTheOffender() {
        KernelLaws.rejectsModelInsteadOf(CATALOGUE, GROUP, ERROR_FIELD, guarded("Weight, Label"),
                NUMBER_AND_NON_NUMBER, STRING_ENUM_AND_NON_STRING_ENUM);
        KernelLaws.rejectsModelInsteadOf(CATALOGUE, GROUP, ERROR_FIELD, guarded("Label, Weight"),
                STRING_ENUM_AND_NON_STRING_ENUM, NUMBER_AND_NON_NUMBER);
    }

    /**
     * The third class behaves the same way, which is what makes it a rule about position rather than a
     * two-code quirk. A date leading a string reports the date code; the same pair reversed reports the
     * string/enum one.
     */
    @Test
    void theDateClassLeadsTheSameWay() {
        rejects("Released, Label", DATE_AND_NONDATE);
        rejects("Label, Released", STRING_ENUM_AND_NON_STRING_ENUM);
    }

    /**
     * <b>The first operand selects which GATE runs, not merely which class the homogeneity gate names.</b>
     * This was written as a control expecting the opposite — that an inadmissible KIND reports its own code
     * from either slot — and the kernel refuted it. A Boolean leading the list is a kind violation; the same
     * Boolean behind a String is reported as a homogeneity violation instead, because the leading String has
     * already fixed the expected class and the Boolean simply is not in it.
     *
     * <p>So {@code MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED} is reachable only from the leading slot, and
     * a consumer switching on the code cannot infer which operand offended — the kernel's message text names
     * it ({@code Corrupt: 'Discontinued'}) while the code names the class the list was judged against.
     */
    @Test
    void theFirstOperandSelectsWhichGateRunsAtAll() {
        rejects("Discontinued, Label", NOT_COMPARABLE);
        rejects("Label, Discontinued", STRING_ENUM_AND_NON_STRING_ENUM);
    }

    /** The live control: a homogeneous list of the same fields is admitted, so the rejections mean something. */
    @Test
    void aHomogeneousListIsAdmitted() {
        KernelLaws.acceptsModel(CATALOGUE, GROUP, ERROR_FIELD, guarded("Sku, Label"));
    }
}
