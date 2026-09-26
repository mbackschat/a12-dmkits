package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate;
import io.github.mbackschat.a12.dm.rulekit.dsl.CountAggregate.CountOp;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * {@code NumberOfFilledGroups} takes any GROUP entity, so a group scope is an operand whether it names a fixed
 * group, a starred one, or one below a starred level.
 *
 * <p>A12 checks this operator's operands after reading them, in this order: a root only alone
 * ({@code MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS}), no semantic index, groups only ({@code MVK_NO_FIELDS_ALLOWED},
 * which asks whether each entity is a group, not how it was spelled), and more than one operand or a sole one
 * whose path carries a star ({@code MVK_PARAMSIZE_INVALIDGN}) — {@code CheckFeldListenAnzahlOpImpl} and
 * {@code CheckEntityListenUtils.isMoreThanOneParameter} in the sibling kernel checkout. So a sole starred path
 * below a repeatable level, {@code Batches*}{@code /Seal}, satisfies the arity law as {@code Keyed*} does.
 *
 * <p>The record withholds every class on a list holding a group, since A12 reads a group's emptiness before
 * any list gate ({@code EntityListOperandReadLawsTest}), so where the kernel refuses, rulekit refusing at all is
 * the claim; where the kernel admits, the rendered condition must draw the kernel's verdict.
 */
class GroupCountScopeOperandLawsTest {

    private static final String MODEL = DocumentModelDraft.named("warehouse-group-count").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER))
                    .group("Specs", g -> g.field("Label", FieldKind.STRING))
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                            .field("Amount", FieldKind.NUMBER))
                    .group("Batches", g -> g.repeatable(4)
                            .group("Seal", s -> s.field("Code", FieldKind.STRING))
                            .group("Boxes", x -> x.repeatable(2).group("Lid", l -> l.field("Tag", FieldKind.STRING)))))
            .rootGroup("Lot", l -> l.repeatable(3).field("Serial", FieldKind.STRING))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String REFUSED = "refused";

    /** One authored operand list: A12's verdict on the count over it, and whether the record admits it. */
    private record Row(String operands, String kernel, Supplier<Condition> record, String recordVerdict) {
    }

    private static Supplier<Condition> count(List<AggregateOperand> operands) {
        return () -> new NumberExpr(new CountAggregate(CountOp.FILLED_GROUPS, operands)).gt(0);
    }

    private static List<Row> rows() {
        var grading = M.flattenGroup("/Item/Grading");
        var specs = M.flattenGroup("/Item/Specs");
        var keyed = M.flattenGroup("/Item/Keyed*");
        var seal = M.flattenGroup("/Item/Batches*/Seal");
        var lot = M.flattenGroup("/Lot*");
        return List.of(
                new Row("Grading, Specs", ADMITTED,
                        count(List.of(grading, specs)), ADMITTED),
                new Row("Keyed*", ADMITTED, count(List.of(keyed)), ADMITTED),
                new Row("Batches*/Seal", ADMITTED,
                        count(List.of(seal)), ADMITTED),
                // the duplicate gate skips a starred operand, so the same scope twice is two positions
                new Row("Batches*/Seal, Batches*/Seal", ADMITTED,
                        count(List.of(seal, seal)), ADMITTED),
                new Row("Grading, Grading", "MVK_DUPLICATE_PARAM1",
                        count(List.of(grading, grading)), REFUSED),
                new Row("Grading", "MVK_PARAMSIZE_INVALIDGN",
                        count(List.of(grading)), REFUSED),
                new Row("Grading, /Lot*", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS",
                        count(List.of(grading, lot)), REFUSED),
                new Row("Batches*/Seal, /Lot*", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS",
                        count(List.of(seal, lot)), REFUSED),
                new Row("Grading, Weight", "MVK_NO_FIELDS_ALLOWED",
                        count(List.of(grading, M.field("/Item/Weight"))), REFUSED),
                new Row("Keyed For \"k\", Grading", "MVK_SEMANTIC_INDEX_NOT_ALLOWED",
                        count(List.of(M.flattenGroup("/Item/Keyed").forIndex("k"), grading)), REFUSED));
    }

    @Test
    void theKernelTakesAnyGroupEntityAsAnOperand() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            expected.put(row.operands(), row.kernel());
            actual.put(row.operands(), KernelLaws.verdictModel(MODEL, "/Item", "../Title",
                    "FieldFilled(Title) And NumberOfFilledGroups(" + row.operands() + ") > 0"));
        }
        assertEquals(expected, actual);
    }

    @Test
    void theRecordAdmitsEveryGroupEntityTheKernelAdmits() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            expected.put(row.operands(), row.recordVerdict());
            actual.put(row.operands(), verdict(row.record(), row.kernel()));
        }
        assertEquals(expected, actual);
    }

    /**
     * The COMPUTATION arm takes the below-star forms the rule arm takes, the same starred form twice included,
     * and refuses each unstarred level with the class the rule arm uses. Measured because the two arms part on malformed content ([KF120]), so agreement on
     * admission was not something to assume (a12-kernel-lean SPEC-2026-09-24-02, their report only).
     */
    @Test
    void theComputationArmTakesTheBelowStarFormsAndRefusesAnUnstarredLevel() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Batches*/Seal", ADMITTED);
        expected.put("Batches*/Seal, Grading", ADMITTED);
        expected.put("Batches*/Seal, Batches*/Seal", ADMITTED);
        // the within-model control for the row above: the duplicate gate skips a starred operand, not a fixed one
        expected.put("Grading, Grading", "MVK_DUPLICATE_PARAM1");
        expected.put("Batches*/Seal, Keyed*", ADMITTED);
        expected.put("Batches*/Boxes*/Lid", ADMITTED);
        expected.put("Batches/Seal", "MVK_NO_WILDCARD");
        expected.put("Batches/Boxes*/Lid", "MVK_NO_WILDCARD");
        expected.put("Batches*/Boxes/Lid", "MVK_NO_WILDCARD");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(operands -> actual.put(operands, KernelLaws.computationVerdict(MODEL,
                new DraftComputation("/Item", "TallyProbe", "../Weight", null, List.of(
                        new DraftComputation.Alternative(null, "NumberOfFilledGroups(" + operands + ")", null))))));
        assertEquals(expected, actual);
    }

    /** {@code REFUSED} when the record refuses, or, where it admits, that the rendering draws the kernel's verdict. */
    private static String verdict(Supplier<Condition> authoring, String kernel) {
        try {
            Condition condition = authoring.get();
            String rendered = KernelLaws.verdictModel(MODEL, "/Item", "../Title", M.field("/Item/Title").filled()
                    .and(condition));
            return rendered.equals(kernel) ? ADMITTED : "admitted, but its rendering draws " + rendered;
        } catch (KernelCodeRefusal refusal) {
            return REFUSED + " citing " + refusal.code().name();
        } catch (IllegalArgumentException uncoded) {
            return REFUSED;
        }
    }
}
