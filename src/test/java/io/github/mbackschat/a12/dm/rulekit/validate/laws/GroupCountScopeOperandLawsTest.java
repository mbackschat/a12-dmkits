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
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * DEV-API-GAPS DG76 — {@code NumberOfFilledGroups} takes any GROUP entity, so a group scope in the record's
 * operand list is an operand like a {@link GroupRef} in its group list.
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
                            .group("Seal", s -> s.field("Code", FieldKind.STRING))))
            .rootGroup("Lot", l -> l.repeatable(3).field("Serial", FieldKind.STRING))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String REFUSED = "refused";

    /** One authored operand list: A12's verdict on the count over it, and whether the record admits it. */
    private record Row(String name, String operands, String kernel, Supplier<Condition> record,
            String recordVerdict) {

        Row(String operands, String kernel, Supplier<Condition> record, String recordVerdict) {
            this(operands, operands, kernel, record, recordVerdict);
        }
    }

    private static Supplier<Condition> count(List<AggregateOperand> fields, List<GroupRef> groups) {
        return () -> new NumberExpr(new CountAggregate(CountOp.FILLED_GROUPS, fields, groups)).gt(0);
    }

    private static List<Row> rows() {
        var grading = M.flattenGroup("/Item/Grading");
        var specs = M.flattenGroup("/Item/Specs");
        var keyed = M.flattenGroup("/Item/Keyed*");
        var seal = M.flattenGroup("/Item/Batches*/Seal");
        var lot = M.flattenGroup("/Lot*");
        return List.of(
                new Row("Grading, Specs", ADMITTED,
                        count(List.of(grading, specs), List.of()), ADMITTED),
                new Row("Keyed*", ADMITTED, count(List.of(keyed), List.of()), ADMITTED),
                new Row("Batches*/Seal", ADMITTED,
                        count(List.of(seal), List.of()), ADMITTED),
                // a scope in the operand list beside a group in the group list: one list to A12
                new Row("Grading, Specs split across both lists", "Grading, Specs", ADMITTED,
                        count(List.of(grading), List.of(M.group("/Item/Specs"))), ADMITTED),
                new Row("Grading", "MVK_PARAMSIZE_INVALIDGN",
                        count(List.of(grading), List.of()), REFUSED),
                new Row("Grading, /Lot*", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS",
                        count(List.of(grading, lot), List.of()), REFUSED),
                new Row("Grading, Weight", "MVK_NO_FIELDS_ALLOWED",
                        count(List.of(grading, M.field("/Item/Weight")), List.of()), REFUSED),
                new Row("Keyed For \"k\", Grading", "MVK_SEMANTIC_INDEX_NOT_ALLOWED",
                        count(List.of(M.flattenGroup("/Item/Keyed").forIndex("k"), grading), List.of()), REFUSED));
    }

    @Test
    void theKernelTakesAnyGroupEntityAsAnOperand() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            expected.put(row.name(), row.kernel());
            actual.put(row.name(), KernelLaws.verdictModel(MODEL, "/Item", "../Title",
                    "FieldFilled(Title) And NumberOfFilledGroups(" + row.operands() + ") > 0"));
        }
        assertEquals(expected, actual);
    }

    @Test
    void theRecordAdmitsEveryGroupEntityTheKernelAdmits() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : rows()) {
            expected.put(row.name(), row.recordVerdict());
            actual.put(row.name(), verdict(row.record(), row.kernel()));
        }
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
