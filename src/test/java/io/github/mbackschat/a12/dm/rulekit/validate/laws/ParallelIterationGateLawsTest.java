package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — which whole-RULE gate answers when a condition PARALLEL-iterates.
 *
 * <p>Two index-keyed repeatable groups iterate jointly with no construct to say so, and A12 then runs four
 * whole-rule gates over the finished tree. Their order is the claim here: a containment assertion on any one
 * of them passes whether or not a second fired, and three of the four had never been measured together.
 *
 * <p>Uses the logistics draft, whose {@code Lines} and {@code Picks} share the index field {@code LineRef} —
 * the only shape in this estate that produces a parallel iteration at all.
 */
class ParallelIterationGateLawsTest {

    private static final String LOGISTICS = ModelDrafts.logistics().toJson();

    private static final String NOT_ALLOWED = "MVK_PARALLEL_ITERATION_NOT_ALLOWED";

    private static String inLines(String condition) {
        return KernelLaws.verdictModel(LOGISTICS, "/Shipment/Lines", "../OrderedQty", condition);
    }

    /**
     * The rule one level up, with its error field on the uniqueness KEY.
     *
     * <p>Targeting the key is what holds the error-field reference gate constant across these rows: every
     * condition here calls {@code RepetitionNotUnique(Lines/LineRef)} and so names it, where targeting
     * {@code OrderedQty} left the starred row measuring {@code MVK_ERROR_FIELD_NOT_REFERENCED} instead of
     * the gate it is about.
     */
    private static String inShipment(String condition) {
        return KernelLaws.verdictModel(LOGISTICS, "/Shipment", "../Lines/LineRef", condition);
    }

