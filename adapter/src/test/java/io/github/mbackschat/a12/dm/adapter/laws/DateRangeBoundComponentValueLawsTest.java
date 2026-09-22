package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * Behavioral law — what a SELECTED DateRange endpoint's component actually resolves to ([KF201]). The
 * admission half is `validate.laws.DateRangeBoundComponentLawsTest`; these are the rows that decide whether an
 * implementation that passed the admission grid still reads the right value.
 *
 * <ul>
 *   <li><b>Each endpoint completes by its POSITION.</b> A month-only start completes to that month's first
 *       day, so it equals a month-and-day start exactly when that day IS the first — the pair that separates
 *       positional completion from a month-only comparison.</li>
 *   <li><b>An unconfigured yearless range computes its RETAINED label</b> rather than needing completion, so
 *       a `MM` range's month is a real number with no Base Year anywhere in the model.</li>
 *   <li><b>An UNPLACED range computes the real number zero</b>, not "no value" — a distinction a caller that
 *       treats zero as absent will get wrong.</li>
 *   <li><b>A formally invalid range clears its reader's target while a clean sibling still computes.</b> The
 *       sibling is the control: without it, "cleared" is satisfied by a document that computes nothing.</li>
 * </ul>
 */
class DateRangeBoundComponentValueLawsTest {

    private static final String COVER = "/Cover";
    private static final Set<String> ROW = Set.of(COVER);
    private static final String MODEL = cover();
    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** `StartOfDateRange(MonthOnly) == StartOfDateRange(MonthDay)` — the positional-completion pair. */
    private static String startsAgree() {
        return RuntimeLaws.withRule(MODEL, COVER, "SameStart", "../Ref", "SAME_START",
                RuntimeLaws.render(Dsl.fieldFilled(M.field(COVER + "/Ref"))
                        .and(Dsl.startOfDateRange(M.dateRange(COVER + "/MonthOnly"))
                                .eq(Dsl.startOfDateRange(M.dateRange(COVER + "/MonthDay"))))));
    }

    @Test
    void aMonthOnlyStartCompletesToTheFirstOfItsMonth() {
        assertTrue(RuntimeLaws.fires(startsAgree(), "SAME_START", ROW,
                Map.of(COVER + "/Ref", "C-1", COVER + "/MonthOnly", "06/06", COVER + "/MonthDay", "06-01/06-30")),
                "a month-only start is that month's FIRST day, so 06-01 matches it");

        assertFalse(RuntimeLaws.fires(startsAgree(), "SAME_START", ROW,
                Map.of(COVER + "/Ref", "C-1", COVER + "/MonthOnly", "06/06", COVER + "/MonthDay", "06-02/06-30")),
                "…and the second of the month does not — the completion is positional, not month-wide");
    }

    /** The reader computation: `MonthFromDate(StartOfDateRange(MonthOnly))` into a scale-0 target. */
    private static String monthReader() {
        Computation reader = M.computation(M.num(COVER + "/ReadMonth")).name("MonthCalc")
                .calc(Dsl.monthFromDate(Dsl.startOfDateRange(M.dateRange(COVER + "/MonthOnly"))).expression())
                .message(Dsl.en("The month could not be read.")).build();
        Computation sibling = M.computation(M.num(COVER + "/ReadLength")).name("LengthCalc")
                .calc(Dsl.length(M.str(COVER + "/Ref")).expression())
                .message(Dsl.en("The length could not be read.")).build();
        return M.addAll(List.of(), List.of(reader, sibling)).toJson();
    }

    /**
     * With NO Base Year the yearless range still yields its retained month, and an UNPLACED range yields the
     * real number zero. Both rows read the same computation on the same model; only the document differs.
     */
    @Test
    void aYearlessRangeComputesItsRetainedLabelAndAnUnplacedOneComputesZero() {
        assertEquals(Optional.of("6"), valueOf(monthReader(),
                RuntimeLaws.doc().group(COVER, 1)
                        .field(COVER + "/Ref", "C-1", 1, 1)
                        .field(COVER + "/MonthOnly", "06/09", 1, 1)),
                "an unconfigured MM range needs no completion to expose its month");

        assertEquals(Optional.of("0"), valueOf(monthReader(),
                RuntimeLaws.doc().group(COVER, 1).field(COVER + "/Ref", "C-1", 1, 1)),
                "an unplaced range computes the real zero, not 'no value'");
    }

    /**
     * A formally invalid range clears the reading target, while a sibling computation over a clean operand in
     * the same row still computes — the control that makes "cleared" a statement about the operand rather than
     * about the document.
     */
    @Test
    void anInvalidRangeClearsItsReaderWhileACleanSiblingStillComputes() {
        RuntimeLaws.Doc doc = RuntimeLaws.doc().group(COVER, 1)
                .field(COVER + "/Ref", "C-1", 1, 1)
                .field(COVER + "/MonthOnly", "13/13", 1, 1)
                .field(COVER + "/ReadMonth", "6", 1, 1);

        List<RuntimeLaws.Computed> out = RuntimeLaws.compute(monthReader(), doc);

        assertTrue(out.stream().anyMatch(c -> c.path().endsWith("ReadMonth") && c.cleared()),
                () -> "the invalid range must clear its reader: " + out);
        assertEquals(Optional.of("3"),
                out.stream().filter(c -> c.path().endsWith("ReadLength")).findFirst().flatMap(RuntimeLaws.Computed::value),
                () -> "…while the clean sibling still computes in the same row: " + out);
    }

    private static Optional<String> valueOf(String modelJson, RuntimeLaws.Doc doc) {
        return RuntimeLaws.compute(modelJson, doc).stream()
                .filter(c -> c.path().endsWith("ReadMonth")).findFirst().flatMap(RuntimeLaws.Computed::value);
    }

    private static String cover() {
        return DocumentModelDraft.named("cover").locale("en_US")
                .rootGroup("Cover", cover -> cover
                        .stringField("Ref", f -> { })
                        .dateRangeField("MonthOnly", r -> r.format("MM").separator("/"))
                        .dateRangeField("MonthDay", r -> r.format("MM-dd").separator("/"))
                        .numberField("ReadMonth", n -> { })
                        .numberField("ReadLength", n -> { }))
                .build().toJson();
    }
}
