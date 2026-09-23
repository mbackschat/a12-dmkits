package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupSemanticIndexValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexFieldValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndexValue;

/**
 * KERNEL-FINDINGS [KF458] — A12 checks each operand of an entity list while it READS it, ahead of
 * every list gate: a semantic index that does not resolve (no index field, an empty key, a key of the wrong
 * type) and a group holding no field, an empty root included. The first such operand in list order answers,
 * and duplicates, the root-group gate, arity and the kind gates all wait behind it, on {@code Sum},
 * {@code MaxValue}, {@code MinValue}, {@code NumberOfDifferentValues}, {@code NumberOfFilledFields} and
 * {@code NumberOfFilledGroups} alike.
 *
 * <p><b>What rulekit may cite, and why the columns differ.</b> A record sees neither fact: an index handle never
 * sees the model, and a group scope does not carry its expansion. So on a list holding an index or a group,
 * every list gate's class is withheld and the refusal stands ([AF160]); that costs the code on lists whose
 * operands all read cleanly, which the control rows show. A list of fields reads nothing that can fail and
 * keeps its class. The {@link BoundModel} factories see each group's expansion, so they cite the empty
 * classes themselves, and keep the list gates' classes once every group is known to hold a field; only
 * behind an index do they withhold. {@link #everyCitationIsTheKernelsOwnClass} holds both columns to the
 * kernel's.
 *
 * <p>The kernel rows are strings because the typed surface refuses most of these lists, which is the subject
 * under test (KernelLaws, "A string row is still legitimate"). Own domain: a warehouse item.
 */
class EntityListOperandReadLawsTest {

