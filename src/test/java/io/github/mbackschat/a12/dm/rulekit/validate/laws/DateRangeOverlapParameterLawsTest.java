package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §6 — what the date-range OVERLAP predicates accept in their parameter slots.
 *
 * <p>{@code MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON} had no test naming it anywhere in the estate;
 * the ledger's whole basis was the sentence "the range-overlap condition takes exactly one unstarred
 * date-range field". This measures what that means across both carriers — {@code DateRangesOverlap} over an
 * operand list, and {@code AtLeastOneDateRangeOverlaps}, whose first slot is the single range.
 *
 * <p>Every row keeps one declared range format, so a component-set mismatch cannot fire and the parameter
 * shape is the only variable moved.
 */
class DateRangeOverlapParameterLawsTest {

    private static final String INVALID_PARAM = "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON";

    private static final String MODEL = DocumentModelDraft.named("range-overlap-params").locale("en_US")
            .rootGroup("Cover", c -> c
                    .field("Ref", FieldKind.STRING)
                    .dateRangeField("WindowA", r -> r.format("yyyy-MM-dd").separator("/"))
                    .dateRangeField("WindowB", r -> r.format("yyyy-MM-dd").separator("/"))
                    .dateField("PlainDate", d -> d.format("yyyy-MM-dd"))
                    .group("Rows", rows -> rows.repeatable(20).indexField("Key")
                            .field("Key", FieldKind.STRING)
                            .dateRangeField("RowWindow", r -> r.format("yyyy-MM-dd").separator("/"))))
            .build().toJson();

    private static void accepts(String condition) {
        KernelLaws.acceptsModel(MODEL, "/Cover", "../Ref", "FieldFilled(Ref) And " + condition);
    }

    private static void rejects(String condition, String code) {
        KernelLaws.rejectsModel(MODEL, "/Cover", "../Ref", "FieldFilled(Ref) And " + condition, code);
    }

    /** THE LIVENESS CONTROL: both carriers in their ordinary shape are admitted. */
    @Test
    void bothOverlapCarriersAreAdmittedInTheirOrdinaryShape() {
        accepts("DateRangesOverlap(WindowA, WindowB)");
        accepts("AtLeastOneDateRangeOverlaps(WindowA IN Rows*/RowWindow)");
    }

    /** A STARRED range in the single slot — the "exactly one unstarred field" the ledger claims. */
    @Test
    void aStarredRangeInTheSingleSlotIsRefused() {
        rejects("AtLeastOneDateRangeOverlaps(Rows*/RowWindow IN WindowA, WindowB)", INVALID_PARAM);
    }

    /**
     * THE DISCRIMINATING ROW, and it corrects the ledger's sentence. A starred range inside the operand LIST
     * is <b>admitted</b> — so "the range-overlap condition takes exactly one unstarred date-range field"
     * governs the SINGLE SLOT only, not every range position. Without this row the rule reads as a property
     * of the predicate family and is wrong on half of it.
     */
    @Test
    void aStarredRangeInTheOperandListIsAdmitted() {
        accepts("DateRangesOverlap(WindowA, Rows*/RowWindow)");
        accepts("AtLeastOneDateRangeOverlaps(WindowA IN WindowB, Rows*/RowWindow)");
    }

    /** The kind discriminator: a plain DATE in a range slot is a different refusal, not this code. */
    @Test
    void aNonRangeOperandIsADifferentRefusal() {
        KernelLaws.rejectsModelInsteadOf(MODEL, "/Cover", "../Ref",
                "FieldFilled(Ref) And DateRangesOverlap(WindowA, PlainDate)",
                "MVK_NO_DATE_RANGE", INVALID_PARAM);
    }
}
