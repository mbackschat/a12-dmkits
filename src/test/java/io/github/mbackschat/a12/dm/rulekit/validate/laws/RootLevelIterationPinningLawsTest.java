package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §9 — which iterating read draws the ROOT-LEVEL class rather than one of its two siblings.
 *
 * <p>{@link RootOnlyErrorTargetIterationLawsTest} locks the two classes {@code IterationChecker} draws when a
 * rule reaches its error field only through a root-group reference. Its loop deliberately skips level 0 —
 * {@code "omit the level == 0, as this is the L-Iteration"} — and a third class,
 * {@code MVK_ERROR_FIELD_AND_ITERATION_INVALID}, answers there instead, from {@code checkVRefs}. It has two
 * arms: a plain iteration of the outermost repeating level, and a PARALLEL iteration when the rule's own
 * index field sits at that level.
 *
 * <p><b>No spelling measured here reaches either arm</b>, and the three ways they fail are the finding:
 * starring the root leaves the repeating group beneath it bare ({@code MVK_NO_WILDCARD}), the scalar group
 * predicates refuse a star outright ({@code MVK_NO_WILDCARDS_ALLOWED}), and carrying the star all the way
 * down is simply ADMITTED. This is seven attempts and not a proof of unreachability — the class stays
 * kernel-delegated with its attempts recorded, rather than being claimed unauthorable.
 *
 * <p><b>What the grid did find is a PRE-EMPTION the sibling classes' own family was missing.</b> An index
 * field on the ROOT turns the parallel-group read into a parse-time {@code MVK_MULTIPLE_INDEX_FIELDS}, and a
 * parse-time class ends the parse — so {@code MVK_ERROR_FIELDGROUP_ITERATION5_INVALID} never runs there. The
 * two models below differ in that one declaration and nothing else, which is what makes the row a
 * measurement of the index field rather than of the fixture.
 *
 * <p>Own domain (an insurance claims register).
 */
class RootLevelIterationPinningLawsTest {

