package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §6/§9 — {@code MVK_PARTIAL_DATE_NOT_ALLOWED}'s carriers, measured.
 *
 * <p>The ledger carried this code as "partly-known dates are barred from most date ops" with no test anywhere
 * naming it. "Most" is wrong: it is thrown from a single site,
 * {@code CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType}, reached only by the entity-list
 * aggregate checks — so its whole denominator is {@code MaxValue}, {@code MinValue} and
 * {@code NumberOfDifferentValues}. That is the SAME method and the SAME three carriers as the component-set
 * gate in {@link TemporalAggregateComponentSetLawsTest}: one kernel check, two refusals.
 *
 * <p>Except where a row says otherwise, both operands share one declared format, so the component-set arm
 * cannot fire and precision is the only variable moved. The two rows that deliberately break that — the
 * DATE_FRAGMENT kind discriminator and the gate-ordering row — say so and assert which code wins.
 */
class PartialDateAggregateLawsTest {

    private static final String GROUP = "/Claim";
    private static final String PARTIAL = "MVK_PARTIAL_DATE_NOT_ALLOWED";

    /**
     * One declared format across every date field, so a refusal can only be about precision. Carries all three
     * partial precisions, a full-precision pair for the liveness control, a DATE_FRAGMENT (a different kernel
     * type, so the discriminator for "partly known" versus "inherently incomplete"), and a DATETIME pair.
     */
    private static final String CLAIMS = DocumentModelDraft.named("motor-claims-precision").locale("en_US")
            .rootGroup("Claim", c -> c
                    .stringField("Reference", s -> { })
                    .dateField("FiledOn", d -> d.format("dd.MM.yyyy"))
                    .dateField("ClosedOn", d -> d.format("dd.MM.yyyy"))
                    .dateField("ReopenedOn", d -> d.format("dd.MM.yyyy"))
                    .dateField("ApproxDay", d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .dateField("ApproxMonth",
                            d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.MONTH_OPTIONAL))
                    .dateField("ApproxYear",
                            d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.YEAR_OPTIONAL))
                    .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy"))
                    // Full precision, different component set — pairs with a partial operand for the
                    // ordering row. A partial precision cannot be declared on a format lacking the component
                    // it makes optional: `yyyy-MM` + DAY_OPTIONAL is refused at MODEL level,
                    // MVK_OPTIONAL_DATE_TYPE_INVALID, so the both-arms case needs two fields, not one.
                    .dateField("CoverFrom", d -> d.format("yyyy-MM"))
                    .dateTimeField("SealedAt", dt -> { })
                    .dateTimeField("ArchivedAt", dt -> { })
                    .dateTimeField("ReviewedAt", dt -> { })
                    // The OTHER two operand shapes the client-side gate inspects, so its denominator is
                    // measured rather than transferred from the direct-reference rows: a starred list and an
                    // index-selected cell. One declared format throughout, so precision stays the only
                    // variable on those rows too.
                    .group("Incidents", i -> i.repeatable(20).indexField("IncidentKey")
                            .field("IncidentKey", FieldKind.STRING)
                            .dateField("OccurredOn", d -> d.format("dd.MM.yyyy"))
                            .dateField("ResolvedOn", d -> d.format("dd.MM.yyyy"))
                            .dateField("ApproxOccurred",
                                    d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL))))
            .build().toJson();

    /** The three aggregates, each rendered around a two-field operand list. */
    private static String aggregate(String operator, String first, String second) {
        return "NumberOfDifferentValues".equals(operator)
                ? operator + "(" + first + ", " + second + ") == 1"
                : operator + "(" + first + ", " + second + ") == [ReopenedOn]";
    }

    private static void refuses(String operator, String first, String second, String code) {
        KernelLaws.rejectsModel(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And " + aggregate(operator, first, second), code);
    }

    private static void admits(String operator, String first, String second) {
        KernelLaws.acceptsModel(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And " + aggregate(operator, first, second));
    }

    private static final List<String> CARRIERS =
            List.of("MaxValue", "MinValue", "NumberOfDifferentValues");

    /** The claim: all three carriers refuse a partly-known date in their operand list. */
    @Test
    void everyEntityListAggregateRefusesAPartlyKnownDate() {
        CARRIERS.forEach(operator -> refuses(operator, "FiledOn", "ApproxDay", PARTIAL));
    }

    /**
     * THE LIVENESS CONTROL, and the row that makes the rest attributable: the identical operand shape with two
     * FULL-precision dates of the same declared format is admitted, so the refusals above are about the
     * precision declaration and not about the aggregate, the comparison, or the error field.
     */
    @Test
    void theSameAggregatesOverFullPrecisionDatesAreAdmitted() {
        CARRIERS.forEach(operator -> admits(operator, "FiledOn", "ClosedOn"));
    }

    /** Every partial precision trips it, not only the deepest — the gate asks "partly known", not "how much". */
    @Test
    void allThreePartialPrecisionsAreRefused() {
        for (String partial : List.of("ApproxDay", "ApproxMonth", "ApproxYear")) {
            refuses("MaxValue", "FiledOn", partial, PARTIAL);
        }
    }

    /**
     * POSITION discriminator. The kernel reaches this check only when the operand list's FIRST field is a
     * datetime base type, so "any operand is partial" and "the first operand is partial" are different rules
     * and every row above holds the partial operand SECOND.
     */
    @Test
    void aPartialDateInTheFirstPositionIsRefusedToo() {
        refuses("MaxValue", "ApproxDay", "FiledOn", PARTIAL);
    }

    /**
     * KIND discriminator. The kernel's partly-known lookup answers for an {@code IDateType} only, so a
     * DATE_FRAGMENT — incomplete by its format rather than by a precision declaration — is a different thing
     * and must not draw this code.
     */
    @Test
    void aDateFragmentIsNotAPartlyKnownDate() {
        KernelLaws.rejectsModelInsteadOf(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And " + aggregate("MaxValue", "FiledOn", "ReportingYear"),
                "MVK_DATEFORMATS_NOT_COMPATIBLE", PARTIAL);
    }

    /**
     * ORDERING, and the row the whole gate ordering rests on. An operand that is both partly known AND of a
     * different component set satisfies both arms of the same kernel check, so which code it reports is a
     * fact about the kernel's order rather than about either rule — and a client-side gate has to copy it.
     *
     * <p>This row was measured on 2026-09-03, then LOST to a later edit of this file while three places went
     * on citing it as the basis for running the precision check first. Restored.
     */
    @Test
    void aPartialOperandOfADifferentComponentSetReportsThePrecisionCode() {
        KernelLaws.rejectsModelInsteadOf(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And " + aggregate("MaxValue", "ApproxDay", "CoverFrom"),
                PARTIAL, "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    /**
     * A DATETIME pair carries no precision declaration at all, so this gate has nothing to say about it. The
     * comparand is a DATETIME rather than the DATE the other rows use: a DATETIME aggregate against a DATE is
     * {@code MVK_INVALID_COMPARE_TO_DATE} regardless of precision, which would make the row unattributable.
     */
    @Test
    void aDateTimePairIsAdmitted() {
        KernelLaws.acceptsModel(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And MaxValue(SealedAt, ArchivedAt) == [ReviewedAt]");
    }

    /**
     * CARRIER denominator. {@code Dsl.requireFullDatePrecision} reads a path out of three operand shapes —
     * a direct {@code FieldRef}, a starred {@code FieldListRef} and an {@code IndexedFieldValue} — while
     * every row above uses the first. Applying the direct-reference verdict to the other two would be a
     * scope transfer, and a client-side refusal may be no broader than what was measured on its own carrier.
     *
     * <p>Each carrier carries its own liveness control: the identical shape over two FULL-precision fields
     * of the same declared format, so a refusal is about the precision and not about the starring or the
     * selection.
     */
    @Test
    void theStarredAndIndexSelectedCarriersRefuseAPartlyKnownDateToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("starred list, partial", verdict(
                "MaxValue(/Claim/Incidents*/OccurredOn, /Claim/Incidents*/ApproxOccurred) == [ReopenedOn]"));
        actual.put("starred list, full-precision control", verdict(
                "MaxValue(/Claim/Incidents*/OccurredOn, /Claim/Incidents*/ResolvedOn) == [ReopenedOn]"));
        actual.put("index-selected, partial", verdict(
                "MaxValue(/Claim/Incidents/OccurredOn For \"K1\", /Claim/Incidents/ApproxOccurred For \"K1\") "
                        + "== [ReopenedOn]"));
        actual.put("index-selected, full-precision control", verdict(
                "MaxValue(/Claim/Incidents/OccurredOn For \"K1\", /Claim/Incidents/ResolvedOn For \"K1\") "
                        + "== [ReopenedOn]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("starred list, partial", PARTIAL);
        expected.put("starred list, full-precision control", "admitted");
        expected.put("index-selected, partial", PARTIAL);
        expected.put("index-selected, full-precision control", "admitted");
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual,
                "whether the gate's other two operand carriers report the same code as a direct reference");
    }

    private static String verdict(String condition) {
        var r = KernelLaws.checkModel(CLAIMS, GROUP, "../Reference", "FieldFilled(Reference) And " + condition);
        return r.ok() ? "admitted"
                : r.diagnostics().stream().map(d -> d.code().orElse("?")).findFirst().orElse("?");
    }
}
