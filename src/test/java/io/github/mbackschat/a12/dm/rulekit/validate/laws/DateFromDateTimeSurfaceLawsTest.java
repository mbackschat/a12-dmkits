package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS §6 — the static surface of {@code DateFromDateTime} ([KF194], a12-kernel-lean
 * {@code SPEC-2026-08-23-13}). Three gates, each measured here rather than transferred from its
 * {@code TimeFromDateTime} sibling.
 *
 * <ol>
 * <li><b>The result is a DATE, and the refusals are what establish that.</b> It compares against a Date field,
 *     against {@code Today}, and against another {@code DateFromDateTime}, and it is admitted as the Date
 *     operand of {@code AddDays} and of {@code DateTime(...)}. An admission alone would not distinguish a Date
 *     result from a DateTime one that merely compares well, so the pairs that carry the claim are the
 *     refusals: against {@code Now}, against a {@code TimeFromDateTime} result, against a TIME field, and
 *     against the very DATE_TIME field it was extracted from, all {@code MVK_INVALID_COMPARE_TO_DATE}.</li>
 * <li><b>ONE complete-DateTime source gate serves BOTH extractors.</b> A plain DATE field is refused
 *     {@code MVK_WRONG_DATE_FORMAT_FOR_OP} <em>at the operator</em> under {@code DateFromDateTime} and under
 *     {@code TimeFromDateTime} alike — the same class and locus {@link TimeOnlyDateTimeSurfaceLawsTest}
 *     already records for the degenerate time-only DateTime. That a plain Date draws it too means the gate
 *     asks "is this a complete DateTime", not "does the format retain a date".</li>
 * <li><b>The operand grammar is narrower than a general date expression.</b> The operand is a bare path, so
 *     the bracket form is {@code MVK_UNEXPECTED_TOKEN}; the extraction is refused as a {@code Date(...)}
 *     component with its own {@code MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED}; and it is not admitted as a
 *     {@code DifferenceInDays} operand at all. The {@code Date(...)} row needs its control, which is the trap
 *     it was written to avoid: a Number field in the same position draws a <em>different</em> code, so the
 *     refusal is about the extract-op and not about "a non-literal component".</li>
 * </ol>
 *
 * <p>The runtime half — that the extracted date is the source label's OWN wall date and not the retained
 * instant's UTC day — cannot be settled by an admission and lives in
 * {@code adapter.laws.DateFromDateTimeWallDateDiffTest}.
 */
class DateFromDateTimeSurfaceLawsTest {

    private static final String GROUP = "/Booking";
    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";
    private static final String NOT_A_DATE = "MVK_INVALID_COMPARE_TO_DATE";