    /** The root REPEATS and carries its own index field, so the parallel arm's precondition can be met. */
    private static final String KEYED_ROOT = DocumentModelDraft.named("register-keyed-root").locale("en_US")
            .rootGroup("Register", root -> root.repeatable(5).indexField("CaseNo")
                    .stringField("CaseNo", f -> { })
                    .stringField("Reference", f -> { })
                    .group("Received", rows -> rows.repeatable(10).indexField("LineNo")
                            .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })
                            .stringField("Note", f -> { }))
                    .group("Expected", rows -> rows.repeatable(10).indexField("LineNo")
                            .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })))
            .build().toJson();

    /** The same shape with NO index field on the root, so the parallel arm's precondition is not met. */
    private static final String PLAIN_ROOT = DocumentModelDraft.named("register-plain-root").locale("en_US")
            .rootGroup("Register", root -> root.repeatable(5)
                    .stringField("CaseNo", f -> { })
                    .stringField("Reference", f -> { })
                    .group("Received", rows -> rows.repeatable(10).indexField("LineNo")
                            .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })
                            .stringField("Note", f -> { }))
                    .group("Expected", rows -> rows.repeatable(10).indexField("LineNo")
                            .stringField("LineNo", f -> { }).numberField("Quantity", f -> { })))
            .build().toJson();

    private static String keyed(String condition) {
        return KernelLaws.verdictModel(KEYED_ROOT, "/Register/Received", "../Note", condition);
    }

    private static String plain(String condition) {
        return KernelLaws.verdictModel(PLAIN_ROOT, "/Register/Received", "../Note", condition);
    }

    @Test
    void whichIteratingReadReachesTheRootLevelClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("keyed root | the root reference alone", keyed("GroupFilled(/Register)"));
        actual.put("keyed root | a read in the error field's own group",
                keyed("GroupFilled(/Register) And [Quantity] > 0"));
        actual.put("keyed root | a read in the parallel group",
                keyed("GroupFilled(/Register) And [/Register/Expected/Quantity] > 0"));
        actual.put("keyed root | a STARRED read of the root's own level",
                keyed("GroupFilled(/Register) And Sum(/Register*/Expected/Quantity) > 0"));
        actual.put("keyed root | a STARRED read of the root reaching the error field's group",
                keyed("GroupFilled(/Register) And Sum(/Register*/Received/Quantity) > 0"));
        actual.put("keyed root | the root reference itself STARRED, beside an own-group read",
                keyed("GroupFilled(/Register*) And [Quantity] > 0"));
        actual.put("keyed root | the root reference itself STARRED, alone", keyed("GroupFilled(/Register*)"));
        actual.put("keyed root | a read of the root's own field", keyed("GroupFilled(/Register) And FieldFilled(/Register/Reference)"));
        actual.put("plain root | the root reference alone", plain("GroupFilled(/Register)"));
        actual.put("plain root | a read in the error field's own group",
                plain("GroupFilled(/Register) And [Quantity] > 0"));
        actual.put("plain root | a read in the parallel group",
                plain("GroupFilled(/Register) And [/Register/Expected/Quantity] > 0"));
        actual.put("plain root | a STARRED read of the root's own level",
                plain("GroupFilled(/Register) And Sum(/Register*/Expected/Quantity) > 0"));
        actual.put("plain root | the root reference itself STARRED, beside an own-group read",
                plain("GroupFilled(/Register*) And [Quantity] > 0"));
        actual.put("plain root | a read of the root's own field",
                plain("GroupFilled(/Register) And FieldFilled(/Register/Reference)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("keyed root | the root reference alone", KernelLaws.ADMITTED);
        expected.put("keyed root | a read in the error field's own group",
                "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID");
        // THE ROW THAT MATTERS: an index field on the ROOT makes the same parallel read a PARSE-time
        // defect, and a parse-time class ends the parse — so the sibling class below never runs. A checker
        // that reads the two grids' shape alone names ITERATION5 here, which is the wrong class on a model
        // A12 correctly refuses.
        expected.put("keyed root | a read in the parallel group", "MVK_MULTIPLE_INDEX_FIELDS");
        // Every attempt to ITERATE the root's own level is refused by a wildcard class first: starring the
        // root leaves the group below it unstarred (`MVK_NO_WILDCARD`), and the group predicates refuse a
        // star outright. Both are parse-time, so `MVK_ERROR_FIELD_AND_ITERATION_INVALID` — the class
        // `checkVRefs` draws at level 0 — is not reachable through any of these spellings.
        expected.put("keyed root | a STARRED read of the root's own level", "MVK_NO_WILDCARD");
        expected.put("keyed root | a STARRED read of the root reaching the error field's group",
                "MVK_NO_WILDCARD");
        expected.put("keyed root | the root reference itself STARRED, beside an own-group read",
                "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("keyed root | the root reference itself STARRED, alone", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("keyed root | a read of the root's own field", KernelLaws.ADMITTED);
        expected.put("plain root | the root reference alone", KernelLaws.ADMITTED);
        expected.put("plain root | a read in the error field's own group",
                "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID");
        // …and the control that makes the keyed row a measurement of the INDEX FIELD rather than of the
        // fixture: the two models differ in that one declaration and nothing else.
        expected.put("plain root | a read in the parallel group", "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID");
        expected.put("plain root | a STARRED read of the root's own level", "MVK_NO_WILDCARD");
        expected.put("plain root | the root reference itself STARRED, beside an own-group read",
                "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("plain root | a read of the root's own field", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which iterating read reaches the root-level class");
    }

    /**
     * Whether any WELL-STARRED spelling reaches the root-level class the two grids' loop skips.
     *
     * <p>The rows above star the root and leave a repeating group beneath it bare, which is its own defect.
     * These carry the star all the way down, which is the only spelling left that could iterate level 0.
     */
    @Test
    void whetherAFullyStarredReadReachesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("keyed root | fully starred read of the parallel group",
                keyed("GroupFilled(/Register) And Sum(/Register*/Expected*/Quantity) > 0"));
        actual.put("plain root | fully starred read of the parallel group",
                plain("GroupFilled(/Register) And Sum(/Register*/Expected*/Quantity) > 0"));
        actual.put("plain root | fully starred read of the error field's own group",
                plain("GroupFilled(/Register) And Sum(/Register*/Received*/Quantity) > 0"));
        actual.put("plain root | a starred root in a group LIST predicate",
                plain("AtLeastOneGroupFilled(/Register*) And [Quantity] > 0"));
        actual.put("plain root | a relative fully starred read, the root left bound",
                plain("GroupFilled(/Register) And Sum(../Expected*/Quantity) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Carrying the star all the way down ADMITS the rule, on both models and into either group — so the
        // spelling that actually iterates the root's own level is legal rather than a defect, and the class
        // `checkVRefs` guards at level 0 is not what it costs.
        expected.put("keyed root | fully starred read of the parallel group", KernelLaws.ADMITTED);
        expected.put("plain root | fully starred read of the parallel group", KernelLaws.ADMITTED);
        expected.put("plain root | fully starred read of the error field's own group", KernelLaws.ADMITTED);
        // A starred ROOT is accepted by a group LIST predicate where the scalar one refuses it, and the
        // class that answers is still the level-1 sibling drawn by the unstarred own-group read beside it.
        expected.put("plain root | a starred root in a group LIST predicate",
                "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID");
        expected.put("plain root | a relative fully starred read, the root left bound", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "whether a fully starred read reaches it");
    }
}
