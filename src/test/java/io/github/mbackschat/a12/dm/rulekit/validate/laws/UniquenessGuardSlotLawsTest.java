package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier;

/**
 * How many slots each kind of operand gives {@code FieldValuesNotUnique}'s iteration guard (KERNEL-FINDINGS
 * KF533, added 2026-10-13 for a12-kernel-lean SPEC-2026-10-13-10). Every rule sits at the fixed root
 * {@code /Policy} and reports on a field of the repeatable {@code Claims}, so the rule iterates the claim; KF533's
 * first table measured that a fixed-root field
 * then gives one independent slot, a starred field one per declared repetition, and that two slots are refused
 * with {@code MVK_NEG_CONDITION_IN_ITERATION}.
 *
 * <p>The rows here widen that to the other operand kinds, for each of the Number, String and Date overloads: a
 * semantic-index selection gives one slot per field it reads, a group outside the claim one per field it expands
 * to, and a group inside the claim none. A field below a repeatable child weighs that child's capacity, as a
 * starred field does.
 *
 * <p>Three rows are controls that a12-kernel-lean's capture does not hold, each built to tell two accounts apart.
 * A root group whose one field sits in a NON-repeatable child separates "a nested field weighs its capacity" from
 * "a nested field weighs two". A selected group with one field separates "one slot per field" from "a selected
 * group weighs two". A star over a group of capacity one settles what KF533 had left unmeasured.
 *
 * <p>Own domain (an insurance policy, its claims, riders and keyed covers).
 */
class UniquenessGuardSlotLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-uniqueness-guard").locale("en_US")
            .rootGroup("Policy", p -> p
                    .numberField("Premium", n -> n.fractionDigits(0))
                    .numberField("Deductible", n -> n.fractionDigits(0))
                    .stringField("Holder", f -> { })
                    .stringField("Agent", f -> { })
                    .dateField("StartsOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("RenewsOn", d -> d.format("yyyy-MM-dd"))
                    .group("Claims", c -> c.repeatable(3)
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .stringField("Ref", f -> { })
                            .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                            .group("Paid", g -> g.numberField("PaidFirst", n -> n.fractionDigits(0))
                                    .numberField("PaidSecond", n -> n.fractionDigits(0)))
                            .group("Refs", g -> g.stringField("RefFirst", f -> { }).stringField("RefSecond", f -> { }))
                            .group("Dates", g -> g.dateField("DateFirst", d -> d.format("yyyy-MM-dd"))
                                    .dateField("DateSecond", d -> d.format("yyyy-MM-dd")))
                            .group("Repair", g -> g.numberField("Labour", n -> n.fractionDigits(0))
                                    .group("Parts", r -> r.repeatable(2).numberField("Cost", n -> n.fractionDigits(0)))))
                    .group("Riders", r -> r.repeatable(2)
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .stringField("Ref", f -> { })
                            .dateField("FiledOn", d -> d.format("yyyy-MM-dd")))
                    .group("Endorsement", e -> e.numberField("Amount", n -> n.fractionDigits(0)))
                    .group("Covers", c -> c.repeatable(3).indexField("Code")
                            .stringField("Code", f -> { })
                            .numberField("Amount", n -> n.fractionDigits(0))
                            .stringField("Ref", f -> { })
                            .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                            .group("Limits", g -> g.numberField("Lower", n -> n.fractionDigits(0))
                                    .numberField("Upper", n -> n.fractionDigits(0)))
                            .group("Labels", g -> g.stringField("LabelFirst", f -> { }).stringField("LabelSecond", f -> { }))
                            .group("Terms", g -> g.dateField("TermFirst", d -> d.format("yyyy-MM-dd"))
                                    .dateField("TermSecond", d -> d.format("yyyy-MM-dd")))
                            .group("Excess", g -> g.numberField("Floor", n -> n.fractionDigits(0)))
                            .group("Tiers", g -> g.group("Bands", b -> b.repeatable(2)
                                    .numberField("Rate", n -> n.fractionDigits(0)))))
                    .group("Totals", g -> g.numberField("Gross", n -> n.fractionDigits(0))
                            .numberField("Net", n -> n.fractionDigits(0)))
                    .group("Names", g -> g.stringField("First", f -> { }).stringField("Last", f -> { }))
                    .group("Signing", g -> g.dateField("Issued", d -> d.format("yyyy-MM-dd"))
                            .dateField("Signed", d -> d.format("yyyy-MM-dd")))
                    .group("Levy", g -> g.numberField("Rate", n -> n.fractionDigits(0)))
                    .group("Alias", g -> g.stringField("Nick", f -> { }))
                    .group("Witness", g -> g.dateField("SignedOn", d -> d.format("yyyy-MM-dd")))
                    .group("Region", g -> g.group("Branches", b -> b.repeatable(2)
                            .numberField("Score", n -> n.fractionDigits(0))))
                    .group("Office", g -> g.numberField("Staff", n -> n.fractionDigits(0))
                            .group("Desks", d -> d.repeatable(2).numberField("Seats", n -> n.fractionDigits(0))))
                    .group("Archive", g -> g.group("Shelf", s -> s.numberField("Box", n -> n.fractionDigits(0)))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(POLICY);
    private static final String NEGATIVE = "MVK_NEG_CONDITION_IN_ITERATION";

    /** One overload's fields: the claim's own, two root fields, and the groups of that kind at each level. */
    private record Kind(String letter, String field, String root1, String root2, String rowGroup, String rowGroupField,
            String rootGroup, String selectedGroup, String singleGroup) { }

    private static final List<Kind> KINDS = List.of(
            new Kind("N", "Amount", "Premium", "Deductible", "Paid", "PaidFirst", "Totals", "Limits", "Levy"),
            new Kind("T", "Ref", "Holder", "Agent", "Refs", "RefFirst", "Names", "Labels", "Alias"),
            new Kind("D", "FiledOn", "StartsOn", "RenewsOn", "Dates", "DateFirst", "Signing", "Terms", "Witness"));

    private static AggregateOperand field(String path) {
        return M.field("/Policy/" + path);
    }

    private static AggregateOperand selected(String path, String key) {
        return M.field("/Policy/" + path).semanticIndex(key).index();
    }

    private static AggregateOperand group(String path) {
        return M.flattenGroup("/Policy/" + path);
    }

    private static AggregateOperand selectedGroup(String path, String key) {
        return M.flattenGroup("/Policy/" + path).forIndex(key);
    }

    private static Condition unique(AggregateOperand... operands) {
        return Dsl.entityListPredicate(Quantifier.FIELD_VALUES_NOT_UNIQUE, List.of(operands));
    }

    private record Row(String errorField, Condition condition, String verdict) { }

    /** a12-kernel-lean's 33 overload rows, re-measured on this model, with the verdict their capture reports. */
    private static Map<String, Row> overloadRows() {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Kind k : KINDS) {
            String own = "Claims/" + k.field();
            String ownGroupField = "Claims/" + k.rowGroup() + "/" + k.rowGroupField();
            AggregateOperand claim = field(own);
            rows.put(k.letter() + "ROOT1", new Row(own, unique(claim, field(k.root1())), KernelLaws.ADMITTED));
            rows.put(k.letter() + "ROOT2", new Row(own, unique(claim, field(k.root1()), field(k.root2())), NEGATIVE));
            rows.put(k.letter() + "STAR2", new Row(own, unique(claim, M.flatten("/Policy/Riders*/" + k.field())), NEGATIVE));
            rows.put(k.letter() + "SEL1", new Row(own, unique(claim, selected("Covers/" + k.field(), "c1")),
                    KernelLaws.ADMITTED));
            rows.put(k.letter() + "SEL2", new Row(own, unique(claim, selected("Covers/" + k.field(), "c1"),
                    selected("Covers/" + k.field(), "c2")), NEGATIVE));
            rows.put(k.letter() + "SELROOT", new Row(own, unique(claim, selected("Covers/" + k.field(), "c1"),
                    field(k.root1())), NEGATIVE));
            rows.put(k.letter() + "ROWGRP", new Row(own, unique(claim, group("Claims/" + k.rowGroup())), KernelLaws.ADMITTED));
            rows.put(k.letter() + "ROOTGRP", new Row(own, unique(claim, group(k.rootGroup())), NEGATIVE));
            rows.put(k.letter() + "ROWGRPONLY", new Row(ownGroupField, unique(group("Claims/" + k.rowGroup())),
                    KernelLaws.ADMITTED));
            rows.put(k.letter() + "SELGRP", new Row(own, unique(claim, selectedGroup("Covers/" + k.selectedGroup(), "c1")),
                    NEGATIVE));
            rows.put(k.letter() + "ROWGRPROOT", new Row(ownGroupField, unique(group("Claims/" + k.rowGroup()),
                    field(k.root1())), KernelLaws.ADMITTED));
            rows.put(k.letter() + "ROOTGRP1", new Row(own, unique(claim, group(k.singleGroup())), KernelLaws.ADMITTED));
            rows.put(k.letter() + "ROOTGRP1F", new Row(own, unique(claim, group(k.singleGroup()), field(k.root1())),
                    NEGATIVE));
        }
        return rows;
    }

    /** The Number overload, so one operand varies and the slot count it brings is the only question. */
    private static Map<String, Row> nestedRows() {
        AggregateOperand claim = field("Claims/Amount");
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("GDEEP1", new Row("Claims/Amount", unique(claim, group("Region")), NEGATIVE));
        rows.put("GDEEP2", new Row("Claims/Amount", unique(claim, group("Office")), NEGATIVE));
        rows.put("GDEEPSTAR", new Row("Claims/Amount", unique(claim, group("Region/Branches*")), NEGATIVE));
        rows.put("SELDEEP", new Row("Claims/Amount", unique(claim, selectedGroup("Covers/Tiers", "c1")), NEGATIVE));
        rows.put("ROWDEEP", new Row("Claims/Amount", unique(claim, group("Claims/Repair")), KernelLaws.ADMITTED));
        // The controls: a non-repeatable child weighs one, a one-field selected group one.
        rows.put("a root group's field in a non-repeatable child", new Row("Claims/Amount",
                unique(claim, group("Archive")), KernelLaws.ADMITTED));
        rows.put("a selected group of one field", new Row("Claims/Amount",
                unique(claim, selectedGroup("Covers/Excess", "c1")), KernelLaws.ADMITTED));
        return rows;
    }

    private static Map<String, String> verdicts(Map<String, Row> rows) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        rows.forEach((id, row) -> verdicts.put(id,
                KernelLaws.verdictModel(POLICY, "/Policy", "../" + row.errorField(), row.condition())));
        return verdicts;
    }

    private static Map<String, String> expected(Map<String, Row> rows) {
        Map<String, String> expected = new LinkedHashMap<>();
        rows.forEach((id, row) -> expected.put(id, row.verdict()));
        return expected;
    }

    @Test
    void aSelectionOrGroupGivesOneSlotPerFieldItReadsOnEveryOverload() {
        Map<String, Row> rows = overloadRows();
        assertEquals(expected(rows), verdicts(rows));
    }

    @Test
    void aNestedFieldWeighsItsRepeatableChildsCapacity() {
        Map<String, Row> rows = nestedRows();
        assertEquals(expected(rows), verdicts(rows));
    }

    /**
     * A star over one repetition is no slot count at all: it is refused as a wildcard first. The row is condition
     * text because the typed surface refuses the star itself ({@code BoundModel.flatten}, the same class), and
     * this row is the kernel measurement that refusal rests on.
     */
    @Test
    void aStarOverOneRepetitionIsRefusedBeforeTheGuard() {
        assertEquals("MVK_INVALID_WILDCARD", KernelLaws.verdictModel(POLICY, "/Policy", "../Claims/Amount",
                "FieldValuesNotUnique(Claims/Amount, Endorsement*/Amount)"));
    }

    /** The typed operands reach the kernel in the spelling each row names, so no row measures a different shape. */
    @Test
    void theOperandsRenderAsTheShapesTheRowsName() {
        Map<String, String> spelled = new LinkedHashMap<>();
        Map<String, Row> rows = new LinkedHashMap<>(overloadRows());
        rows.putAll(nestedRows());
        for (String id : List.of("NSTAR2", "TSEL2", "DSELGRP", "NROWGRPROOT", "GDEEPSTAR", "SELDEEP")) {
            spelled.put(id, KernelLaws.render("/Policy", rows.get(id).condition()));
        }
        assertEquals(Map.of(
                "NSTAR2", "FieldValuesNotUnique(Claims/Amount, Riders*/Amount)",
                "TSEL2", "FieldValuesNotUnique(Claims/Ref, Covers/Ref For \"c1\", Covers/Ref For \"c2\")",
                "DSELGRP", "FieldValuesNotUnique(Claims/FiledOn, Covers/Terms For \"c1\")",
                "NROWGRPROOT", "FieldValuesNotUnique(Claims/Paid, Premium)",
                "GDEEPSTAR", "FieldValuesNotUnique(Claims/Amount, Region/Branches*)",
                "SELDEEP", "FieldValuesNotUnique(Claims/Amount, Covers/Tiers For \"c1\")"), spelled);
    }
}
