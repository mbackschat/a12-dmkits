package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * KERNEL-FINDINGS [KF389], [KF399] — A12 asks an entity list's kind gates over each group operand's EXPANSION,
 * in place and at any depth, and types a group-led list by its expansion's first field. The {@link BoundModel}
 * factories read that expansion, so they ask the same gates in the same order and cite the kernel's class.
 *
 * <p>The kernel column is the oracle. {@link #everyCitationIsTheKernelsOwnClass} holds the model-bound column to
 * it. Own domain: a warehouse item.
 */
class EntityListGroupExpansionKindLawsTest {

    private static final String MODEL = DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .dateField("Released", d -> d.format("dd.MM.yyyy"))
                    .dateField("ApproxDay", d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL))
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER).field("Score", FieldKind.NUMBER))
                    .group("Specs", g -> g.field("Label", FieldKind.STRING).field("Depth", FieldKind.NUMBER))
                    .group("Nested", g -> g.group("Inner", inner -> inner.field("Note", FieldKind.STRING)))
                    .group("NumNested", g -> g.group("Tally", tally -> tally.field("Qty", FieldKind.NUMBER)))
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                            .field("Amount", FieldKind.NUMBER))
                    .group("Rekeyed", g -> g.repeatable(5).indexField("Code").field("Tally", FieldKind.NUMBER)
                            .field("Code", FieldKind.STRING))
                    .group("Layered", g -> g.group("Sheet", sheet -> sheet.field("Remark", FieldKind.STRING))
                            .field("Count", FieldKind.NUMBER))
                    .group("Annotated", g -> g.field("Pieces", FieldKind.NUMBER)
                            .group("Memo", memo -> memo.field("Text", FieldKind.STRING)))
                    .group("Dated", g -> g.repeatable(5)
                            .dateField("PackedOn", d -> d.format("dd.MM.yyyy"))
                            .dateField("BestBefore", d -> d.format("dd.MM.yyyy")))
                    .group("Monthly", g -> g.repeatable(5)
                            .dateField("OpenedIn", d -> d.format("yyyy-MM"))
                            .dateField("ClosedIn", d -> d.format("yyyy-MM"))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String UNCODED = "uncoded";
    private static final String NO_NUMBER = "MVK_NO_NUMBER";
    private static final String NOT_SORTABLE = "MVK_NOT_SORTABLE";
    private static final String NUMBER_AND_NON = "MVK_NUMBER_AND_NON_NUMBER";
    private static final String STRING_ENUM = "MVK_STRING_ENUM_AND_NON_STRING_ENUM";
    private static final String DATE_AND_NONDATE = "MVK_DATE_AND_NONDATE";
    private static final String PARTIAL = "MVK_PARTIAL_DATE_NOT_ALLOWED";

    /** One list on one carrier: the kernel's class and the model-bound factory's verdict. */
    private record Row(String carrier, String list, String kernel, String bound) {

        String condition() {
            return (carrier.equals("MaxValue@date") ? "MaxValue" : carrier) + "(" + list + ")"
                    + (carrier.equals("MaxValue@date") ? " > [Released]" : " > 0");
        }
    }

    private static final List<Row> ROWS = List.of(
            new Row("Sum", "Weight, Specs", NO_NUMBER, NO_NUMBER),
            // a lone group is judged as its fields
            new Row("Sum", "Specs", NO_NUMBER, NO_NUMBER),
            // at any depth: the group holds only a subgroup, which holds a String
            new Row("Sum", "Nested", NO_NUMBER, NO_NUMBER),
            new Row("Sum", "NumNested, Weight", ADMITTED, ADMITTED),
            // the index field is part of the expansion
            new Row("Sum", "Keyed*", NO_NUMBER, NO_NUMBER),
            new Row("Sum", "Grading", ADMITTED, ADMITTED),
            // a group leader's class is its first field's, so the non-date route is visible through it
            new Row("MaxValue", "Grading, Title", NOT_SORTABLE, NOT_SORTABLE),
            new Row("MaxValue", "Weight, Specs", NOT_SORTABLE, NOT_SORTABLE),
            new Row("MinValue", "Specs, Weight", NOT_SORTABLE, NOT_SORTABLE),
            new Row("NumberOfDifferentValues", "Weight, Specs", NUMBER_AND_NON, NUMBER_AND_NON),
            // the class is the group's first field's, and a later field of the same group breaks it
            new Row("NumberOfDifferentValues", "Specs, Weight", STRING_ENUM, STRING_ENUM),
            new Row("NumberOfDifferentValues", "Grading, Weight", ADMITTED, ADMITTED),
            new Row("NumberOfDifferentValues", "Keyed*, Title", STRING_ENUM, STRING_ENUM),
            // the expansion keeps declaration order: an index field declared after a Number does not lead, and a
            // subgroup's fields stand in its place, leading when it is declared first and following when it is not
            new Row("NumberOfDifferentValues", "Rekeyed*, Title", NUMBER_AND_NON, NUMBER_AND_NON),
            new Row("NumberOfDifferentValues", "Layered, Weight", STRING_ENUM, STRING_ENUM),
            new Row("NumberOfDifferentValues", "Annotated, Weight", NUMBER_AND_NON, NUMBER_AND_NON),
            // an index-selected group is read as its expansion too once its key resolves; behind the index the
            // model-bound factory refuses uncoded
            new Row("NumberOfDifferentValues", "Title, Keyed For \"k\"", STRING_ENUM, UNCODED),
            new Row("NumberOfDifferentValues", "Dated*, Title", DATE_AND_NONDATE, DATE_AND_NONDATE),
            new Row("MaxValue@date", "Dated*, ApproxDay", PARTIAL, PARTIAL),
            new Row("MaxValue@date", "Dated*, Weight", DATE_AND_NONDATE, DATE_AND_NONDATE),
            new Row("MaxValue@date", "Released, Dated*, Title", DATE_AND_NONDATE, DATE_AND_NONDATE),
            new Row("MaxValue@date", "Dated*, Released", ADMITTED, ADMITTED),
            // a group behind a temporal leader is walked through its fields, and its component sets are theirs
            new Row("MaxValue@date", "Released, Keyed*", DATE_AND_NONDATE, DATE_AND_NONDATE),
            new Row("MaxValue@date", "Released, Monthly*", "MVK_DATEFORMATS_NOT_COMPATIBLE",
                    "MVK_DATEFORMATS_NOT_COMPATIBLE"));

    @Test
    void theKernelAsksTheKindGatesOverEachGroupsExpansion() {
        assertEquals(column(Row::kernel), answers(row -> kernel(row.condition())));
    }

    @Test
    void theModelBoundFactoriesAskThemTheSameWay() {
        assertEquals(column(Row::bound), answers(row -> verdict(() -> bound(row))));
    }

    @Test
    void everyCitationIsTheKernelsOwnClass() {
        Map<String, String> strays = new LinkedHashMap<>();
        for (Row row : ROWS) {
            if (row.bound().startsWith("MVK_") && !row.bound().equals(row.kernel())) {
                strays.put(row.condition(), row.bound() + " where the kernel reports " + row.kernel());
            }
        }
        assertEquals(Map.of(), strays);
    }

    private static Condition bound(Row row) {
        List<AggregateOperand> operands = Arrays.stream(row.list().split(", "))
                .map(EntityListGroupExpansionKindLawsTest::operand).toList();
        AggregateOperand first = operands.getFirst();
        AggregateOperand[] rest = operands.subList(1, operands.size()).toArray(AggregateOperand[]::new);
        return switch (row.carrier()) {
            case "Sum" -> M.sum(first, rest).gt(0);
            case "MaxValue" -> M.maxValue(first, rest).gt(0);
            case "MinValue" -> M.minValue(first, rest).gt(0);
            case "NumberOfDifferentValues" -> M.numberOfDifferentValues(first, rest).gt(0);
            case "MaxValue@date" -> M.latestOf(first, rest).after(M.date("/Item/Released"));
            default -> throw new IllegalStateException(row.carrier());
        };
    }

    /**
     * A plain field or group of Item, or a group selected by a quoted key; a repeatable group is written with the
     * star rulekit renders for it.
     */
    private static AggregateOperand operand(String token) {
        int keyAt = token.indexOf(" For \"");
        if (keyAt >= 0) {
            String key = token.substring(keyAt + " For \"".length(), token.length() - 1);
            return M.flattenGroup("/Item/" + token.substring(0, keyAt)).forIndex(key);
        }
        String path = "/Item/" + token.replace("*", "");
        boolean isGroup = M.groups().anyMatch(g -> g.path().equals(path));
        if (!isGroup) {
            return M.field(path);
        }
        if (token.endsWith("*") != M.group(path).repeating()) {
            throw new IllegalStateException(token + " is not written as rulekit renders it");
        }
        return M.flattenGroup(token.endsWith("*") ? path + "*" : path);
    }

    private static Map<String, String> column(Function<Row, String> column) {
        Map<String, String> expected = new LinkedHashMap<>();
        ROWS.forEach(row -> expected.put(row.condition(), column.apply(row)));
        return expected;
    }

    private static Map<String, String> answers(Function<Row, String> answer) {
        Map<String, String> actual = new LinkedHashMap<>();
        ROWS.forEach(row -> actual.put(row.condition(), answer.apply(row)));
        return actual;
    }

    private static String kernel(String condition) {
        return KernelLaws.verdictModel(MODEL, "/Item", "../Title", "FieldFilled(Title) And " + condition);
    }

    private static String verdict(Supplier<?> authoring) {
        try {
            authoring.get();
            return ADMITTED;
        } catch (KernelCodeRefusal refusal) {
            return refusal.code().name();
        } catch (IllegalArgumentException uncoded) {
            return UNCODED;
        }
    }
}
