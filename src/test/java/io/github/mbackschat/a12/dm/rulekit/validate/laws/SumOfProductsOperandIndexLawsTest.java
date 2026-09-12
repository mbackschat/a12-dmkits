package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Whether an A12 model can make two {@code SumOfProducts} operands differ at their OUTERMOST index — the one
 * question the A1 record left open on {@code MVK_INVALID_PARAMS_FOR_OP}
 * (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, A1 record).
 *
 * <p>The code's live raiser is {@code CheckEntityListenUtils.checkAllFieldsSameL}, which compares
 * {@code EntityRef.getLfdNr()} — that is {@code indices.get(0)} — across the two operands, and it is called
 * UNGATED from {@code CheckFeldListenOpImpl}. Source reading can get that far and no further: whether two
 * operands that survive the checks running BEFORE it can still disagree is a reachability question, and a
 * reachability claim needs every link ATTACKED rather than measured once.
 *
 * <p>So this attacks five ways to make them disagree, and each is refused by a DIFFERENT earlier gate. The
 * outermost index can be marked by a semantic index ({@code CheckSemanticIndexImpl.checkAndSetIndex} writes
 * {@code SEMANTIC_INDEX} at the index group's level), which is the only writer an A12 reference reaches —
 * and the model cannot carry it on one operand alone.
 */
class SumOfProductsOperandIndexLawsTest {

    /** Two nested repeatable levels, the outer one indexed, so a semantic index has a level to occupy. */
    private static final String NESTED = DocumentModelDraft.named("sop-operands").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Total", FieldKind.NUMBER)
                    .group("Lines", l -> l
                            .repeatable(9).indexField("LineRef")
                            .field("LineRef", FieldKind.STRING)
                            .group("Sub", s -> s
                                    .repeatable(9)
                                    .field("A", FieldKind.NUMBER)
                                    .field("B", FieldKind.NUMBER))))
            .build().toJson();

    private static String verdict(String operands) {
        ValidationResult result = new RuleValidator().check(NESTED, new DraftComputation(
                "/Order", "Comp", "../Total", null,
                List.of(new DraftComputation.Alternative(null,
                        "{ SumOfProducts(" + operands + ") }"))));
        return result.ok() ? KernelLaws.ADMITTED : result.diagnostics().stream()
                .map(d -> d.code().orElse("UNCODED")).distinct().reduce((a, b) -> a + "," + b).orElse("?");
    }

    /**
     * Every asymmetry that could reach {@code checkAllFieldsSameL} with different outermost indices, and the
     * gate that refuses it first. None of them is `MVK_INVALID_PARAMS_FOR_OP`.
     */
    @Test
    void noAuthorableAsymmetrySurvivesTheGatesAheadOfTheIndexComparison() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both operands carry the same semantic index",
                verdict("Lines/Sub*/A For \"x\", Lines/Sub*/B For \"x\""));
        actual.put("both carry one, with DIFFERENT index values",
                verdict("Lines/Sub*/A For \"x\", Lines/Sub*/B For \"y\""));
        actual.put("only one operand carries a semantic index",
                verdict("Lines/Sub*/A For \"x\", Lines/Sub*/B"));
        actual.put("neither does, so the outer level is unmarked",
                verdict("Lines/Sub*/A, Lines/Sub*/B"));
        actual.put("the outer level starred instead",
                verdict("Lines*/Sub*/A, Lines*/Sub*/B"));
        actual.put("one operand marks the outer iteration with `$`",
                verdict("$Lines/Sub*/A For \"x\", Lines/Sub*/B For \"x\""));
        actual.put("one operand filters, which also decorates its outer level",
                verdict("Lines/Sub*/A For \"x\" Having FieldFilled(B), Lines/Sub*/B For \"x\""));
        actual.put("the two spellings of one path differ, absolute against relative",
                verdict("/Order/Lines/Sub*/A For \"x\", Lines/Sub*/B For \"x\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both operands carry the same semantic index", KernelLaws.ADMITTED);
        // The index VALUE is not what reaches `indices[0]` — the marker is, and it is the same marker.
        expected.put("both carry one, with DIFFERENT index values", KernelLaws.ADMITTED);
        expected.put("only one operand carries a semantic index", "MVK_NO_WILDCARD");
        expected.put("neither does, so the outer level is unmarked", "MVK_NO_WILDCARD");
        expected.put("the outer level starred instead", "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED");
        expected.put("one operand marks the outer iteration with `$`", "MVK_INVALID_OUTER_ITERATION");
        expected.put("one operand filters, which also decorates its outer level",
                "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        // Spelling is not structure: the paths resolve to the same element and the same index shape.
        expected.put("the two spellings of one path differ, absolute against relative", KernelLaws.ADMITTED);
        assertEquals(expected, actual,
                "an asymmetry that reaches the operand-index comparison would make MVK_INVALID_PARAMS_FOR_OP "
                        + "reachable for an A12 model; each is refused earlier, by a different gate");
    }

    /**
     * The second CARRIER. {@code feldListenOp} is reached from `operation`, which a rule's comparison also
     * contains, so the computation rows above do not settle the rule arm — measured rather than assumed.
     */
    @Test
    void theRuleCarrierAgrees() {
        KernelLaws.acceptsModel(NESTED, "/Order", "../Total",
                "SumOfProducts(Lines/Sub*/A For \"x\", Lines/Sub*/B For \"x\") > [Total]");
        KernelLaws.rejectsModel(NESTED, "/Order", "../Total",
                "SumOfProducts(Lines/Sub*/A For \"x\", Lines/Sub*/B) > [Total]", "MVK_NO_WILDCARD");
    }
}