    /**
     * A travel-booking model: two complete DATE_TIME fields, the degenerate time-only one, two plain DATE
     * fields, a TIME, and a NUMBER for the {@code Date(...)} component control.
     */
    private static final String BOOKING = DocumentModelDraft.named("booking").locale("en_US")
            .rootGroup("Booking", b -> b
                    .stringField("Reference", s -> { })
                    .dateTimeField("CheckedInAt", dt -> { })
                    .dateTimeField("CheckedOutAt", dt -> { })
                    .dateTimeField("ShiftClock", dt -> dt.format("HH:mm:ss"))
                    .dateField("StayFrom", d -> d.format("yyyy-MM-dd"))
                    .dateField("StayTo", d -> d.format("yyyy-MM-dd"))
                    .timeField("Curfew", t -> { })
                    .numberField("Nights", n -> n.fractionDigits(0)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(BOOKING);

    private static String guarded(Condition body) {
        return KernelLaws.render(Dsl.fieldFilled(M.field(GROUP + "/Reference")).and(body));
    }

    private static void admits(Condition body) {
        KernelLaws.acceptsModel(BOOKING, GROUP, "../Reference", guarded(body));
    }

    /**
     * A written-out condition, for the shapes the typed surface cannot express at all — an operand kind the
     * signature refuses, or a grammar position with no typed slot. The kernel row is what grounds each of
     * those type-level erasures ("a client-side refusal needs a measured kernel basis"); without it the
     * missing factory reads as "not a thing" rather than "refused".
     */
    private static void refusesRaw(String body, String code) {
        KernelLaws.rejectsModel(BOOKING, GROUP, "../Reference", "FieldFilled(Reference) And " + body, code);
    }

    private static io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr extracted() {
        return Dsl.dateFromDateTime(M.dateTime(GROUP + "/CheckedInAt"));
    }

    // ── 1. the result is a DATE ──────────────────────────────────────────────────────────────────────

    @Test
    void theExtractedValueComparesAgainstADateField() {
        admits(extracted().eq(M.date(GROUP + "/StayFrom")));
    }

    @Test
    void theExtractedValueComparesAgainstToday() {
        admits(extracted().eq(Dsl.today()));
    }

    @Test
    void theExtractedValueComparesAgainstAnotherExtractedDate() {
        admits(extracted().eq(Dsl.dateFromDateTime(M.dateTime(GROUP + "/CheckedOutAt"))));
    }

    @Test
    void theExtractedValueIsTheDateOperandOfAddDays() {
        admits(Dsl.addDays(extracted(), 3L).eq(M.date(GROUP + "/StayTo")));
    }

    @Test
    void theExtractedValueIsTheDateOperandOfDateTimeConstruction() {
        admits(Dsl.dateTime(extracted(), Dsl.timeFromDateTime(M.dateTime(GROUP + "/CheckedOutAt")))
                .eq(M.dateTime(GROUP + "/CheckedInAt")));
    }

    /** …and the four refusals that make "a DATE" a claim rather than a compatible coincidence. */
    @Test
    void theExtractedValueIsNotComparableWithNow() {
        refusesRaw("DateFromDateTime(CheckedInAt) == Now", NOT_A_DATE);
    }

    @Test
    void theExtractedValueIsNotComparableWithAnExtractedTime() {
        refusesRaw("DateFromDateTime(CheckedInAt) == TimeFromDateTime(CheckedOutAt)", NOT_A_DATE);
    }

    @Test
    void theExtractedValueIsNotComparableWithATimeField() {
        refusesRaw("DateFromDateTime(CheckedInAt) == [Curfew]", NOT_A_DATE);
    }

    @Test
    void theExtractedValueIsNotComparableWithItsOwnDateTimeSource() {
        refusesRaw("DateFromDateTime(CheckedInAt) == [CheckedInAt]", NOT_A_DATE);
    }

    // ── 2. one complete-DateTime source gate, shared by both extractors ─────────────────────────────

    @Test
    void aPlainDateSourceIsRefusedByTheDateExtractor() {
        refusesRaw("DateFromDateTime(StayFrom) == [StayTo]", WRONG_FORMAT);
    }

    @Test
    void aPlainDateSourceIsRefusedByTheTimeExtractorToo() {
        refusesRaw("TimeFromDateTime(StayFrom) == [Curfew]", WRONG_FORMAT);
    }

    /**
     * The degenerate time-only DateTime draws the same class, which is what makes the two rows above one gate
     * rather than two coincidences: neither a source missing its clock nor one missing its calendar passes.
     */
    @Test
    void aTimeOnlyDateTimeSourceIsRefusedByTheDateExtractor() {
        refusesRaw("DateFromDateTime(ShiftClock) == [StayFrom]", WRONG_FORMAT);
    }

    // ── 3. the operand grammar is narrower than a general date expression ───────────────────────────

    @Test
    void theOperandIsABarePathAndNotABracketedValue() {
        refusesRaw("DateFromDateTime([CheckedInAt]) == [StayFrom]", "MVK_UNEXPECTED_TOKEN");
    }

    @Test
    void theExtractionIsRefusedAsADateConstructionComponent() {
        refusesRaw("[StayFrom] == Date(DateFromDateTime(CheckedInAt), \"10\", \"1916\")",
                "MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED");
    }

    /**
     * The control for the row above: a Number field in that same component position draws a DIFFERENT code,
     * so the refusal is keyed on the extract-op and not on "the component is not a literal".
     */
    @Test
    void aNonExtractorComponentInThatPositionDrawsADifferentCode() {
        refusesRaw("[StayFrom] == Date(Nights, \"10\", \"1916\")", WRONG_FORMAT);
    }

    /** …and the accepted control, so the two refusals above are not "Date(...) is illegal on this model". */
    @Test
    void aConstantComponentDateConstructionIsAdmitted() {
        KernelLaws.acceptsModel(BOOKING, GROUP, "../StayFrom",
                "FieldFilled(StayFrom) And [StayFrom] == Date(\"1\", \"10\", \"1916\")");
    }

    @Test
    void theExtractionIsNotADifferenceInDaysOperand() {
        refusesRaw("DifferenceInDays(DateFromDateTime(CheckedInAt), [StayFrom]) == 3", "MVK_UNEXPECTED_TOKEN");
    }

    // ── the nesting that IS admitted, so gate 3 is not read as "never nests" ────────────────────────

    @Test
    void theExtractionNestsInsideAnOrdinaryDateComponentExtractor() {
        admits(Dsl.dayFromDate(extracted()).eq(15L));
    }
}