    private static final String MODEL = DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .field("Sealed", FieldKind.BOOLEAN)
                    .dateField("Released", d -> d.format("dd.MM.yyyy"))
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER).field("Score", FieldKind.NUMBER))
                    .group("Specs", g -> g.field("Label", FieldKind.STRING).field("Depth", FieldKind.NUMBER))
                    .group("Hollow", g -> { })
                    .group("Lapses", g -> g.repeatable(3))
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                            .field("Amount", FieldKind.NUMBER).dateField("PackedOn", d -> d.format("dd.MM.yyyy"))))
            .rootGroup("Other", o -> o.group("Inner", inner -> { }))
            .rootGroup("Extra", x -> x.field("Code", FieldKind.STRING))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** An operand A12 refuses while reading it → the class it reports. */
    private static final Map<String, String> UNREADABLE = unreadable();

    /** The unreadable operands whose failure the MODEL shows: a group's expansion, not an index's resolution. */
    private static final Set<String> EMPTY_GROUPS = Set.of("Hollow", "/Other");

    private static Map<String, String> unreadable() {
        Map<String, String> operands = new LinkedHashMap<>();
        operands.put("Grading For \"k\"", "MVK_NO_INDEX_FIELD");
        operands.put("Grading/Grade For \"k\"", "MVK_NO_INDEX_FIELD");
        operands.put("Keyed/Amount For \"\"", "MVK_INDEX_VALUE_INVALID");
        operands.put("Keyed/Amount For Weight", "MVK_INDEX_TYPE_INCONSISTENT");
        operands.put("Hollow", "MVK_GROUP_IS_EMPTY");
        operands.put("/Other", "MVK_ROOT_GROUP_IS_EMPTY");
        return operands;
    }

    private static final List<String> NUMERIC_CARRIERS =
            List.of("Sum", "MaxValue", "MinValue", "NumberOfDifferentValues", "NumberOfFilledFields");

    /**
     * Where {@code Title} leads an unreadable operand, the lists rulekit leaves to the kernel: no gate of its own
     * fires there. Every other row of the grid is refused by rulekit, uncoded.
     */
    private static final Map<String, Set<String>> TITLE_LED_ADMITTED = Map.of(
            "Sum", Set.of(),
            "MaxValue", Set.of(),
            "MinValue", Set.of(),
            "NumberOfDifferentValues", Set.of("Grading For \"k\"", "Hollow"),
            // a root is refused on this carrier whether or not it holds a field, so only /Other is refused here
            "NumberOfFilledFields", Set.of("Grading For \"k\"", "Grading/Grade For \"k\"", "Keyed/Amount For \"\"",
                    "Keyed/Amount For Weight", "Hollow"));

    private record Row(String carrier, String list, String kernel, String dsl, String bound) {
        Row(String carrier, String list, String kernel, String dsl) {
            this(carrier, list, kernel, dsl, null);
        }

        String condition() {
            return carrier.replace("@date", "").replace("@groups", "") + "(" + list + ")" + comparand();
        }

        private String comparand() {
            return switch (carrier) {
                case "MaxValue@date" -> " > [Released]";
                case "MinValue@date" -> " < [Released]";
                default -> " > 0";
            };
        }
    }

    private static final List<Row> ROWS = rows();

    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (String carrier : NUMERIC_CARRIERS) {
            UNREADABLE.forEach((operand, read) -> {
                // the model-bound factories read a group's expansion first and cite it; an index they cannot
                // resolve, so behind one they answer as the records do
                boolean shown = EMPTY_GROUPS.contains(operand);
                String titleLed = TITLE_LED_ADMITTED.get(carrier).contains(operand) ? ADMITTED : UNCODED;
                rows.add(new Row(carrier, operand + ", Weight, Weight", read, UNCODED, shown ? read : UNCODED));
                rows.add(new Row(carrier, "Weight, Weight, " + operand, read, UNCODED, shown ? read : UNCODED));
                rows.add(new Row(carrier, "Title, " + operand, read, titleLed, shown ? read : titleLed));
            });
            // operands that read cleanly: the list gates answer; a record, unable to tell, withholds, and the
            // model-bound factory cites once it has read that every group holds a field
            rows.add(new Row(carrier, "Keyed/Amount For \"k\", Weight, Weight", DUPLICATE, UNCODED, UNCODED));
            rows.add(new Row(carrier, "Grading, Weight, Weight", DUPLICATE, UNCODED, DUPLICATE));
            // a list of fields reads nothing that can fail, and keeps its class
            rows.add(new Row(carrier, "Weight, Weight, Title", DUPLICATE, DUPLICATE, DUPLICATE));
        }
        rows.add(new Row("Sum", "Title, Weight", "MVK_NO_NUMBER", "MVK_NO_NUMBER", "MVK_NO_NUMBER"));
        rows.add(new Row("MaxValue", "Title, Weight", "MVK_NOT_SORTABLE", "MVK_NOT_SORTABLE", "MVK_NOT_SORTABLE"));
        rows.add(new Row("NumberOfDifferentValues", "Title, Weight", "MVK_STRING_ENUM_AND_NON_STRING_ENUM",
                "MVK_STRING_ENUM_AND_NON_STRING_ENUM", "MVK_STRING_ENUM_AND_NON_STRING_ENUM"));

        // beside a group that holds a field, the model-bound factories ask the records' list gates coded: the
        // kind, root and param classes too, not only the duplicate one above
        rows.add(new Row("Sum", "Grading, Title", "MVK_NO_NUMBER", UNCODED, "MVK_NO_NUMBER"));
        rows.add(new Row("MaxValue", "Title, Grading", "MVK_NOT_SORTABLE", UNCODED, "MVK_NOT_SORTABLE"));
        rows.add(new Row("Sum", "/Item", "MVK_ROOT_GROUP_REFERENCED", UNCODED, "MVK_ROOT_GROUP_REFERENCED"));
        rows.add(new Row("NumberOfFilledFields@groups", "/Item", "MVK_ROOT_GROUP_REFERENCED", UNCODED,
                "MVK_ROOT_GROUP_REFERENCED"));
        rows.add(new Row("NumberOfDifferentValues", "Title, Grading, Weight", "MVK_STRING_ENUM_AND_NON_STRING_ENUM",
                UNCODED, "MVK_STRING_ENUM_AND_NON_STRING_ENUM"));
        rows.add(new Row("NumberOfDifferentValues", "Weight, Grading, Title", "MVK_NUMBER_AND_NON_NUMBER", UNCODED,
                "MVK_NUMBER_AND_NON_NUMBER"));
        rows.add(new Row("NumberOfDifferentValues", "Sealed, Grading", "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED",
                UNCODED, "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED"));
        // …and no further: a group LEADER hides the route, and a mismatch inside a group's expansion, which A12
        // asks under the leader's class, is not asked here (DEV-API-GAPS DG73), so both stay with the kernel
        rows.add(new Row("MaxValue", "Grading, Title", "MVK_NOT_SORTABLE", UNCODED, UNCODED));
        rows.add(new Row("Sum", "Weight, Specs", "MVK_NO_NUMBER", ADMITTED, ADMITTED));
        rows.add(new Row("NumberOfDifferentValues", "Weight, Specs", "MVK_NUMBER_AND_NON_NUMBER", ADMITTED,
                ADMITTED));

        // the FIRST unreadable operand in list order answers
        rows.add(new Row("Sum", "Hollow, /Other", "MVK_GROUP_IS_EMPTY", UNCODED, "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("Sum", "/Other, Hollow", "MVK_ROOT_GROUP_IS_EMPTY", UNCODED, "MVK_ROOT_GROUP_IS_EMPTY"));
        rows.add(new Row("Sum", "Keyed/Amount For \"\", Hollow", "MVK_INDEX_VALUE_INVALID", ADMITTED, ADMITTED));
        rows.add(new Row("Sum", "Hollow, Keyed/Amount For \"\"", "MVK_GROUP_IS_EMPTY", ADMITTED,
                "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("Sum", "/Item, Hollow", "MVK_GROUP_IS_EMPTY", UNCODED, "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("NumberOfDifferentValues", "Hollow, Keyed/Amount For \"\"", "MVK_GROUP_IS_EMPTY",
                ADMITTED, "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("NumberOfDifferentValues", "Keyed/Amount For \"\", Hollow", "MVK_INDEX_VALUE_INVALID",
                ADMITTED, ADMITTED));

        // NumberOfFilledFields over plain groups, the overload the condition-text bridge takes for them
        rows.add(new Row("NumberOfFilledFields@groups", "Hollow, Grading", "MVK_GROUP_IS_EMPTY", ADMITTED,
                "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("NumberOfFilledFields@groups", "Grading, Grading", DUPLICATE, UNCODED, DUPLICATE));
        rows.add(new Row("NumberOfFilledFields@groups", "Grading, /Other", "MVK_ROOT_GROUP_IS_EMPTY", UNCODED,
                "MVK_ROOT_GROUP_IS_EMPTY"));

        // the temporal extrema, through the date factories
        rows.add(new Row("MaxValue@date", "Released, Weight, Lapses*", "MVK_GROUP_IS_EMPTY", UNCODED,
                "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("MaxValue@date", "Lapses*, Released, Released", "MVK_GROUP_IS_EMPTY", UNCODED,
                "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("MinValue@date", "Released, Weight, Lapses*", "MVK_GROUP_IS_EMPTY", UNCODED,
                "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("MaxValue@date", "Released, Keyed/PackedOn For \"\", Weight", "MVK_INDEX_VALUE_INVALID",
                UNCODED, UNCODED));
        rows.add(new Row("MaxValue@date", "Released, Released, Keyed/PackedOn For \"k\"", DUPLICATE, UNCODED,
                UNCODED));
        rows.add(new Row("MaxValue@date", "Released, Released, Keyed*", DUPLICATE, UNCODED, DUPLICATE));
        rows.add(new Row("MaxValue@date", "Released, Released, Title", DUPLICATE, DUPLICATE, DUPLICATE));

        // NumberOfFilledGroups: every operand is a group, so a record withholds every class it reports, and the
        // model-bound factory, which reads each one, cites them all
        rows.add(new Row("NumberOfFilledGroups", "Hollow, Grading, Grading", "MVK_GROUP_IS_EMPTY", UNCODED,
                "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("NumberOfFilledGroups", "Grading, Grading, Hollow", "MVK_GROUP_IS_EMPTY", UNCODED,
                "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("NumberOfFilledGroups", "Hollow", "MVK_GROUP_IS_EMPTY", UNCODED, "MVK_GROUP_IS_EMPTY"));
        rows.add(new Row("NumberOfFilledGroups", "Grading, /Other", "MVK_ROOT_GROUP_IS_EMPTY", UNCODED,
                "MVK_ROOT_GROUP_IS_EMPTY"));
        rows.add(new Row("NumberOfFilledGroups", "Grading, Grading", DUPLICATE, UNCODED, DUPLICATE));
        rows.add(new Row("NumberOfFilledGroups", "Grading", "MVK_PARAMSIZE_INVALIDGN", UNCODED,
                "MVK_PARAMSIZE_INVALIDGN"));
        rows.add(new Row("NumberOfFilledGroups", "Grading, /Item", "MVK_DUPLICATE_PARAM2", UNCODED,
                "MVK_DUPLICATE_PARAM2"));
        rows.add(new Row("NumberOfFilledGroups", "Grading, Keyed*", ADMITTED, ADMITTED, ADMITTED));
        // a lone root: whether it holds a field moves the class, which only the model shows
        rows.add(new Row("NumberOfFilledGroups", "/Item", "MVK_PARAMSIZE_INVALIDGN", UNCODED,
                "MVK_PARAMSIZE_INVALIDGN"));
        rows.add(new Row("NumberOfFilledGroups", "/Other", "MVK_ROOT_GROUP_IS_EMPTY", UNCODED,
                "MVK_ROOT_GROUP_IS_EMPTY"));
        // a root that holds a field, beside a group it does not contain: the group count has a class of its own
        // for that, and the field count the plain one
        rows.add(new Row("NumberOfFilledGroups", "Grading, /Extra", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS", UNCODED,
                "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS"));
        rows.add(new Row("NumberOfFilledGroups", "/Extra, Grading", "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS", UNCODED,
                "MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS"));
        rows.add(new Row("NumberOfFilledFields@groups", "Grading, /Extra", "MVK_ROOT_GROUP_REFERENCED", UNCODED,
                "MVK_ROOT_GROUP_REFERENCED"));
        rows.add(new Row("NumberOfFilledFields", "/Extra, Weight", "MVK_ROOT_GROUP_REFERENCED", UNCODED,
                "MVK_ROOT_GROUP_REFERENCED"));
        return rows;
    }

    @Test
    void theKernelReadsEveryOperandBeforeAnyListGate() {
        assertEquals(column(Row::kernel), answers(row -> kernel(row.condition())));
        // the comparands and the unreadable operands' neighbours are admitted alone
        assertEquals(ADMITTED, kernel("[Released] == [Released]"));
        assertEquals(ADMITTED, kernel("Sum(Grading, Weight) > 0"));
    }

    @Test
    void theRecordsWithholdEveryClassOnAListHoldingAnIndexOrAGroup() {
        assertEquals(column(Row::dsl), answers(row -> verdict(() -> dsl(row))));
    }

    @Test
    void theModelBoundFactoriesCiteAnEmptyGroupAndWithholdOnlyBehindAnIndex() {
        List<Row> bound = ROWS.stream().filter(row -> row.bound() != null).toList();
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : bound) {
            expected.put(row.condition(), row.bound());
            actual.put(row.condition(), verdict(() -> bound(row)));
        }
        assertEquals(expected, actual);
    }

    /** Where rulekit admits a list, the kernel verdict of what it RENDERS is the row's — the tokens build the row. */
    @Test
    void anAdmittedListRendersTheRowItStandsFor() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Row row : ROWS) {
            if (row.dsl().equals(ADMITTED)) {
                expected.put(row.condition(), row.kernel());
                actual.put(row.condition(), KernelLaws.verdictModel(MODEL, "/Item", "../Title",
                        M.field("/Item/Title").filled().and(dsl(row))));
            }
        }
        assertTrue(expected.size() >= 10, expected.toString());
        assertEquals(expected, actual);
    }

    @Test
    void everyCitationIsTheKernelsOwnClass() {
        Map<String, String> strays = new LinkedHashMap<>();
        for (Row row : ROWS) {
            for (String cited : Arrays.asList(row.dsl(), row.bound())) {
                if (cited != null && cited.startsWith("MVK_") && !cited.equals(row.kernel())) {
                    strays.put(row.condition(), cited + " where the kernel reports " + row.kernel());
                }
            }
        }
        assertEquals(Map.of(), strays);
    }

    private static io.github.mbackschat.a12.dm.rulekit.dsl.Condition dsl(Row row) {
        if (row.carrier().equals("NumberOfFilledGroups")) {
            List<GroupRef> groups = groups(row.list());
            GroupRef first = groups.getFirst();
            if (groups.size() == 1) {
                return (isRoot(first) ? Dsl.numberOfFilledRootGroups(first) : Dsl.numberOfFilledGroups(first)).gt(0);
            }
            return Dsl.numberOfFilledGroups(first, groups.get(1), restOf(groups, 2)).gt(0);
        }
        if (row.carrier().equals("NumberOfFilledFields@groups")) {
            List<GroupRef> groups = groups(row.list());
            return Dsl.numberOfFilledFields(groups.getFirst(), restOf(groups, 1)).gt(0);
        }
        List<AggregateOperand> ops = operands(row.list());
        AggregateOperand first = ops.getFirst();
        AggregateOperand[] rest = ops.subList(1, ops.size()).toArray(AggregateOperand[]::new);
        return switch (row.carrier()) {
            case "Sum" -> Dsl.sum(first, rest).gt(0);
            case "MaxValue" -> Dsl.maxValue(first, rest).gt(0);
            case "MinValue" -> Dsl.minValue(first, rest).gt(0);
            case "NumberOfDifferentValues" -> Dsl.numberOfDifferentValues(first, rest).gt(0);
            case "NumberOfFilledFields" -> Dsl.numberOfFilledFields(ops).gt(0);
            case "MaxValue@date" -> Dsl.latestOf(first, rest).after(M.date("/Item/Released"));
            case "MinValue@date" -> Dsl.earliestOf(first, rest).before(M.date("/Item/Released"));
            default -> throw new IllegalStateException(row.carrier());
        };
    }

    private static io.github.mbackschat.a12.dm.rulekit.dsl.Condition bound(Row row) {
        if (row.carrier().equals("NumberOfFilledGroups")) {
            List<GroupRef> groups = groups(row.list());
            GroupRef first = groups.getFirst();
            if (groups.size() == 1) {
                return (isRoot(first) ? M.numberOfFilledRootGroups(first) : M.numberOfFilledGroups(first)).gt(0);
            }
            return M.numberOfFilledGroups(first, groups.get(1), restOf(groups, 2)).gt(0);
        }
        if (row.carrier().equals("NumberOfFilledFields@groups")) {
            List<GroupRef> groups = groups(row.list());
            return M.numberOfFilledFields(groups.getFirst(), restOf(groups, 1)).gt(0);
        }
        List<AggregateOperand> ops = operands(row.list());
        AggregateOperand first = ops.getFirst();
        AggregateOperand[] rest = ops.subList(1, ops.size()).toArray(AggregateOperand[]::new);
        return switch (row.carrier()) {
            case "Sum" -> M.sum(first, rest).gt(0);
            case "MaxValue" -> M.maxValue(first, rest).gt(0);
            case "MinValue" -> M.minValue(first, rest).gt(0);
            case "NumberOfFilledFields" -> M.numberOfFilledFields(first, ops.get(1),
                    ops.subList(2, ops.size()).toArray(AggregateOperand[]::new)).gt(0);
            case "NumberOfDifferentValues" -> M.numberOfDifferentValues(first, rest).gt(0);
            case "MaxValue@date" -> M.latestOf(first, rest).after(M.date("/Item/Released"));
            case "MinValue@date" -> M.earliestOf(first, rest).before(M.date("/Item/Released"));
            default -> throw new IllegalStateException(row.carrier());
        };
    }

    private static List<AggregateOperand> operands(String list) {
        return Arrays.stream(list.split(", ")).map(EntityListOperandReadLawsTest::operand).toList();
    }

    /** One token: a plain field or group of Item, a slash-led root, or either keyed by a literal or a field. */
    private static AggregateOperand operand(String token) {
        int keyAt = token.indexOf(" For ");
        String name = keyAt < 0 ? token : token.substring(0, keyAt);
        String path = name.startsWith("/") ? name : "/Item/" + name;
        boolean isGroup = M.groups().anyMatch(g -> g.path().equals(path.replace("*", "")));
        if (keyAt < 0) {
            return isGroup ? M.flattenGroup(path) : M.field(path);
        }
        String key = token.substring(keyAt + " For ".length());
        if (isGroup) {
            return new GroupSemanticIndexValue(M.flattenGroup(path), key.substring(1, key.length() - 1));
        }
        return key.startsWith("\"") ? new SemanticIndexValue(M.field(path), key.substring(1, key.length() - 1))
                : new SemanticIndexFieldValue(M.field(path), M.field("/Item/" + key));
    }

    private static List<GroupRef> groups(String list) {
        return Arrays.stream(list.split(", "))
                .map(token -> M.group((token.startsWith("/") ? token : "/Item/" + token).replace("*", "")))
                .toList();
    }

    private static GroupRef[] restOf(List<GroupRef> groups, int from) {
        return groups.subList(from, groups.size()).toArray(GroupRef[]::new);
    }

    private static boolean isRoot(GroupRef group) {
        return group.path().value().lastIndexOf('/') == 0;
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

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String UNCODED = "uncoded";
    private static final String DUPLICATE = "MVK_DUPLICATE_PARAM1";
}
