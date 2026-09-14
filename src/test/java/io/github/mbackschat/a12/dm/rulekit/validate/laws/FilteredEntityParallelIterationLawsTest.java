package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — when a {@code Having} filter rides a PARALLEL-iterated level.
 *
 * <p>{@code ParserService.checkNormalRule}'s second gate asks {@code hasParallelIteratedFilterConditionId},
 * which wants ONE entity id carrying both a filter condition and the parallel-iteration marker. The grid
 * separates that from the rival reading — any filter in a condition that parallel-iterates anywhere — which
 * no single-row measurement can tell apart.
 *
 * <p>Fulfilment domain: {@code Lines} and {@code Picks} share the index field {@code LineRef}, so a read of
 * {@code Picks} beside a rule anchored in {@code Lines} iterates jointly; {@code Audits} repeats without an
 * index field and is the unkeyed control.
 */
class FilteredEntityParallelIterationLawsTest {

    private static final String FILTERED = "MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED";

    private static final String FULFILMENT = DocumentModelDraft.named("fulfilment-parallel").locale("en_US")
            .rootGroup("Fulfilment", d -> d
                    .field("OrderRef", FieldKind.STRING)
                    .group("Lines", g -> g.repeatable(50).indexField("LineRef")
                            .field("LineRef", FieldKind.STRING)
                            .field("OrderedQty", FieldKind.NUMBER)
                            .group("Details", i -> i.repeatable(5)
                                    .field("DetailNote", FieldKind.STRING)
                                    .field("DetailQty", FieldKind.NUMBER)))
                    .group("Picks", g -> g.repeatable(50).indexField("LineRef")
                            .field("LineRef", FieldKind.STRING)
                            .field("PickedQty", FieldKind.NUMBER)
                            .group("Checks", i -> i.repeatable(5)
                                    .field("CheckNote", FieldKind.STRING)
                                    .field("CheckQty", FieldKind.NUMBER)))
                    .group("Audits", g -> g.repeatable(20)
                            .field("AuditNote", FieldKind.STRING)
                            .group("Notes", i -> i.repeatable(5)
                                    .field("NoteText", FieldKind.STRING)
                                    .field("NoteQty", FieldKind.NUMBER))))
            .build().toJson();

    private static String inLines(String condition) {
        return KernelLaws.verdictModel(FULFILMENT, "/Fulfilment/Lines", "../OrderedQty",
                "FieldFilled(OrderedQty) And " + condition);
    }

    /** The same grid one level up, where the uniqueness construct is reachable at all. */
    private static String inFulfilment(String condition) {
        return KernelLaws.verdictModel(FULFILMENT, "/Fulfilment", "../Lines/LineRef", condition);
    }

    @Test
    void whatMakesAFilteredOperandParallelIterated() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filtered operand under a keyed PARALLEL level",
                inLines("Sum(/Fulfilment/Picks/Checks*/CheckQty "
                        + "Having [/Fulfilment/Picks/Checks/CheckNote] == \"x\") > 0"));
        actual.put("the same operand with NO filter",
                inLines("Sum(/Fulfilment/Picks/Checks*/CheckQty) > 0"));
        actual.put("a filtered operand on the TARGET's own chain",
                inLines("Sum(Details*/DetailQty Having [Details/DetailNote] == \"x\") > 0"));
        actual.put("a filtered operand on the target's chain, the condition parallel-iterating ELSEWHERE",
                inLines("Sum(Details*/DetailQty Having [Details/DetailNote] == \"x\") > 0 "
                        + "And FieldFilled(/Fulfilment/Picks/PickedQty)"));
        actual.put("the parallel level declares NO index field",
                inLines("Sum(/Fulfilment/Audits/Notes*/NoteQty "
                        + "Having [/Fulfilment/Audits/Notes/NoteText] == \"x\") > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a filtered operand under a keyed PARALLEL level", FILTERED);
        // The filter is the whole variable on the off-path operand: the identical parallel read is a LEGAL
        // parallel iteration without it, so this gate adds nothing to the scope chain except the filter.
        expected.put("the same operand with NO filter", KernelLaws.ADMITTED);
        expected.put("a filtered operand on the TARGET's own chain", KernelLaws.ADMITTED);
        // The row that kills the narrow reading. `setIndexesForParallelIteration` retags EVERY unstarred
        // repeatable level that is an ancestor-or-equal of the error field and declares an index field, once
        // any off-path read has made the condition parallel-iterating — so the marker reaches the rule's own
        // group and the filter on it is refused, though nothing about the operand changed between these two
        // rows. A checker keying on "the filtered operand reads a parallel group" would admit this.
        expected.put("a filtered operand on the target's chain, the condition parallel-iterating ELSEWHERE",
                FILTERED);
        // The scope chain refuses an unkeyed parallel level during the parse, so this gate never sees it.
        expected.put("the parallel level declares NO index field", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "what makes a filtered operand parallel-iterated");
    }

    /**
     * Which of {@code checkNormalRule}'s first two gates answers when a condition violates both.
     *
     * <p>Both are post-parse and neither hides behind a positioned class, so only an explicit measurement
     * says which one a model tripping both draws.
     */
    @Test
    void whichPostParseGateAnswersFirst() {
        String filtered = "Sum(/Fulfilment/Picks/Checks*/CheckQty "
                + "Having [/Fulfilment/Picks/Checks/CheckNote] == \"x\") > 0";

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filtered parallel operand, rule at the root", inFulfilment(filtered));
        actual.put("the same beside a RepetitionNotUnique",
                inFulfilment("RepetitionNotUnique(Lines/LineRef) And " + filtered));
        actual.put("an UNFILTERED read starred below the parallel level, beside a RepetitionNotUnique",
                inFulfilment("RepetitionNotUnique(Lines/LineRef) "
                        + "And Sum(/Fulfilment/Picks/Checks*/CheckQty) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a filtered parallel operand, rule at the root", FILTERED);
        expected.put("the same beside a RepetitionNotUnique", "MVK_PARALLEL_ITERATION_NOT_ALLOWED");
        // A star marks the level it sits on and nothing above it, so a read starred at an INNER level still
        // iterates its outer one jointly. The row matters to a checker: keying the parallel iteration on
        // "the reference carries no star at all" misses exactly this shape.
        expected.put("an UNFILTERED read starred below the parallel level, beside a RepetitionNotUnique",
                "MVK_PARALLEL_ITERATION_NOT_ALLOWED");
        assertEquals(expected, actual, "which post-parse gate answers first");
    }
}
