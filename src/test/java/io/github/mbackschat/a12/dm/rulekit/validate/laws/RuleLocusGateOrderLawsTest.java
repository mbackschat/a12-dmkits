package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — the two rule-authoring gates on an unstarred repeatable operand are <b>ordered</b>: the
 * OPERAND-LOCUS gate ({@code MVK_NO_WILDCARD}) reports before the ERROR-FIELD gate
 * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}), and the locus rule is the same on every DateRange carrier
 * (SPEC-2026-08-23-04).
 *
 * <p><b>Why the order matters to a consumer.</b> Both gates were separately documented; which one reports was
 * not. A checker that runs the error-field gate first tells a rule author to fix the error field, when what
 * the kernel objects to is the operand — the wrong half of the condition.
 *
 * <p><b>Why five carriers rather than one.</b> The locus rule was stated for the construction endpoint and
 * assumed to be shared. It is shared — the overlap predicate, stored range equality, an extracted endpoint
 * against a fixed Date, an endpoint pair, and the two-endpoint construction all behave alike — but that is a
 * result and not an assumption, and a consumer that transfers the rule between carriers inherits whichever
 * one it happened to sample.
 *
 * <p>Own domain (e-commerce seasonal promotions).
 */
class RuleLocusGateOrderLawsTest {

    private static final String ADMITTED = "ADMITTED";
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String NOT_REFERENCED = "MVK_ERROR_FIELD_NOT_REFERENCED";

    private static final String SHOP = "/Shop";
    private static final String RUNS = "/Shop/Runs";

