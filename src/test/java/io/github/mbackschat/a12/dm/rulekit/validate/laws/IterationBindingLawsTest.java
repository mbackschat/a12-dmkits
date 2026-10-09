package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * Which references BIND a rule's iteration, and what A12 then asks of the error field (a12-kernel-lean
 * SPEC-2026-10-09-05 and -06). Every rule here sits at the fixed root {@code /Policy} and reports on a field of
 * the repeatable {@code Claims}, so only the condition decides whether the rule iterates; each one is also
 * measured as the common precondition of a computation into {@code Claims/Score}, which has no error field.
 *
 * <p><b>Membership is not binding.</b> {@code IterationChecker.checkIteration4NotOnlyVRefs} walks the error
 * field's repetition levels and, wherever some reference records {@code ITERATION}, requires one naming the error
 * field (the field itself or a containing non-root group) to record it too. A starred reference records
 * {@code WILDCARD}, so {@code Sum(Claims*\/Amount)} NAMES the error field {@code Claims/Amount} without binding
 * anything — harmless alone, and refused with {@code MVK_ERROR_FIELD_AND_ITERATION_INVALID} as soon as a sibling
 * per-row read such as {@code FieldFilled(Claims/Reserve)} makes the rule iterate. Reading the error field itself
 * per row admits it again. A computation carries no error field and admits all three.
 *
 * <p><b>The negative-iteration gate then counts slots.</b> Once a rule iterates, {@code IterationNotValidVisitor}
 * weighs the fill quantifiers' list operands: an ordinary per-row read is an independent slot, a starred field a
 * slot per declared repetition, a fixed-root field one more; a {@code $}-marked filter read binds the iteration
 * without making the starred list a per-row read, and an unmarked filter read binds nothing at all. The
 * threshold is per operator — one independent slot for {@code AtLeastOneFieldFilled} and
 * {@code FieldsNotCollectivelyFilled}, two for {@code MoreThanOneFieldFilled} and {@code FieldValuesNotUnique},
 * none at all for the three negatives, which a guard on the bound row rescues.
 *
 * <p><b>Typed-surface boundary.</b> The subject of both grids is reference SPELLING — starred, per-row, marked or
 * unmarked inside a filter — which is the variable under test, so the rows are condition text.
 *
 * <p>Own domain (an insurance policy's claims), transferring the peer's {@code Probe_DM} topology field for field.
 */
class IterationBindingLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-iteration-binding").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Holder", f -> { })
                    .numberField("Premium", n -> n.fractionDigits(0))
                    .group("Claims", c -> c.repeatable(3)
                            .group("Detail", d -> d.numberField("Units", n -> n.fractionDigits(0)))
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .dateField("FiledOn", f -> f.format("yyyy-MM-dd"))
                            .stringField("Note", f -> { })
                            .numberField("Reserve", n -> n.fractionDigits(2))
                            .dateRangeField("CoverSpan", r -> r.format("yyyy-MM-dd").separator("/"))
                            .group("Windows", w -> w
                                    .dateRangeField("Window", r -> r.format("yyyy-MM-dd").separator("/")))
                            .numberField("Score", n -> n.fractionDigits(0))))
            .build().toJson();

    private static final String BINDS = "MVK_ERROR_FIELD_AND_ITERATION_INVALID";
    private static final String NEGATIVE = "MVK_NEG_CONDITION_IN_ITERATION";

    /** The marked filter: its {@code $} read of the claim's own detail binds the rule to the claim. */
    private static final String MARKED = "Claims*/Amount Having (FieldFilled($Claims/Detail/Units) And "
            + "CurrentRepetition(Claims) > 0)";
    /** The same filter unmarked: the detail is read per CANDIDATE, which binds nothing outside the filter. */
    private static final String CANDIDATE = "Claims*/Amount Having (FieldFilled(Claims/Detail/Units) And "
            + "CurrentRepetition(Claims) > 0)";
    /** A filter that keeps every claim while the policy is filled — a marked read of the fixed root. */
    private static final String ROOT_MARKED = " Having (GroupFilled($/Policy) And CurrentRepetition(Claims) > 0)";

    /** The rule verdict and its computation twin, as {@code rule | computation}. */
    private static String both(String errorField, String condition) {
        String rule = KernelLaws.verdictModel(POLICY, "/Policy", "../" + errorField, condition);
        String computation = KernelLaws.computationVerdict(POLICY, new DraftComputation("/Policy", "ScoreProbe",
                "../Claims/Score", condition, List.of(new DraftComputation.Alternative(null, "1"))));
        return rule + " | " + computation;
    }

    private static String admitted() {
        return KernelLaws.ADMITTED + " | " + KernelLaws.ADMITTED;
    }

    private static String refused(String code) {
        return code + " | " + KernelLaws.ADMITTED;
    }

    /**
     * Ten list callers, each plain, behind a SIBLING's per-row guard and behind the error field's OWN. Only the
     * sibling guard refuses — and the plural overlap refuses plain too, because its scalar operand
     * {@code Claims/CoverSpan} is already a per-row read the starred group {@code Claims*\/Windows} does not bind.
     */
    @Test
    void aStarredReadNamesTheErrorFieldWithoutBindingTheIteration() {
        record Caller(String name, String errorField, String ownGuard, String list) { }
        List<Caller> callers = List.of(
                new Caller("Sum", "Claims/Amount", "Claims/Amount", "Sum(Claims*/Amount) > 0"),
                new Caller("filled count", "Claims/Amount", "Claims/Amount", "NumberOfFilledFields(Claims*/Amount) > 0"),
                new Caller("distinct count", "Claims/Note", "Claims/Note", "NumberOfDifferentValues(Claims*/Note) > 0"),
                new Caller("first filled", "Claims/Note", "Claims/Note", "FirstFilledValue(Claims*/Note) == \"a\""),
                new Caller("date extremum", "Claims/FiledOn", "Claims/FiledOn",
                        "MinValue(Claims*/FiledOn) == \"01.01.2024\""),
                new Caller("field fill", "Claims/Note", "Claims/Note", "AtLeastOneFieldFilled(Claims*/Note)"),
                new Caller("uniqueness", "Claims/Note", "Claims/Note", "FieldValuesNotUnique(Claims*/Note)"),
                new Caller("range overlap", "Claims/CoverSpan", "Claims/CoverSpan", "DateRangesOverlap(Claims*/CoverSpan)"),
                new Caller("group Sum", "Claims/Detail/Units", "Claims/Detail/Units", "Sum(Claims*/Detail) > 0"),
                new Caller("group overlap", "Claims/Windows/Window", "Claims/Windows/Window",
                        "AtLeastOneDateRangeOverlaps(Claims/CoverSpan In Claims*/Windows)"));
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Caller c : callers) {
            actual.put(c.name() + ", plain", both(c.errorField(), c.list()));
            actual.put(c.name() + ", sibling guard", both(c.errorField(), "FieldFilled(Claims/Reserve) And " + c.list()));
            actual.put(c.name() + ", own guard",
                    both(c.errorField(), "FieldFilled(" + c.ownGuard() + ") And " + c.list()));
            expected.put(c.name() + ", plain", c.name().equals("group overlap") ? refused(BINDS) : admitted());
            expected.put(c.name() + ", sibling guard", refused(BINDS));
            expected.put(c.name() + ", own guard", admitted());
        }
        assertEquals(expected, actual);
    }

    /**
     * A marked read of the fixed ROOT inside the list's filter: the root contains the error field, but as a
     * root group it is not one of the references the per-level check asks — so the sibling guard still refuses
     * and the own guard still admits.
     */
    @Test
    void aMarkedRootReadDoesNotBindTheErrorField() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Sum, sibling", rule("Claims/Amount",
                "FieldFilled(Claims/Reserve) And Sum(Claims*/Amount" + ROOT_MARKED + ") > 0"));
        actual.put("Sum, own", rule("Claims/Amount",
                "FieldFilled(Claims/Amount) And Sum(Claims*/Amount" + ROOT_MARKED + ") > 0"));
        actual.put("first filled, sibling", rule("Claims/Note",
                "FieldFilled(Claims/Reserve) And FirstFilledValue(Claims*/Note" + ROOT_MARKED + ") == \"a\""));
        actual.put("first filled, own", rule("Claims/Note",
                "FieldFilled(Claims/Note) And FirstFilledValue(Claims*/Note" + ROOT_MARKED + ") == \"a\""));
        actual.put("date extremum, sibling", rule("Claims/FiledOn",
                "FieldFilled(Claims/Reserve) And MinValue(Claims*/FiledOn" + ROOT_MARKED + ") == \"01.01.2024\""));
        actual.put("date extremum, own", rule("Claims/FiledOn",
                "FieldFilled(Claims/FiledOn) And MinValue(Claims*/FiledOn" + ROOT_MARKED + ") == \"01.01.2024\""));
        actual.put("range overlap, sibling", rule("Claims/CoverSpan",
                "FieldFilled(Claims/Reserve) And DateRangesOverlap(Claims*/CoverSpan" + ROOT_MARKED + ")"));
        actual.put("range overlap, own", rule("Claims/CoverSpan",
                "FieldFilled(Claims/CoverSpan) And DateRangesOverlap(Claims*/CoverSpan" + ROOT_MARKED + ")"));
        Map<String, String> expected = new LinkedHashMap<>();
        actual.keySet().forEach(k -> expected.put(k, k.endsWith("sibling") ? BINDS : KernelLaws.ADMITTED));
        assertEquals(expected, actual);
    }

    private static String rule(String errorField, String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../" + errorField, condition);
    }

    /**
     * Seven fill quantifiers and uniqueness over seven operand shapes. The computation twin admits every row;
     * the rule's verdict is the slot count against the operator's threshold, with the marked filter refused only
     * where one wildcard slot is already too many, and rescued by a guard on the bound row.
     */
    @Test
    void theIterationGuardCountsIndependentSlots() {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("rowPair", "Claims/Amount, Claims/Reserve");
        shapes.put("mixedPair", "Claims/Amount, Premium");
        shapes.put("starAndRow", "Claims*/Amount, Claims/Reserve");
        shapes.put("marked", MARKED);
        shapes.put("guard", MARKED);
        shapes.put("candidate", CANDIDATE);
        shapes.put("plain", "Claims*/Amount");
        Map<String, String> errorFieldOf = Map.of("rowPair", "Claims/Amount", "mixedPair", "Claims/Amount",
                "starAndRow", "Claims/Reserve", "marked", "Claims/Detail/Units", "guard", "Claims/Detail/Units",
                "candidate", "Claims/Amount", "plain", "Claims/Amount");

        // Which shapes each operator refuses; every other shape, and every computation, admits.
        Map<String, List<String>> refusedShapes = new LinkedHashMap<>();
        refusedShapes.put("AllFieldsFilled", List.of());
        refusedShapes.put("NoFieldFilled", List.of("rowPair", "mixedPair", "starAndRow"));
        refusedShapes.put("AtLeastOneFieldFilled", List.of("mixedPair", "starAndRow", "marked"));
        refusedShapes.put("MoreThanOneFieldFilled", List.of("starAndRow", "marked"));
        refusedShapes.put("NotAllFieldsFilled", List.of("rowPair", "mixedPair", "starAndRow"));
        refusedShapes.put("NotExactlyOneFieldFilled", List.of("rowPair", "mixedPair", "starAndRow"));
        refusedShapes.put("FieldsNotCollectivelyFilled", List.of("mixedPair", "starAndRow", "marked"));
        refusedShapes.put("FieldValuesNotUnique", List.of("starAndRow", "marked"));

        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        refusedShapes.forEach((operator, refusing) -> shapes.forEach((shape, operands) -> {
            String call = operator + "(" + operands + ")";
            String condition = shape.equals("guard") ? "FieldFilled(Claims/Detail/Units) And " + call : call;
            actual.put(operator + " " + shape, both(errorFieldOf.get(shape), condition));
            expected.put(operator + " " + shape, refusing.contains(shape) ? refused(NEGATIVE) : admitted());
        }));
        assertEquals(expected, actual);
    }
}
