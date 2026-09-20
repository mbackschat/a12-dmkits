package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §6 — {@code ValueAsDate}'s operand must be a <b>partially known</b> DateType, the INVERSE
 * of the gate the extraction family applies.
 *
 * <p>{@code MVK_INVALID_DATE_TYPE_FOR_OP} had no test naming it anywhere in the estate; the ledger's claim
 * dated from a 2026-06-07 family sweep. This measures it: a full-precision DateType and a DATE_FRAGMENT
 * against all three partial precisions, with both day keywords.
 *
 * <p>The two sibling gates now read the same declaration in opposite directions — the extraction family
 * refuses a partly-known operand ({@code MVK_INVALID_DATE_TYPE}, {@code DateExtractionLawsTest}), the entity
 * -list aggregates refuse one too ({@code MVK_PARTIAL_DATE_NOT_ALLOWED}, [KF427]), and this one <em>requires
 * </em> it.
 */
class ValueAsDateOperandPrecisionLawsTest {

    private static final String FOR_OP = "MVK_INVALID_DATE_TYPE_FOR_OP";

    private static final String MODEL = DocumentModelDraft.named("value-as-date-precision").locale("en_US")
            .rootGroup("Record", r -> r
                    .field("Reference", FieldKind.STRING)
                    .dateField("SettledOn", d -> d.format("dd.MM.yyyy"))
                    .dateField("ApproxDay", d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .dateField("ApproxMonth",
                            d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.MONTH_OPTIONAL))
                    .dateField("ApproxYear",
                            d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.YEAR_OPTIONAL))
                    .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy")))
            .build().toJson();

    private static String condition(String field, String keyword) {
        return "FieldFilled(Reference) And [SettledOn] > ValueAsDate(" + field + ", " + keyword + ")";
    }

    /** The claim: a FULL-precision DateType is not an admissible operand. */
    @Test
    void aFullPrecisionDateIsRefused() {
        for (String keyword : List.of("FirstDay", "LastDay")) {
            KernelLaws.rejectsModel(MODEL, "/Record", "../Reference",
                    condition("SettledOn", keyword), FOR_OP);
        }
    }

    /**
     * THE LIVENESS CONTROL, and the denominator: every partial precision is admitted with both keywords, so
     * the refusals above are about the operand's precision and not about the construction, the comparison or
     * the fixture.
     */
    @Test
    void everyPartialPrecisionIsAdmittedWithBothKeywords() {
        for (String field : List.of("ApproxDay", "ApproxMonth", "ApproxYear")) {
            for (String keyword : List.of("FirstDay", "LastDay")) {
                KernelLaws.acceptsModel(MODEL, "/Record", "../Reference", condition(field, keyword));
            }
        }
    }

    /**
     * The kind discriminator. A DATE_FRAGMENT is incomplete by its FORMAT rather than by a precision
     * declaration — the same separation [KF427] measures on the aggregate carrier — so which code it draws
     * says whether this gate reads precision or incompleteness.
     */
    @Test
    void aDateFragmentIsRefusedToo() {
        KernelLaws.rejectsModel(MODEL, "/Record", "../Reference",
                condition("ReportingYear", "FirstDay"), FOR_OP);
    }
}
