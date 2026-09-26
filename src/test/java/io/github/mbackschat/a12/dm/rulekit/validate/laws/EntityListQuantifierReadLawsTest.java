package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS [KF458] on the carriers it was not measured on: the fill, group-list and value-list
 * quantifiers, {@code NumberOfValueInFields}, {@code RepetitionNotUnique} and the scalar value list.
 */
class EntityListQuantifierReadLawsTest {

    private static final String MODEL = DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH"))
                    .dateRangeField("Window", r -> r.format("dd.MM.yyyy").separator("-"))
                    .group("Stays", g -> g.repeatable(4)
                            .dateRangeField("Span", r -> r.format("dd.MM.yyyy").separator("-")))
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER).field("Score", FieldKind.NUMBER))
                    .group("Specs", g -> g.field("Label", FieldKind.STRING).field("Depth", FieldKind.NUMBER))
                    .group("Hollow", g -> { })
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                            .field("Amount", FieldKind.NUMBER)
                            .dateRangeField("Span", r -> r.format("dd.MM.yyyy").separator("-"))))
            .rootGroup("Other", o -> o.group("Inner", inner -> { }))
            .rootGroup("Lot", l -> l.repeatable(3).indexField("Serial").field("Serial", FieldKind.STRING))
            .build().toJson();

    /** An operand A12 refuses while reading it, written from {@code /Item}, → the class it reports. */
    private static final Map<String, String> UNREADABLE = unreadable("");

    private static Map<String, String> unreadable(String prefix) {
        Map<String, String> operands = new LinkedHashMap<>();
        operands.put(prefix + "Grading For \"k\"", "MVK_NO_INDEX_FIELD");
        operands.put(prefix + "Grading/Grade For \"k\"", "MVK_NO_INDEX_FIELD");
        operands.put(prefix + "Keyed/Amount For \"\"", "MVK_INDEX_VALUE_INVALID");
        operands.put(prefix + "Hollow", "MVK_GROUP_IS_EMPTY");
        operands.put("/Other", "MVK_ROOT_GROUP_IS_EMPTY");
        return operands;
    }

    /**
     * One carrier: its condition with {@code LIST} for the operand list, the rule group it sits in, the operand
     * it lists twice, its unreadable operands, a {@code $}-marked operand, and the controls that read cleanly.
     */
    private record Carrier(String template, String group, String errorField, String duplicate,
            Map<String, String> unreadable, String marked, List<String> controls, String plain) {

        String condition(String list) {
            return template.replace("LIST", list);
        }
    }

    private static Carrier fromItem(String template, String duplicate, String marked, String plain) {
        return new Carrier(template, "/Item", "../Title", duplicate, UNREADABLE, marked,
                List.of("Keyed/Amount For \"k\"", "Grading"), plain);
    }

    private static final List<Carrier> CARRIERS = List.of(
            fromItem("AtLeastOneFieldFilled(LIST)", "Weight", "$Title", "Weight, Weight, Title"),
            fromItem("FieldValuesNotUnique(LIST)", "Weight", "$Title", "Weight, Weight, Title"),
            fromItem("AtLeastOneGroupFilled(LIST)", "Specs", "$Title", "Title, Title, Weight"),
            fromItem("NumberOfValueInFields(1 In LIST) > 0", "Weight", "$Title", "Weight, Weight, Title"),
            fromItem("AtLeastOneFieldValueIncludedInValueList(LIST In \"a\")", "Title", "$Weight",
                    "Title, Title, Specs/Label"),
            fromItem("AtLeastOneFieldValueIncludedInValueList(LIST In Specs/Label)", "Title", "$Weight",
                    "Title, Title, Tier"),
            // keys must lie in a repeatable group the rule iterates, so this carrier sits in Keyed
            new Carrier("RepetitionNotUnique(LIST)", "/Item/Keyed", "../Key", "Key", unreadable("/Item/"), "$Amount",
                    List.of("/Item/Grading"), "Key, Key, Amount"),
            // the scalar list takes bracketed reads as its values; the subject is appended to them last
            new Carrier("FieldValueIncludedInValueList(Title, LIST)", "/Item", "../Title", "[Title]",
                    bracketed(UNREADABLE), "[$Weight]", List.of("[Keyed/Amount For \"k\"]"),
                    "[Specs/Label], [Specs/Label]"));

    /** The scalar list's values are reads, and a group is no read, so only the index operands carry over. */
    private static Map<String, String> bracketed(Map<String, String> operands) {
        Map<String, String> reads = new LinkedHashMap<>();
        operands.forEach((operand, read) -> {
            if (operand.contains(" For ")) {
                reads.put("[" + operand + "]", read);
            }
        });
        return reads;
    }

    @Test
    void theKernelReadsEveryOperandBeforeTheDuplicateGate() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Carrier carrier : CARRIERS) {
            String d = carrier.duplicate();
            Map<String, String> unreadable = new LinkedHashMap<>(carrier.unreadable());
            unreadable.put(carrier.marked(), "MVK_INVALID_OUTER_ITERATION");
            unreadable.forEach((operand, read) -> {
                for (String list : List.of(operand + ", " + d + ", " + d, d + ", " + d + ", " + operand)) {
                    String condition = carrier.condition(list);
                    expected.put(condition, read);
                    actual.put(condition, kernel(carrier, condition));
                }
            });
            List<String> controls = new java.util.ArrayList<>();
            carrier.controls().forEach(control -> controls.add(control + ", " + d + ", " + d));
            // every operand a plain field: the one list shape a record may cite the duplicate class on
            controls.add(carrier.plain());
            for (String list : controls) {
                String condition = carrier.condition(list);
                expected.put(condition, "MVK_DUPLICATE_PARAM1");
                actual.put(condition, kernel(carrier, condition));
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void theOtherListGatesOfTheseCarriersWaitForTheReadToo() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AtLeastOneFieldValueIncludedInValueList(Tier -> Nope, Title, Title In \"a\")", "MVK_CATEGORY_UNKOWN");
        expected.put("AtLeastOneFieldValueIncludedInValueList(Title, Title, Tier -> Nope In \"a\")", "MVK_CATEGORY_UNKOWN");
        expected.put("AtLeastOneFieldValueIncludedInValueList(Tier -> Band, Title, Title In \"a\")", "MVK_DUPLICATE_PARAM1");
        expected.put("AtLeastOneDateRangeOverlaps(Keyed/Span For \"\", Stays*/Span In Window)", "MVK_INDEX_VALUE_INVALID");
        expected.put("AtLeastOneDateRangeOverlaps(Stays*/Span, Keyed/Span For \"\" In Window)", "MVK_INDEX_VALUE_INVALID");
        expected.put("AtLeastOneDateRangeOverlaps(Stays*/Span In Window)", "MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON");
        expected.put("AtLeastOneDateRangeOverlaps(Stays*/Span In Keyed/Span For \"\")", "MVK_INDEX_VALUE_INVALID");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition, kernel(CARRIERS.getFirst(), condition)));

        Carrier keyed = CARRIERS.stream().filter(c -> c.group().equals("/Item/Keyed")).findFirst().orElseThrow();
        Map<String, String> scoped = new LinkedHashMap<>();
        scoped.put("RepetitionNotUnique($Amount @From /Item/Grading)", "MVK_INVALID_OUTER_ITERATION");
        scoped.put("RepetitionNotUnique(Key @From /Item/Hollow)", "MVK_GROUP_IS_EMPTY");
        scoped.put("RepetitionNotUnique(Key @From /Item/Grading)", "MVK_GROUP_NOT_REPEATABLE");
        scoped.forEach((condition, read) -> {
            expected.put(condition, read);
            actual.put(condition, kernel(keyed, condition));
        });
        assertEquals(expected, actual);
    }

    /** The group count's index, root and iteration edges, and an index-selected root on the aggregates. */
    @Test
    void theRootGatesAskAnIndexSelectedRootAndTheCountsIterationEdge() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NumberOfFilledGroups(Keyed, Grading) > 0", "MVK_NO_WILDCARD");
        expected.put("NumberOfFilledGroups(Keyed/Amount For \"k\", Grading) > 0", "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        expected.put("NumberOfFilledGroups(Grading, Keyed/Amount For \"k\") > 0", "MVK_SEMANTIC_INDEX_NOT_ALLOWED");
        expected.put("NumberOfFilledGroups(/Lot For \"k\", Grading) > 0", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
        expected.put("NumberOfFilledGroups(Grading, /Lot For \"k\") > 0", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS");
        // the aggregates' own root gate is asked of an index-selected root too, ahead of the kind gate
        expected.put("Sum(/Lot For \"k\", Weight) > 0", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("MaxValue(Weight, /Lot For \"k\") > 0", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("NumberOfFilledFields(Weight, /Lot For \"k\") > 0", "MVK_ROOT_GROUP_REFERENCED");
        expected.put("NumberOfDifferentValues(Weight, /Lot For \"k\") > 0", "MVK_ROOT_GROUP_REFERENCED");
        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition, kernel(CARRIERS.getFirst(), condition)));
        Carrier keyed = CARRIERS.stream().filter(c -> c.group().equals("/Item/Keyed")).findFirst().orElseThrow();
        String iterated = "NumberOfFilledGroups(/Item/Keyed, /Item/Grading) > 0";
        expected.put(iterated + ", in Keyed", KernelLaws.ADMITTED);
        actual.put(iterated + ", in Keyed", kernel(keyed, iterated));
        assertEquals(expected, actual);
    }

    private static String kernel(Carrier carrier, String condition) {
        return KernelLaws.verdictModel(MODEL, carrier.group(), carrier.errorField(),
                "FieldFilled(" + carrier.errorField().substring(3) + ") And " + condition);
    }
}
