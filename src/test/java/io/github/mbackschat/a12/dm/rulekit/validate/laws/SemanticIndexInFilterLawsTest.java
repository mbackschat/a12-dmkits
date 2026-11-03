package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — a semantic index inside a {@code Having} filter, and what outranks that gate.
 *
 * <p>{@code ParserService.checkNormalRule}'s fourth gate asks {@code AnalyseService.usesSemanticIndex} of
 * every collected filter condition, so the demand is on the filter's BODY and on nothing else. The grid
 * separates that from the three rival readings a single row cannot: the index's own position, the group it
 * addresses, and whether the key iterates.
 *
 * <p>Insurance domain: {@code Coverages} and {@code Endorsements} both key on {@code CoverageRef}, which is
 * the only shape here that produces a parallel iteration and so the only one that reaches the earlier gate.
 */
class SemanticIndexInFilterLawsTest {

    private static final String NOT_ALLOWED = "MVK_SEMANTIC_INDEX_NOT_ALLOWED";

    private static final String POLICY = DocumentModelDraft.named("policy-index-filter").locale("en_US")
            .rootGroup("Policy", d -> d
                    .field("PolicyRef", FieldKind.STRING)
                    .field("Premium", FieldKind.NUMBER)
                    .group("Coverages", g -> g.repeatable(20).indexField("CoverageRef")
                            .field("CoverageRef", FieldKind.STRING)
                            .field("Limit", FieldKind.NUMBER)
                            .field("Note", FieldKind.STRING))
                    .group("Endorsements", g -> g.repeatable(20).indexField("CoverageRef")
                            .field("CoverageRef", FieldKind.STRING)
                            .field("EndorsementAmount", FieldKind.NUMBER)
                            .group("Riders", r -> r.repeatable(5)
                                    .field("RiderNote", FieldKind.STRING)
                                    .field("RiderAmount", FieldKind.NUMBER)))
                    .group("Claims", g -> g.repeatable(20)
                            .field("ClaimRef", FieldKind.STRING)
                            .field("ClaimAmount", FieldKind.NUMBER)))
            .build().toJson();

    private static String onPremium(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Premium",
                "FieldFilled(Premium) And " + condition);
    }

    /** The same probe anchored on an indexed target, the only way a parallel iteration is legal here. */
    private static String onACoverage(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Coverages/Limit",
                "FieldFilled(Coverages/Limit) And " + condition);
    }

    @Test
    void whereASemanticIndexIsRefusedForSittingInAFilter() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an index over the FILTERED group, literal key",
                onPremium("Sum(Coverages*/Limit Having [Coverages/Note For \"C1\"] == \"x\") > 0"));
        actual.put("an index over ANOTHER group, literal key",
                onPremium("Sum(Claims*/ClaimAmount Having [Coverages/Limit For \"C1\"] > 0) > 0"));
        actual.put("an index whose key is READ from the filtered group",
                onPremium("Sum(Claims*/ClaimAmount Having [Coverages/Limit For Claims/ClaimRef] > 0) > 0"));
        actual.put("the same index OUTSIDE any filter — the control",
                onPremium("[Coverages/Limit For \"C1\"] > 0"));
        actual.put("an off-path iterating reference BEFORE the indexed one",
                onPremium("Sum(Coverages*/Limit Having [Claims/ClaimRef] == \"y\" "
                        + "And [Coverages/Note For \"C1\"] == \"x\") > 0"));
        actual.put("the same two, the indexed reference FIRST",
                onPremium("Sum(Coverages*/Limit Having [Coverages/Note For \"C1\"] == \"x\" "
                        + "And [Claims/ClaimRef] == \"y\") > 0"));
        actual.put("an index in the filter whose key names a GROUP",
                onPremium("Sum(Coverages*/Limit Having [Coverages/Note For Claims] == \"x\") > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an index over the FILTERED group, literal key", NOT_ALLOWED);
        // The group the index addresses is not a variable: the gate visits the filter's parse tree and asks
        // only whether some entity id carries a semantic index at all.
        expected.put("an index over ANOTHER group, literal key", NOT_ALLOWED);
        expected.put("an index whose key is READ from the filtered group", NOT_ALLOWED);
        // The control that makes the position load-bearing rather than the construct: the same index is
        // admitted where the rule reads it directly.
        expected.put("the same index OUTSIDE any filter — the control", KernelLaws.ADMITTED);
        expected.put("an off-path iterating reference BEFORE the indexed one",
                "MVK_INVALID_ITERATION_IN_SET_EXPRESSION");
        expected.put("the same two, the indexed reference FIRST", NOT_ALLOWED);
        expected.put("an index in the filter whose key names a GROUP", "MVK_SEMANTIC_INDEX_NO_FIELD");
        assertEquals(expected, actual, "where a semantic index is refused for sitting in a filter");
    }

    /**
     * That this class is drawn during the PARSE, which is what its precedence shows.
     *
     * <p>{@code checkNormalRule} also carries a {@code MVK_SEMANTIC_INDEX_NOT_ALLOWED} branch, four gates
     * into the same method and two after the filtered-entity one — so a post-parse reading of this class
     * predicts the filtered-entity verdict here. The filter walk throws first and the parse never finishes,
     * which is why the code needs a phase, not a rank.
     */
    @Test
    void theFilterWalkThrowsBeforeTheWholeRuleGatesRun() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an indexed filter on an operand under a keyed PARALLEL level",
                onACoverage("Sum(Endorsements/Riders*/RiderAmount "
                        + "Having [Coverages/Note For \"C1\"] == \"x\") > 0"));
        actual.put("the same operand with a filter carrying NO index",
                onACoverage("Sum(Endorsements/Riders*/RiderAmount "
                        + "Having [Endorsements/Riders/RiderNote] == \"x\") > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an indexed filter on an operand under a keyed PARALLEL level", NOT_ALLOWED);
        // The control: the identical operand without the index reaches the post-parse gate, so the row
        // above is the parse-time throw erasing it rather than the two gates being ranked.
        expected.put("the same operand with a filter carrying NO index",
                "MVK_PARALLEL_ITERATION_FOR_PATH_WITH_ASTERISKS_NOT_ALLOWED");
        assertEquals(expected, actual, "the filter walk throws before the whole-rule gates run");
    }
}