    private static final String MODEL = DocumentModelDraft.named("promo-locus").locale("en_US")
            .rootGroup("Shop", s -> s
                    .stringField("Reference", f -> { })
                    .dateField("CheckDate", d -> d.format("yyyy-MM-dd"))
                    .dateRangeField("SeasonWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                    .dateRangeField("ClearanceWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                    .group("Runs", g -> g.repeatable(20).indexField("RunKey")
                            .field("RunKey", FieldKind.STRING)
                            .stringField("RunNote", f -> { })
                            .dateField("RunStart", d -> d.format("yyyy-MM-dd"))
                            .dateField("RunEnd", d -> d.format("yyyy-MM-dd"))
                            .dateRangeField("RunWindow", r -> r.format("yyyy-MM-dd").separator("/"))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /**
     * The ordering separator. Row one offends BOTH gates — an unstarred repeatable operand read from a
     * non-iterating locus, and an error field the condition never names — and the kernel reports the locus
     * one. Row two removes only the repeatable operand and the error-field gate then reports, which is what
     * makes row one's answer an ordering fact rather than an accident of the fixture.
     */
    @Test
    void theOperandLocusGateReportsBeforeTheErrorFieldGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("repeatable operand + unreferenced error field",
                verdict(SHOP, "../Reference", overlapWithRow()));
        actual.put("the same unreferenced error field, scalar operands only",
                verdict(SHOP, "../Reference", Dsl.fieldFilled(M.field(SHOP + "/SeasonWindow"))));
        actual.put("…and the same condition with the error field referenced — control",
                verdict(SHOP, "../Reference", Dsl.fieldFilled(M.field(SHOP + "/Reference"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("repeatable operand + unreferenced error field", NO_WILDCARD);
        expected.put("the same unreferenced error field, scalar operands only", NOT_REFERENCED);
        expected.put("…and the same condition with the error field referenced — control", ADMITTED);
        assertEquals(expected, actual, "the locus gate decides first, so the reported repair is the operand");
    }

    /**
     * Five DateRange carriers, each admitted from an ITERATING locus and refused from a non-iterating one.
     * Every row carries a presence guard on its own locus's error field, so the error-field gate is satisfied
     * and only the locus gate can speak.
     */
    @Test
    void everyDateRangeCarrierObeysTheSameLocusRule() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Map.Entry<String, Condition> carrier : carriers().entrySet()) {
            actual.put(carrier.getKey() + " — inside", inside(carrier.getValue()));
            actual.put(carrier.getKey() + " — outside", outside(carrier.getValue()));
            expected.put(carrier.getKey() + " — inside", ADMITTED);
            expected.put(carrier.getKey() + " — outside", NO_WILDCARD);
        }
        assertEquals(expected, actual, "the locus rule is shared by every DateRange carrier, measured not transferred");
    }

    /**
     * The syntactic negative-condition-in-iteration gate does NOT reach a value comparison on the iterated
     * operand. The two presence rows are the live control: on this same model and locus the gate does fire,
     * so the admitted comparisons are not a fixture that has stopped checking.
     */
    @Test
    void theNegativeIterationGateIsPresenceShapedNotPolarityShaped() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("unguarded negative PRESENCE on the iterated operand",
                verdict(RUNS, "../RunWindow", Dsl.fieldNotFilled(M.field(RUNS + "/RunWindow"))));
        actual.put("…its guarded twin", onWindow(Dsl.fieldNotFilled(M.field(RUNS + "/RunWindow"))));
        actual.put("stored range equality", onWindow(rangeEquality()));
        actual.put("stored range INEQUALITY",
                onWindow(M.dateRange(RUNS + "/RunWindow").ne(M.dateRange(SHOP + "/SeasonWindow"))));
        actual.put("extracted endpoint equality", onWindow(endpointAgainstDate()));
        actual.put("extracted endpoint INEQUALITY",
                onWindow(Dsl.startOfDateRange(M.dateRange(RUNS + "/RunWindow")).ne(M.date(SHOP + "/CheckDate"))));
        actual.put("extracted endpoint ORDER",
                onWindow(Dsl.startOfDateRange(M.dateRange(RUNS + "/RunWindow")).after(M.date(SHOP + "/CheckDate"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("unguarded negative PRESENCE on the iterated operand", "MVK_NEG_CONDITION_IN_ITERATION");
        expected.put("…its guarded twin", ADMITTED);
        expected.put("stored range equality", ADMITTED);
        expected.put("stored range INEQUALITY", ADMITTED);
        expected.put("extracted endpoint equality", ADMITTED);
        expected.put("extracted endpoint INEQUALITY", ADMITTED);
        expected.put("extracted endpoint ORDER", ADMITTED);
        assertEquals(expected, actual, "the gate's reach is presence-shaped: a negated COMPARISON is not caught");
    }

    /**
     * The whole-list overlap operator obeys the same locus rule on its bare operand, while its list-SHAPE
     * gates stay locus-independent: list length two and three behave alike, and a starred occurrence is
     * admitted from either locus.
     *
     * <p><b>The two sole-operand rows extend the ordering fact of
     * {@link #theOperandLocusGateReportsBeforeTheErrorFieldGate} to a third gate.</b> A single bare operand is
     * the multiplicity class {@code MVK_PARAMSIZE_INVALIDFN} from the iterating locus, and
     * {@code MVK_NO_WILDCARD} from the non-iterating one — so the locus gate outranks the arity gate as well,
     * and a checker reporting arity first sends the author to add an operand when what the kernel wants is a
     * different error field.
     */
    @Test
    void theWholeListOverlapOperatorObeysTheLocusRuleOnItsBareOperand() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("bare repeatable in a list of two — inside", inside(overlapWithRow()));
        actual.put("bare repeatable in a list of two — outside", outside(overlapWithRow()));
        actual.put("bare repeatable in a list of three — inside", inside(overlapOfThree()));
        actual.put("bare repeatable in a list of three — outside", outside(overlapOfThree()));
        actual.put("starred occurrence — inside", inside(starredOverlap()));
        actual.put("starred occurrence — outside", outside(starredOverlap()));
        actual.put("both spellings of one field — inside", inside(Dsl.dateRangesOverlap(
                M.dateRange(RUNS + "/RunWindow").field(), M.flatten(SHOP + "/Runs*/RunWindow"))));
        actual.put("a SOLE bare operand — inside",
                inside(Dsl.dateRangesOverlap(M.dateRange(RUNS + "/RunWindow").field())));
        actual.put("a SOLE bare operand — outside",
                outside(Dsl.dateRangesOverlap(M.dateRange(RUNS + "/RunWindow").field())));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("bare repeatable in a list of two — inside", ADMITTED);
        expected.put("bare repeatable in a list of two — outside", NO_WILDCARD);
        expected.put("bare repeatable in a list of three — inside", ADMITTED);
        expected.put("bare repeatable in a list of three — outside", NO_WILDCARD);
        expected.put("starred occurrence — inside", ADMITTED);
        expected.put("starred occurrence — outside", ADMITTED);
        expected.put("both spellings of one field — inside", ADMITTED);
        expected.put("a SOLE bare operand — inside", "MVK_PARAMSIZE_INVALIDFN");
        expected.put("a SOLE bare operand — outside", NO_WILDCARD);
        assertEquals(expected, actual,
                "list length and the star are locus-independent; the bare operand is not, and it decides first");
    }

    /** …and the distinguished-scalar operator obeys it on BOTH of its sides, not only the list side. */
    @Test
    void theDistinguishedScalarOverlapOperatorObeysTheLocusRuleOnBothSides() {
        Condition repeatableScalar = Dsl.atLeastOneDateRangeOverlaps(M.dateRange(RUNS + "/RunWindow").field(),
                M.dateRange(SHOP + "/SeasonWindow").field(), M.dateRange(SHOP + "/ClearanceWindow").field());
        Condition repeatableList = Dsl.atLeastOneDateRangeOverlaps(M.dateRange(SHOP + "/SeasonWindow").field(),
                M.dateRange(RUNS + "/RunWindow").field());

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("repeatable distinguished scalar — inside", inside(repeatableScalar));
        actual.put("repeatable distinguished scalar — outside", outside(repeatableScalar));
        actual.put("repeatable in the list — inside", inside(repeatableList));
        actual.put("repeatable in the list — outside", outside(repeatableList));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("repeatable distinguished scalar — inside", ADMITTED);
        expected.put("repeatable distinguished scalar — outside", NO_WILDCARD);
        expected.put("repeatable in the list — inside", ADMITTED);
        expected.put("repeatable in the list — outside", NO_WILDCARD);
        assertEquals(expected, actual, "neither side of the operator is exempt from the locus rule");
    }

    // ---- carriers --------------------------------------------------------------------------------------

    private static Map<String, Condition> carriers() {
        Map<String, Condition> carriers = new LinkedHashMap<>();
        carriers.put("overlap predicate", overlapWithRow());
        carriers.put("stored range equality", rangeEquality());
        carriers.put("extracted endpoint against a fixed Date", endpointAgainstDate());
        carriers.put("an endpoint pair", Dsl.startOfDateRange(M.dateRange(RUNS + "/RunWindow"))
                .ne(Dsl.endOfDateRange(M.dateRange(RUNS + "/RunWindow"))));
        carriers.put("the two-endpoint construction",
                Dsl.dateRange(M.date(RUNS + "/RunStart"), M.date(RUNS + "/RunEnd"))
                        .eq(M.dateRange(SHOP + "/SeasonWindow")));
        return carriers;
    }

    private static Condition overlapWithRow() {
        return Dsl.dateRangesOverlap(M.dateRange(RUNS + "/RunWindow").field(),
                M.dateRange(SHOP + "/SeasonWindow").field());
    }

    private static Condition overlapOfThree() {
        return Dsl.dateRangesOverlap(M.dateRange(RUNS + "/RunWindow").field(),
                M.dateRange(SHOP + "/SeasonWindow").field(), M.dateRange(SHOP + "/ClearanceWindow").field());
    }

    private static Condition starredOverlap() {
        return Dsl.dateRangesOverlap(M.flatten(SHOP + "/Runs*/RunWindow"),
                M.dateRange(SHOP + "/SeasonWindow").field());
    }

    private static Condition rangeEquality() {
        return M.dateRange(RUNS + "/RunWindow").eq(M.dateRange(SHOP + "/SeasonWindow"));
    }

    private static Condition endpointAgainstDate() {
        return Dsl.startOfDateRange(M.dateRange(RUNS + "/RunWindow")).eq(M.date(SHOP + "/CheckDate"));
    }

    // ---- loci ------------------------------------------------------------------------------------------

    /** An ITERATING locus: the error field is the row's own note, guarded so the error-field gate is met. */
    private static String inside(Condition condition) {
        return verdict(RUNS, "../RunNote", Dsl.fieldFilled(M.field(RUNS + "/RunNote")).and(condition));
    }

    /**
     * The iterating locus with the ITERATED OPERAND as the error field, so the whole negative-gate table holds
     * locus, error field and guard fixed and varies only the condition shape. The unguarded row must use this
     * error field too: with the row's note as the error field the ERROR-FIELD gate reports first and the
     * negative-iteration gate is never reached — measured, and the reason this table is written this way.
     */
    private static String onWindow(Condition condition) {
        return verdict(RUNS, "../RunWindow", Dsl.fieldFilled(M.field(RUNS + "/RunNote")).and(condition));
    }

    /** A NON-iterating locus: the error field sits on the form, guarded the same way. */
    private static String outside(Condition condition) {
        return verdict(SHOP, "../Reference", Dsl.fieldFilled(M.field(SHOP + "/Reference")).and(condition));
    }

    private static String verdict(String group, String errorField, Condition condition) {
        ValidationResult r = new RuleValidator().check(MODEL, new DraftRule(group, "LocusProbe", errorField,
                KernelLaws.render(condition), "LOCUS", "ERROR"));
        return r.ok() ? ADMITTED
                : r.diagnostics().stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }
}
