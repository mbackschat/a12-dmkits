package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6/§9 — <b>a FILTERED star is an admitted extremum operand</b>, on the temporal carrier and
 * the numeric one, and the temporal component-set gate reads through the filter unchanged.
 *
 * <p><b>Why this file exists at all.</b> Nothing in this estate crossed {@code Having} with the extremum
 * operators: six law files use {@code .having(…)} and none of them reaches {@code MaxValue}/{@code MinValue}
 * or {@code latestOf}/{@code earliestOf}. So the shape had no measured verdict here, and our own pre-checks
 * ({@code ListAggregate}, {@code DateListAggregate}) inspect an operand's KIND and never its filter — which
 * reads as "filtered stars are fine" but had never been confirmed against the kernel. A pre-check that is
 * silent about a shape and a kernel that admits it look identical from inside the code; only a run separates
 * "deliberately delegated" from "nobody looked".
 *
 * <p><b>The rival reading this kills.</b> a12-kernel-lean declined to admit the shape on the ground that the
 * neighbouring DATERANGE-endpoint carrier genuinely refuses starred operands ({@code MVK_NO_WILDCARDS_ALLOWED}
 * — a real divergence, not a guess). A known-real boundary predicting the wrong side of itself is the strongest
 * possible argument for measuring rather than reasoning, and they report the kernel admits the filtered star at
 * the extremum. These rows are that result re-derived here, on our own fixture, against the same oracle.
 *
 * <p><b>Two controls carry each carrier row.</b> A PLAIN star must be admitted before a filtered one is
 * readable — otherwise "filtered stars are refused" and "this operand shape never reaches the gate" are the
 * same observation, which is exactly how their first attempt died. And the NUMERIC extremum sits beside the
 * temporal one so a refusal would separate "filtered stars are refused at extrema generally" from "the temporal
 * carrier refuses filtered forms".
 *
 * <p>Own domain (motor policy inspections).
 */
class FilteredStarExtremumOperandLawsTest {

    private static final String GROUP = "/Policy";
    private static final String ERROR_REL = "../Reference";
    private static final String INCOMPATIBLE = "MVK_DATEFORMATS_NOT_COMPATIBLE";
    private static final String NOT_SORTABLE = "MVK_NOT_SORTABLE";
    private static final String REVIEWED = "/Policy/ReviewedOn";
    private static final String CLOSED_ON = "/Policy/ClosedOn";
    private static final String COVER_FROM = "/Policy/CoverFrom";

