package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * A list gate's class is withheld wherever an operand's READ may answer first, on the list carriers beyond
 * the entity-list aggregates: A12 reads every operand before its list gates, so an index that does not
 * resolve, a group holding no field, an undeclared category or a {@code $} marker answers ahead of them
 * (KERNEL-FINDINGS KF458; kernel rows in {@code validate.laws.EntityListQuantifierReadLawsTest}). The record
 * cannot settle any of those, so on a list holding one it keeps the refusal and drops the code, while a list
 * of plain fields keeps {@code MVK_DUPLICATE_PARAM1}, the class the kernel names there.
 */
class ListGateReadWithholdingTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH"))
                    .dateRangeField("Window", r -> r.format("dd.MM.yyyy").separator("-"))
                    .group("Stays", g -> g.repeatable(4)
                            .dateRangeField("Span", r -> r.format("dd.MM.yyyy").separator("-")))
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER).field("Score", FieldKind.NUMBER))
                    .group("Specs", g -> g.field("Label", FieldKind.STRING).field("Depth", FieldKind.NUMBER))
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                            .field("Amount", FieldKind.NUMBER)
                            .dateRangeField("Span", r -> r.format("dd.MM.yyyy").separator("-"))))
            .rootGroup("Lot", l -> l.repeatable(3).indexField("Serial").field("Serial", FieldKind.STRING))
            .build().toJson());

    private static final String UNCODED = "uncoded";
    private static final String DUPLICATE = "MVK_DUPLICATE_PARAM1";

    private static final FieldRef TITLE = M.field("/Item/Title");
    private static final FieldRef WEIGHT = M.field("/Item/Weight");
    private static final FieldRef LABEL = M.field("/Item/Specs/Label");

    /** One operand of each kind whose read the record cannot settle, keyed by what it is. */
    private static Map<String, AggregateOperand> unreadable() {
        Map<String, AggregateOperand> operands = new LinkedHashMap<>();
        operands.put("an index-selected field", new SemanticIndexValue(M.field("/Item/Keyed/Amount"), "k"));
        operands.put("an index-selected group", M.flattenGroup("/Item/Grading").forIndex("k"));
        operands.put("a group", M.flattenGroup("/Item/Grading"));
        operands.put("a $-marked field", TITLE.outer());
        return operands;
    }

    @Test
    void theFillQuantifiersWithholdBehindAnUnsettledRead() {
        Map<String, String> actual = new LinkedHashMap<>();
        unreadable().forEach((what, operand) -> actual.put(what, verdict(() -> new EntityListPredicate(
                Quantifier.AT_LEAST_ONE_FIELD_FILLED, List.of(operand, WEIGHT, WEIGHT)))));
        actual.put("plain fields", verdict(() -> new EntityListPredicate(
                Quantifier.AT_LEAST_ONE_FIELD_FILLED, List.of(WEIGHT, WEIGHT, TITLE))));
        assertEquals(expectedFor(unreadable().keySet()), actual);
    }

    @Test
    void numberOfValueInFieldsWithholdsBehindAnUnsettledRead() {
        Map<String, String> actual = new LinkedHashMap<>();
        unreadable().forEach((what, operand) -> actual.put(what, verdict(() -> new NumberOfValueInFields(
                NumberLiteral.of(1), List.of(operand, WEIGHT, WEIGHT)))));
        actual.put("plain fields", verdict(() -> new NumberOfValueInFields(
                NumberLiteral.of(1), List.of(WEIGHT, WEIGHT, TITLE))));
        assertEquals(expectedFor(unreadable().keySet()), actual);
    }

    @Test
    void theGroupListQuantifiersWithholdBehindAnUnsettledRead() {
        Map<String, String> actual = new LinkedHashMap<>();
        unreadable().forEach((what, operand) -> actual.put(what, verdict(() -> GroupListPredicate.overEntities(
                GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, (GroupListOperand) operand, TITLE, TITLE))));
        actual.put("plain fields", verdict(() -> GroupListPredicate.overEntities(
                GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, TITLE, TITLE, WEIGHT)));
        assertEquals(expectedFor(unreadable().keySet()), actual);
    }

    @Test
    void theValueListQuantifiersWithholdBehindAnUnsettledRead() {
        Map<String, ValueListOperand> operands = valueListUnreadable();
        Map<String, String> actual = new LinkedHashMap<>();
        operands.forEach((what, operand) -> {
            actual.put(what + " | constants", verdict(() -> new FieldListValueListCondition(
                    ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, List.of(operand, TITLE, TITLE),
                    List.of(new StringLiteral("a")))));
            actual.put(what + " | fields", verdict(() -> new FieldListEntityListCondition(
                    ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, List.of(operand, TITLE, TITLE),
                    List.of(LABEL))));
        });
        actual.put("plain fields | constants", verdict(() -> new FieldListValueListCondition(
                ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, List.of(TITLE, TITLE, LABEL),
                List.of(new StringLiteral("a")))));
        actual.put("plain fields | fields", verdict(() -> new FieldListEntityListCondition(
                ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED, List.of(TITLE, TITLE, WEIGHT),
                List.of(LABEL))));

        Map<String, String> expected = new LinkedHashMap<>();
        operands.keySet().forEach(what -> {
            expected.put(what + " | constants", UNCODED);
            expected.put(what + " | fields", UNCODED);
        });
        expected.put("plain fields | constants", DUPLICATE);
        expected.put("plain fields | fields", DUPLICATE);
        assertEquals(expected, actual);
    }

    /**
     * The date-range slot gate is a list gate too: a starred range in the first slot is
     * {@code MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON} only once every operand, in either slot, reads.
     */
    @Test
    void theDateRangeSlotGateWithholdsBehindAnUnsettledRead() {
        FieldListRef stays = M.flatten("/Item/Stays*/Span");
        SemanticIndexValue keyedSpan = new SemanticIndexValue(M.field("/Item/Keyed/Span"), "k");
        FieldRef window = M.field("/Item/Window");
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an index after the starred range", verdict(() -> new FieldListEntityListCondition(
                ValueListQuantifier.AT_LEAST_ONE_DATE_RANGE_OVERLAPS, List.of(stays, keyedSpan), List.of(window))));
        actual.put("an index in the value slot", verdict(() -> new FieldListEntityListCondition(
                ValueListQuantifier.AT_LEAST_ONE_DATE_RANGE_OVERLAPS, List.of(stays), List.of(keyedSpan))));
        actual.put("plain fields", verdict(() -> new FieldListEntityListCondition(
                ValueListQuantifier.AT_LEAST_ONE_DATE_RANGE_OVERLAPS, List.of(stays), List.of(window))));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an index after the starred range", UNCODED);
        expected.put("an index in the value slot", UNCODED);
        expected.put("plain fields", "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON");
        assertEquals(expected, actual);
    }

    @Test
    void theScalarValueListWithholdsBehindAnUnsettledRead() {
        FieldValue label = new FieldValue(LABEL, false);
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an index-selected value", verdict(() -> new ValueListCondition(TITLE, true,
                List.of(new SemanticIndexValue(M.field("/Item/Keyed/Key"), "k"), label, label))));
        actual.put("a $-marked value", verdict(() -> new ValueListCondition(TITLE, true,
                List.of(new FieldValue(LABEL, true), label, label))));
        actual.put("plain values", verdict(() -> new ValueListCondition(TITLE, true, List.of(label, label))));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an index-selected value", UNCODED);
        expected.put("a $-marked value", UNCODED);
        expected.put("plain values", DUPLICATE);
        assertEquals(expected, actual);
    }

    @Test
    void repetitionNotUniqueWithholdsBehindAMarkedKey() {
        FieldRef key = M.field("/Item/Keyed/Key");
        assertEquals(UNCODED, verdict(() -> new RepetitionNotUniqueCondition(
                List.of(M.field("/Item/Keyed/Amount").outer(), key, key), Optional.empty())));
        assertEquals(DUPLICATE, verdict(() -> new RepetitionNotUniqueCondition(List.of(key, key), Optional.empty())));
    }

    /**
     * A12 reads the {@code @From} scope before asking whether it repeats, so an empty group answers
     * {@code MVK_GROUP_IS_EMPTY} first; a {@link GroupRef} does not show whether it holds a field.
     */
    @Test
    void aScopeThatDoesNotRepeatIsRefusedWithoutTheRepeatClass() {
        RepetitionNotUniqueCondition keyed = Dsl.repetitionNotUnique(M.field("/Item/Keyed/Key"));
        assertEquals(UNCODED, verdict(() -> keyed.from(M.group("/Item/Grading"))));
        assertEquals("admitted", verdict(() -> keyed.from(M.group("/Item/Keyed"))));
    }

    /** A12's root gate asks ahead of its index gate, so an index-selected root beside a group names the root. */
    @Test
    void anIndexSelectedRootBesideAGroupIsRefusedByTheRootGate() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> new CountAggregate(
                CountAggregate.CountOp.FILLED_GROUPS,
                List.of(M.flattenGroup("/Lot*").forIndex("k"), M.flattenGroup("/Item/Grading")), List.of()));
        assertTrue(refusal.getMessage().contains("a root group only as its sole operand"), refusal::getMessage);
        assertEquals(UNCODED, verdict(() -> {
            throw refusal;
        }), "behind an index the record withholds the class");
    }

    /** The aggregates' root gate is asked of an index-selected root too, ahead of their kind gates. */
    @Test
    void anIndexSelectedRootIsRefusedByTheAggregatesRootGate() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> new ListAggregate(
                ListAggregate.AggOp.SUM, List.of(M.flattenGroup("/Lot*").forIndex("k"), WEIGHT)));
        assertTrue(refusal.getMessage().contains("does not accept a root group"), refusal::getMessage);
        assertEquals(UNCODED, verdict(() -> {
            throw refusal;
        }), "behind an index the record withholds the class");
    }

    private static Map<String, ValueListOperand> valueListUnreadable() {
        Map<String, ValueListOperand> operands = new LinkedHashMap<>();
        operands.put("an index-selected field", new SemanticIndexValue(M.field("/Item/Keyed/Key"), "k"));
        operands.put("a group", M.flattenGroup("/Item/Specs"));
        operands.put("a $-marked field", LABEL.outer());
        operands.put("a category", new EnumCategoryAccess(M.field("/Item/Tier"), "Band"));
        return operands;
    }

    private static Map<String, String> expectedFor(Iterable<String> unreadable) {
        Map<String, String> expected = new LinkedHashMap<>();
        unreadable.forEach(what -> expected.put(what, UNCODED));
        expected.put("plain fields", DUPLICATE);
        return expected;
    }

    private static String verdict(Supplier<?> authoring) {
        try {
            authoring.get();
            return "admitted";
        } catch (KernelCodeRefusal refusal) {
            return refusal.code().name();
        } catch (IllegalArgumentException uncoded) {
            return UNCODED;
        }
    }
}