    @Test
    void whichGateAnswersOnAParallelIteratingCondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a parallel iteration alone — the control",
                inLines("FieldsNotCollectivelyFilled(/Shipment/Lines/OrderedQty, /Shipment/Picks/PickedQty)"));
        actual.put("a pure NEGATIVE over both groups",
                inLines("NoFieldFilled(/Shipment/Lines/OrderedQty, /Shipment/Picks/PickedQty)"));
        actual.put("RepetitionNotUnique beside a cross-group read",
                inShipment("RepetitionNotUnique(Lines/LineRef) And FieldFilled(/Shipment/Picks/PickedQty)"));
        actual.put("RepetitionNotUnique beside a pure negative over both groups",
                inShipment("RepetitionNotUnique(Lines/LineRef) "
                        + "And NoFieldFilled(/Shipment/Lines/OrderedQty, /Shipment/Picks/PickedQty)"));
        actual.put("RepetitionNotUnique beside a STARRED read of the other group",
                inShipment("RepetitionNotUnique(Lines/LineRef) And Sum(/Shipment/Picks*/PickedQty) > 0"));
        actual.put("a FILTER on an unstarred cross-group operand",
                inLines("Sum(/Shipment/Picks/PickedQty Having [/Shipment/Picks/LineRef] == \"x\") > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a parallel iteration alone — the control", KernelLaws.ADMITTED);
        expected.put("a pure NEGATIVE over both groups", "MVK_PARALLEL_ITERATION_INVALID");
        expected.put("RepetitionNotUnique beside a cross-group read", NOT_ALLOWED);
        // The precedence row: both gates are violated and the uniqueness one is checked first, so the
        // negative-condition class is pre-empted rather than co-reported.
        expected.put("RepetitionNotUnique beside a pure negative over both groups", NOT_ALLOWED);
        // A star puts a WILDCARD at that level, not the parallel-iteration marker, so the second group is
        // read as a list rather than iterated jointly — the row that keeps a checker from reporting on
        // every condition that happens to name two index-keyed groups.
        expected.put("RepetitionNotUnique beside a STARRED read of the other group", KernelLaws.ADMITTED);
        // The aggregate's own arity gate comes first: an unstarred operand is refused before any
        // parallel-iteration gate is reached, which is why the filtered-entity class needs a nested model.
        expected.put("a FILTER on an unstarred cross-group operand", "MVK_WILDCARDS_REQUIRED");
        assertEquals(expected, actual, "which gate answers on a parallel-iterating condition");
    }

    /**
     * The two filter-position gates, which need a repetition INSIDE a parallel-iterated group.
     *
     * <p>A star puts a wildcard at its own level, so the parallel-iterated level has to be a different,
     * outer one — the logistics draft is flat and cannot express that, which is why these rows carry their
     * own model rather than joining the grid above.
     */
    @Test
    void whichGateAnswersWhenTheFilterSitsUnderAParallelIteration() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filter on an operand whose OUTER level is parallel-iterated",
                nested("FieldFilled(OrderedQty) And Sum(/Depot/Picks/Checks*/CheckQty "
                        + "Having [/Depot/Picks/Checks/CheckNote] == \"x\") > 0"));
        actual.put("a filter whose own BODY reads the parallel group",
                nested("FieldFilled(OrderedQty) And Sum(/Depot/Lines/Details*/DetailQty "
                        + "Having [/Depot/Picks/PickedQty] > 0) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a filter on an operand whose OUTER level is parallel-iterated",
                "MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED");
        // NOT the parallel-iteration class: a filter body may not ITERATE at all, and that broader gate
        // answers first. So `MVK_PARALLEL_ITERATION_NOT_ALLOWED`'s second firing site — a filter condition
        // that parallel-iterates — is not reached by this shape. Whether any shape reaches it is not
        // claimed here; one attempt is not an unreachability proof.
        expected.put("a filter whose own BODY reads the parallel group",
                "MVK_INVALID_ITERATION_IN_FILTER_CONDITION");
        assertEquals(expected, actual, "which gate answers when the filter sits under a parallel iteration");
    }

    private static final String DEPOT = DocumentModelDraft.named("depot-parallel").locale("en_US")
            .rootGroup("Depot", d -> d
                    .field("DepotRef", FieldKind.STRING)
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
                                    .field("CheckQty", FieldKind.NUMBER))))
            .build().toJson();

    /**
     * Whether the two index fields must share a TYPE for the groups to iterate jointly.
     *
     * <p>The record says "same-name/type index field" and every measured row here has two STRING keys, so
     * the type half is a constant of the set rather than something any of them tested. It decides what a
     * client-side check may key on: if the NAME alone made the iteration, a checker could read names only.
     */
    @Test
    void whetherTheIndexFieldsTypeIsPartOfWhatMakesTheIterationParallel() {
        String mismatched = DocumentModelDraft.named("depot-mixed-keys").locale("en_US")
                .rootGroup("Depot", d -> d
                        .field("DepotRef", FieldKind.STRING)
                        .group("Lines", g -> g.repeatable(50).indexField("LineRef")
                                .field("LineRef", FieldKind.STRING)
                                .field("OrderedQty", FieldKind.NUMBER))
                        .group("Picks", g -> g.repeatable(50).indexField("LineRef")
                                .field("LineRef", FieldKind.NUMBER)
                                .field("PickedQty", FieldKind.NUMBER)))
                .build().toJson();

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("RepetitionNotUnique beside a cross-group read, keys of DIFFERENT type",
                KernelLaws.verdictModel(mismatched, "/Depot", "../Lines/LineRef",
                        "RepetitionNotUnique(Lines/LineRef) And FieldFilled(/Depot/Picks/PickedQty)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // The type half is load-bearing, and it does not merely switch the joint iteration off: a DIFFERENT
        // class answers, `MVK_ERROR_FIELD_INVALID_INDEX_TYPE`, ahead of the uniqueness gate. So a checker
        // keying on the index field's NAME alone would name the parallel class where A12 names this one —
        // a wrong class on a correct rejection, which is the direction this estate has already shipped once.
        expected.put("RepetitionNotUnique beside a cross-group read, keys of DIFFERENT type",
                "MVK_ERROR_FIELD_INVALID_INDEX_TYPE");
        assertEquals(expected, actual, "whether the index fields' type is part of the joint iteration");
    }

    private static String nested(String condition) {
        return KernelLaws.verdictModel(DEPOT, "/Depot/Lines", "../OrderedQty", condition);
    }
}