    /**
     * A repeatable {@code Visits} group supplying the starred list, plus two direct dates chosen to separate
     * the temporal gate's two answers: {@code CoverFrom} differs from the visit date in its component SET,
     * while {@code ClosedOn} carries the SAME set spelled differently.
     */
    private static final String POLICY = DocumentModelDraft.named("motor-policy").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", s -> { })
                    .dateField("ReviewedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", d -> d.format("dd.MM.yyyy"))
                    .dateField("CoverFrom", d -> d.format("yyyy-MM"))
                    .numberField("Excess", n -> n.fractionDigits(0))
                    .group("Visits", v -> v.repeatable(20)
                            .stringField("Outcome", s -> { })
                            .dateField("VisitOn", d -> d.format("yyyy-MM-dd"))
                            .numberField("Cost", n -> n.fractionDigits(0))
                            // The non-sortable leaders the family gate has to answer for, plus a nested group
                            // so a GROUP operand — filtered and plain — has a shape to name.
                            .field("Passed", FieldKind.BOOLEAN)
                            .field("Signed", FieldKind.CONFIRM)
                            .group("Parts", parts -> parts
                                    .stringField("PartCode", s -> { })
                                    .numberField("PartCost", n -> n.fractionDigits(0)))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(POLICY);

    /** The filter: a plain per-row presence test, the simplest shape the filter grammar admits. */
    private static Condition rowFilter() {
        return Dsl.fieldFilled(M.field("/Policy/Visits/Outcome"));
    }

    private static FieldListRef visitDates() {
        return M.flatten("/Policy/Visits*/VisitOn");
    }

    private static FieldListRef visitCosts() {
        return M.flatten("/Policy/Visits*/Cost");
    }

    private static String verdict(Condition aggregate) {
        String condition = new DslRenderer()
                .render(Dsl.fieldFilled(M.field("/Policy/Reference")).and(aggregate));
        ValidationResult r = KernelLaws.checkModel(POLICY, GROUP, ERROR_REL, condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /**
     * The carrier sweep. The plain-star rows are the controls without which the filtered rows say nothing, and
     * the numeric row is what separates a filtered-star ban from a temporal-carrier one.
     */
    @Test
    void aFilteredStarIsAnAdmittedExtremumOperandOnBothCarriers() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("temporal extremum, PLAIN star — control",
                verdict(Dsl.latestOf(visitDates()).eq(M.date(REVIEWED))));
        actual.put("temporal extremum, FILTERED star",
                verdict(Dsl.latestOf(visitDates().having(rowFilter())).eq(M.date(REVIEWED))));
        actual.put("numeric extremum, PLAIN star — control",
                verdict(Dsl.maxValue(visitCosts()).gt(100)));
        actual.put("numeric extremum, FILTERED star",
                verdict(Dsl.maxValue(visitCosts().having(rowFilter())).gt(100)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("temporal extremum, PLAIN star — control", KernelLaws.ADMITTED);
        expected.put("temporal extremum, FILTERED star", KernelLaws.ADMITTED);
        expected.put("numeric extremum, PLAIN star — control", KernelLaws.ADMITTED);
        expected.put("numeric extremum, FILTERED star", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "a filter does not change whether a star is an admitted extremum operand");
    }

    /**
     * The component-set gate measured THROUGH the filter, rather than inferred from the plain-star rows.
     *
     * <p>The differing-set row is what proves the gate still runs: without it, four admissions above are
     * equally well explained by "a filtered operand is not gate-checked at all", which would be a far worse
     * outcome than a refusal. The same-set-different-spelling row then shows the gate reads component sets
     * here exactly as {@link TemporalAggregateComponentSetLawsTest} measures it without a filter.
     *
     * <p><b>The second date must sit in the OPERAND LIST, and a first draft of this file put it on the
     * comparison instead — where every row came back ADMITTED.</b> That is not a bug in the gate: an aggregate
     * compared to a date is a DIRECT comparison, which KERNEL-SEMANTICS §6 measures against the *coarse*
     * gate (matching year presence and date/no-date class), while the exact component-set gate reads the
     * aggregate's own operand list. Putting {@code CoverFrom} on the right-hand side therefore measured the
     * wrong gate and would have published "the filter exempts the operand" as a finding. The plain-star
     * control is what caught it, by being ADMITTED where it had no business being — the reason a control
     * earns its place is precisely that it fails when the row's *design* is wrong, not only when the kernel is.
     */
    @Test
    void theTemporalComponentGateReadsThroughTheFilter() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("filtered star beside a DIFFERING component set",
                verdict(Dsl.latestOf(visitDates().having(rowFilter()), M.field(COVER_FROM))
                        .eq(M.date(REVIEWED))));
        actual.put("plain star beside the same differing set — control",
                verdict(Dsl.latestOf(visitDates(), M.field(COVER_FROM)).eq(M.date(REVIEWED))));
        actual.put("filtered star beside the SAME set, other spelling",
                verdict(Dsl.latestOf(visitDates().having(rowFilter()), M.field(CLOSED_ON))
                        .eq(M.date(REVIEWED))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("filtered star beside a DIFFERING component set", INCOMPATIBLE);
        expected.put("plain star beside the same differing set — control", INCOMPATIBLE);
        expected.put("filtered star beside the SAME set, other spelling", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the filter narrows the rows; it does not exempt the operand from the gate");
    }

    /**
     * The two entity-list carriers beside the extrema, since a filtered star reaching one gate says nothing
     * about another — the scope-transfer rule this estate keeps paying for.
     */
    @Test
    void theEntityListCarriersAdmitTheSameFilteredStar() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NumberOfDifferentValues, PLAIN star — control",
                verdict(Dsl.numberOfDifferentValues(visitDates()).gt(1)));
        actual.put("NumberOfDifferentValues, FILTERED star",
                verdict(Dsl.numberOfDifferentValues(visitDates().having(rowFilter())).gt(1)));
        actual.put("FieldValuesNotUnique, PLAIN star — control",
                verdict(Dsl.fieldValuesNotUnique(visitDates())));
        actual.put("FieldValuesNotUnique, FILTERED star",
                verdict(Dsl.fieldValuesNotUnique(visitDates().having(rowFilter()))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NumberOfDifferentValues, PLAIN star — control", KernelLaws.ADMITTED);
        expected.put("NumberOfDifferentValues, FILTERED star", KernelLaws.ADMITTED);
        expected.put("FieldValuesNotUnique, PLAIN star — control", KernelLaws.ADMITTED);
        expected.put("FieldValuesNotUnique, FILTERED star", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "measured per carrier, not carried across from the extremum");
    }

    /**
     * <b>The FAMILY gate reads straight through the filter</b>, so a filtered star supplies the leading kind
     * exactly as its plain twin does — a different gate from the component-set one above, and therefore its own
     * measurement rather than a transfer.
     *
     * <p><b>The claim is the PAIRING.</b> Each filtered row is twinned with a plain row identical in every
     * other respect, so a gate reporting earlier would move both members and cannot fake agreement. Three
     * distinct codes appear, which rules out a blanket filtered-operand refusal, and the admitted numeric row
     * keeps the grid from being constant.
     *
     * <p>Held fixed: the operator, the arity, the comparand, the locale, the filter condition, the operand
     * order, the group, and the error field. Varied: filtered against plain, and the leading kind.
     */
    @Test
    void aFilteredStarSuppliesTheLeadingKindJustAsItsPlainTwinDoes() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        record Leader(String path, String label, String code) { }
        List<Leader> leaders = List.of(
                new Leader("/Policy/Visits*/Outcome", "STRING", NOT_SORTABLE),
                new Leader("/Policy/Visits*/Passed", "BOOLEAN", NOT_SORTABLE),
                new Leader("/Policy/Visits*/Signed", "CONFIRM", NOT_SORTABLE),
                new Leader("/Policy/Visits*/VisitOn", "DATE", "MVK_DATE_AND_NONDATE"));

        // Raw DSL throughout: `ListAggregate` refuses a non-sortable leader client-side, correctly and on a
        // measured basis (these very rows), so the typed surface cannot express what is under test here.
        for (Leader leader : leaders) {
            actual.put(leader.label() + " leader, PLAIN", verdictRaw(
                    "MinValue(" + leader.path() + ", /Policy/Visits*/Cost) < 100"));
            expected.put(leader.label() + " leader, PLAIN", leader.code());
            actual.put(leader.label() + " leader, FILTERED", verdictRaw(
                    "MinValue(" + leader.path() + " Having (FieldFilled(/Policy/Visits/Outcome)),"
                            + " /Policy/Visits*/Cost) < 100"));
            expected.put(leader.label() + " leader, FILTERED", leader.code());
        }
        // Liveness: the same shape with a sortable leader is admitted, filtered and plain alike.
        actual.put("NUMBER leader, PLAIN — liveness", verdict(Dsl.minValue(visitCosts()).lt(100)));
        expected.put("NUMBER leader, PLAIN — liveness", KernelLaws.ADMITTED);
        actual.put("NUMBER leader, FILTERED — liveness",
                verdict(Dsl.minValue(visitCosts().having(rowFilter())).lt(100)));
        expected.put("NUMBER leader, FILTERED — liveness", KernelLaws.ADMITTED);

        assertEquals(expected, actual, "the filter selects rows; the family gate reads the declaration");
    }

    /**
     * <b>…and the two filtered forms do NOT agree.</b> On a starred FIELD the filter is transparent (above);
     * on a GROUP the filtered operand is refused {@code MVK_NO_GROUPS_ALLOWED} outright, where its plain twin
     * reaches the kind gate and reports that instead.
     *
     * <p><b>The control is what makes the claim group-ness rather than the filter.</b> A filtered starred FIELD
     * inside that same subgroup, carrying the identical filter, is admitted — which rules out the filter's
     * spelling, the operand's depth, and the filter's scope, and leaves group-ness as the only difference.
     * These rows use raw DSL because a filtered group operand is <b>unrepresentable</b> in the typed surface:
     * {@link io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef} carries no filter at all, which is a claim
     * about our own types discharged by reading the record rather than by the kernel happening to agree.
     *
     * <p>No mechanism is proposed for the group half. {@code MVK_NO_GROUPS_ALLOWED} is the code the carriers
     * that refuse groups outright report, which invites reading the filtered operand as routed somewhere a
     * group is not a legal shape at all — nothing measured here isolates that, so it stays a hypothesis.
     */
    @Test
    void aFilteredGroupOperandIsRefusedWhereItsPlainTwinReachesTheKindGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("group operand, PLAIN", verdictRaw(
                "MinValue(/Policy/Visits*/Parts, /Policy/Visits*/Cost) < 100"));
        actual.put("group operand, FILTERED", verdictRaw(
                "MinValue(/Policy/Visits*/Parts Having (FieldFilled(/Policy/Visits/Outcome)),"
                        + " /Policy/Visits*/Cost) < 100"));
        actual.put("a filtered starred FIELD in that same subgroup — the control", verdictRaw(
                "MinValue(/Policy/Visits*/Parts/PartCost Having (FieldFilled(/Policy/Visits/Outcome))) < 100"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("group operand, PLAIN", NOT_SORTABLE);
        expected.put("group operand, FILTERED", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a filtered starred FIELD in that same subgroup — the control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "one rule cannot serve both filtered forms");
    }

    /** A raw-DSL verdict, for the shapes the typed surface cannot express. */
    private static String verdictRaw(String aggregate) {
        ValidationResult r = KernelLaws.checkModel(POLICY, GROUP, ERROR_REL,
                "FieldFilled(/Policy/Reference) And " + aggregate);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }
}
