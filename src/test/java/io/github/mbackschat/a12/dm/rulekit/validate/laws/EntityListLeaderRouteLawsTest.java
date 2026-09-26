package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS [KF389] — A12 types an entity list by its LEADING field and then asks one route's questions,
 * held beside what rulekit cites for the same list through each factory that builds it.
 *
 * <p>{@code MaxValue}, {@code MinValue} and {@code NumberOfDifferentValues} route by the first expanded field:
 * a date-like leader sends the list through one walk that asks each operand, in turn, for a date-like type, a
 * fully known date and the leader's component set; any other leader through a kind check whose one class
 * answers for every operand. So the class names the ROUTE, not the offender, and the walk answers by POSITION.
 *
 * <p><b>The oracle is the kernel column; the rulekit columns are held to it by one law.</b> Every code rulekit
 * cites must be the kernel's own for that list. {@link #everyCitationIsTheKernelsOwnClass} checks that
 * mechanically, so a column cannot drift into citing a class it has no basis for. An {@code uncoded} refusal
 * answers two ways: withheld, where the kernel refuses for something the factory cannot see, and the factory's
 * own routing refusal, where the kernel admits the list as the OTHER extremum type. The law cannot tell those
 * apart, so the column assertions pin each one. Model-free, a factory sees only kinds: it
 * cites the non-date route's class and withholds the walk's, whose earlier operands' precision and component
 * set it cannot read. Model-bound, the {@link BoundModel} factories walk the list in A12's order.
 *
 * <p>Each kernel row's comparand matches its leader's class, because a bare {@code > 0} beside a date
 * extremum is refused by the comparison gate instead ({@code ExtremumOperandKindGridLawsTest}).
 */
class EntityListLeaderRouteLawsTest {

    private static final String MODEL = DocumentModelDraft.named("stockroom").locale("en_US")
            .rootGroup("Item", g -> {
                g.stringField("Title", f -> { });
                g.stringField("Label", f -> { });
                g.numberField("Weight", f -> f.fractionDigits(0, 3));
                g.numberField("Excess", f -> f.fractionDigits(0, 2));
                g.dateField("Released", f -> f.format("dd.MM.yyyy"));
                g.dateField("Withdrawn", f -> f.format("dd.MM.yyyy"));
                g.dateField("CoverFrom", f -> f.format("yyyy-MM"));
                g.dateField("ApproxDay", f -> f.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL));
                g.field("Discontinued", FieldKind.BOOLEAN);
                g.field("Sealed", FieldKind.CONFIRM);
                g.dateRangeField("Window", f -> f.format("yyyy-MM-dd").separator("/"));
                g.timeField("OpensAt", f -> { });
                g.timeField("ClosesAt", f -> { });
                g.group("Batches", b -> b.repeatable(5)
                        .numberField("Size", f -> f.fractionDigits(0, 0))
                        .numberField("Weighed", f -> f.fractionDigits(0, 0)));
                g.group("DatedBatches", b -> b.repeatable(5)
                        .dateField("PackedOn", f -> f.format("dd.MM.yyyy"))
                        .dateField("BestBefore", f -> f.format("dd.MM.yyyy")));
            })
            // A second root, so a list can name a root group that is not the rule's own.
            .rootGroup("Extra", x -> x.stringField("Code", f -> { }))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The same model with {@code OpensAt}'s format key removed. The draft writes a time field's default format
     * when it declares none, so a stored model is the only way to reach a declaration without one, and A12 loads
     * it ([KF426]).
     */
    private static final String FORMATLESS = withoutFormat(MODEL, "OpensAt");

    private static final BoundModel M_FORMATLESS = BoundModel.ofJson(FORMATLESS);


    private static final String ADMITTED = "ADMITTED";
    private static final String UNCODED = "uncoded";
    private static final String NOT_SORTABLE = "MVK_NOT_SORTABLE";
    private static final String DATE_AND_NONDATE = "MVK_DATE_AND_NONDATE";
    private static final String PARTIAL = "MVK_PARTIAL_DATE_NOT_ALLOWED";
    private static final String FORMATS = "MVK_DATEFORMATS_NOT_COMPATIBLE";
    private static final String DUPLICATE = "MVK_DUPLICATE_PARAM1";
    private static final String ROOT = "MVK_ROOT_GROUP_REFERENCED";
    private static final String ARITY = "MVK_PARAMSIZE_INVALIDN";
    private static final String STRING_ENUM = "MVK_STRING_ENUM_AND_NON_STRING_ENUM";
    private static final String NUMBER_AND_NON = "MVK_NUMBER_AND_NON_NUMBER";
    private static final String NOT_COMPARABLE = "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED";

    /** One row: the list, A12's verdict for it, and what each rulekit factory answers. */
    private record Row(String list, String kernel, String modelFree, String modelBound) {}

    /** {@code MaxValue} through the NUMBER factory, which has no model-bound twin. */
    private static final List<Row> NUMERIC_MAX = List.of(
            new Row("Title, Weight", NOT_SORTABLE, NOT_SORTABLE, null),
            new Row("Weight, Released", NOT_SORTABLE, NOT_SORTABLE, null),
            new Row("Released, Weight", DATE_AND_NONDATE, UNCODED, null),
            // A12 types this list as a DATE extremum; the number factory refuses it on its own authority.
            new Row("Released, Withdrawn", ADMITTED, UNCODED, null),
            // Delegated: a group names no kind, so the number check sees only Weight and passes it.
            new Row("DatedBatches*, Weight", DATE_AND_NONDATE, ADMITTED, null),
            new Row("Batches*, Title", NOT_SORTABLE, UNCODED, null),
            new Row("Weight, Excess", ADMITTED, ADMITTED, null),
            new Row("Weight, Batches*", ADMITTED, ADMITTED, null),
            // The root-group gate sits after duplicates and before every kind gate, beside any operand. A root is
            // a group, whose emptiness A12 reads first, so model-free the class is withheld
            // (EntityListOperandReadLawsTest).
            new Row("Title, /Extra", ROOT, UNCODED, null),
            new Row("Weight, /Extra", ROOT, UNCODED, null),
            new Row("Weight, Weight, /Extra", DUPLICATE, UNCODED, null));

    /** {@code Sum}, which asks one number question in no route: its class does not move with the leader. */
    private static final List<Row> SUM = List.of(
            new Row("Released, Weight", "MVK_NO_NUMBER", "MVK_NO_NUMBER", null),
            new Row("Weight, /Extra", ROOT, UNCODED, null));

    /** {@code MaxValue} through the DATE factory, model-free and model-bound. */
    private static final List<Row> DATE_MAX = List.of(
            new Row("Weight, Released", NOT_SORTABLE, NOT_SORTABLE, NOT_SORTABLE),
            new Row("Title, ApproxDay", NOT_SORTABLE, NOT_SORTABLE, NOT_SORTABLE),
            new Row("Released, Weight", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            // A12's NUMERIC extremum; the date factory refuses it on its own authority.
            new Row("Weight, Excess", ADMITTED, UNCODED, UNCODED),
            new Row("Weight, Batches*", ADMITTED, UNCODED, UNCODED),
            new Row("Released, Withdrawn", ADMITTED, ADMITTED, ADMITTED),
            new Row("Released, Title, CoverFrom", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("Released, CoverFrom, Title", FORMATS, UNCODED, FORMATS),
            new Row("Released, CoverFrom, ApproxDay", FORMATS, ADMITTED, FORMATS),
            new Row("Released, ApproxDay, CoverFrom", PARTIAL, ADMITTED, PARTIAL),
            new Row("ApproxDay, Released, Released", DUPLICATE, DUPLICATE, DUPLICATE),
            new Row("ApproxDay", ARITY, ARITY, ARITY),
            new Row("ApproxDay, Title", PARTIAL, UNCODED, PARTIAL),
            // A group is walked as its expansion, in its place, as A12 walks it (EntityListGroupExpansionKindLawsTest).
            new Row("Released, DatedBatches*, Title", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("DatedBatches*, Weight", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("DatedBatches*, ApproxDay", PARTIAL, ADMITTED, PARTIAL),
            new Row("Title, /Extra", ROOT, UNCODED, ROOT),
            new Row("Released, Title, /Extra", ROOT, UNCODED, ROOT));

    /** {@code MinValue} and the TIME factory — the same walk on the other extremum and the other class. */
    private static final List<Row> OTHER_EXTREMA = List.of(
            new Row("MinValue: Released, Weight", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("MinValue: Released, /Extra", ROOT, UNCODED, ROOT),
            new Row("MaxValue time: OpensAt, Weight", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("MaxValue time: OpensAt, ClosesAt", ADMITTED, ADMITTED, ADMITTED));

    /**
     * A leader declaring no format: the walk has no witness set, yet A12 asks the next operand's type and
     * precision before any comparison, so those two classes stay citable.
     */
    private static final List<Row> FORMATLESS_LEADER = List.of(
            new Row("OpensAt, Weight", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("OpensAt, ClosesAt", ADMITTED, ADMITTED, ADMITTED),
            // With no witness set the walk cannot ask Released's component set, which A12 asks first.
            new Row("OpensAt, Released, Weight", FORMATS, UNCODED, UNCODED),
            // The other side: a declared leader, then an operand declaring no format, is left to the kernel.
            new Row("ClosesAt, OpensAt", ADMITTED, ADMITTED, ADMITTED));

    /** {@code NumberOfDifferentValues}, whose non-date leaders each select a class of their own. */
    private static final List<Row> DISTINCT = List.of(
            new Row("Label, Discontinued", STRING_ENUM, STRING_ENUM, STRING_ENUM),
            new Row("Discontinued, Label", NOT_COMPARABLE, NOT_COMPARABLE, NOT_COMPARABLE),
            new Row("Weight, Discontinued", NUMBER_AND_NON, NUMBER_AND_NON, NUMBER_AND_NON),
            new Row("Released, Label", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("Batches*, Label, Released", NUMBER_AND_NON, UNCODED, NUMBER_AND_NON),
            new Row("Label, ApproxDay", STRING_ENUM, STRING_ENUM, STRING_ENUM),
            new Row("ApproxDay, Label", PARTIAL, UNCODED, PARTIAL),
            new Row("Released, CoverFrom, Label", FORMATS, UNCODED, FORMATS),
            new Row("Released, Label, CoverFrom", DATE_AND_NONDATE, UNCODED, DATE_AND_NONDATE),
            new Row("ApproxDay, Released, Released", DUPLICATE, DUPLICATE, DUPLICATE),
            new Row("ApproxDay", ARITY, ARITY, ARITY),
            new Row("Weight, Sealed", NUMBER_AND_NON, NUMBER_AND_NON, NUMBER_AND_NON),
            new Row("Label, Window", STRING_ENUM, STRING_ENUM, STRING_ENUM),
            new Row("Window, Label", NOT_COMPARABLE, NOT_COMPARABLE, NOT_COMPARABLE),
            // A hidden group leader with one class among the fields: admitted, the positive control of the
            // hidden-leader branch.
            new Row("Batches*, Weight", ADMITTED, ADMITTED, ADMITTED),
            new Row("Label, Discontinued, /Extra", ROOT, UNCODED, ROOT),
            new Row("Weight, Label, /Extra", ROOT, UNCODED, ROOT));

    /** The comparands every row borrows, admitted on their own — the liveness control for the kernel column. */
    @Test
    void theComparandsAreAdmittedAlone() {
        assertEquals(ADMITTED, kernel("[Released] == [Released]"));
        assertEquals(ADMITTED, kernel("[OpensAt] == [OpensAt]"));
    }

    @Test
    void theKernelTypesEachListByItsLeadingField() {
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        NUMERIC_MAX.forEach(row -> record(expected, actual, "MaxValue", row));
        SUM.forEach(row -> record(expected, actual, "Sum", row));
        DATE_MAX.forEach(row -> record(expected, actual, "MaxValue", row));
        OTHER_EXTREMA.forEach(row -> record(expected, actual, null, row));
        DISTINCT.forEach(row -> record(expected, actual, "NumberOfDifferentValues", row));
        assertEquals(expected, actual);
    }

    @Test
    void theNumberFactoryCitesOnlyTheNonDateRoutesClass() {
        assertEquals(column(NUMERIC_MAX, Row::modelFree), answers(NUMERIC_MAX, ops -> () -> Dsl.maxValue(
                ops.getFirst(), rest(ops))));
    }

    @Test
    void sumCitesItsOneClassWhateverLeads() {
        assertEquals(column(SUM, Row::modelFree), answers(SUM, ops -> () -> Dsl.sum(ops.getFirst(), rest(ops))));
    }

    @Test
    void theModelFreeDateFactoryCitesOnlyTheNonDateRoutesClass() {
        assertEquals(column(DATE_MAX, Row::modelFree),
                answers(DATE_MAX, ops -> () -> Dsl.latestOf(ops.getFirst(), rest(ops))));
    }

    @Test
    void theModelBoundDateFactoryWalksTheListInA12sOrder() {
        assertEquals(column(DATE_MAX, Row::modelBound),
                answers(DATE_MAX, ops -> () -> M.latestOf(ops.getFirst(), rest(ops))));
    }

    @Test
    void theOtherExtremaWalkTheSameWay() {
        Map<String, String> modelFree = new LinkedHashMap<>();
        Map<String, String> modelBound = new LinkedHashMap<>();
        for (Row row : OTHER_EXTREMA) {
            List<AggregateOperand> ops = operands(row.list().substring(row.list().indexOf(':') + 2));
            boolean min = row.list().startsWith("MinValue");
            modelFree.put(row.list(), verdict(() -> min
                    ? Dsl.earliestOf(ops.getFirst(), rest(ops)) : Dsl.latestTimeOf(ops.getFirst(), rest(ops))));
            modelBound.put(row.list(), verdict(() -> min
                    ? M.earliestOf(ops.getFirst(), rest(ops)) : M.latestTimeOf(ops.getFirst(), rest(ops))));
        }
        assertEquals(column(OTHER_EXTREMA, Row::modelFree), modelFree);
        assertEquals(column(OTHER_EXTREMA, Row::modelBound), modelBound);
    }

    @Test
    void aLeaderDeclaringNoFormatStillHasItsNextOperandTyped() {
        Map<String, String> kernel = new LinkedHashMap<>();
        Map<String, String> modelFree = new LinkedHashMap<>();
        Map<String, String> modelBound = new LinkedHashMap<>();
        for (Row row : FORMATLESS_LEADER) {
            List<AggregateOperand> ops = operands(M_FORMATLESS, row.list());
            kernel.put(row.list(), KernelLaws.verdictModel(FORMATLESS, "/Item", "../Title",
                    "FieldFilled(Title) And MaxValue(" + row.list() + ") == [OpensAt]"));
            modelFree.put(row.list(), verdict(() -> Dsl.latestTimeOf(ops.getFirst(), rest(ops))));
            modelBound.put(row.list(), verdict(() -> M_FORMATLESS.latestTimeOf(ops.getFirst(), rest(ops))));
        }
        assertEquals(column(FORMATLESS_LEADER, Row::kernel), kernel);
        assertEquals(column(FORMATLESS_LEADER, Row::modelFree), modelFree);
        assertEquals(column(FORMATLESS_LEADER, Row::modelBound), modelBound);
    }

    @Test
    void numberOfDifferentValuesCitesTheLeadersClass() {
        assertEquals(column(DISTINCT, Row::modelFree),
                answers(DISTINCT, ops -> () -> Dsl.numberOfDifferentValues(ops.getFirst(), rest(ops))));
        assertEquals(column(DISTINCT, Row::modelBound),
                answers(DISTINCT, ops -> () -> M.numberOfDifferentValues(ops.getFirst(), rest(ops))));
    }

    /**
     * The law the columns above are instances of: a code rulekit cites is always the kernel's own for that
     * list. Withholding ({@code uncoded}) is allowed where the kernel refuses, and so is admitting a list the
     * kernel refuses for model data the factory cannot see; citing any OTHER code is not.
     */
    @Test
    void everyCitationIsTheKernelsOwnClass() {
        List<Row> all = new ArrayList<>();
        all.addAll(NUMERIC_MAX);
        all.addAll(SUM);
        all.addAll(DATE_MAX);
        all.addAll(OTHER_EXTREMA);
        all.addAll(FORMATLESS_LEADER);
        all.addAll(DISTINCT);
        for (Row row : all) {
            for (String client : new String[] {row.modelFree(), row.modelBound()}) {
                if (client != null && client.startsWith("MVK_")) {
                    assertEquals(row.kernel(), client, row.list());
                }
            }
        }
        assertTrue(all.stream().anyMatch(row -> UNCODED.equals(row.modelFree())), "the withholding is exercised");
    }

    private static void record(Map<String, String> expected, Map<String, String> actual, String op, Row row) {
        String operator = op;
        String list = row.list();
        if (operator == null) {
            operator = list.substring(0, list.indexOf(':')).replace(" time", "");
            list = list.substring(list.indexOf(':') + 2);
        }
        String label = operator + "(" + list + ")";
        expected.put(label, row.kernel());
        actual.put(label, kernel(label + comparand(operator, list)));
    }

    /** The comparison each row's result takes, matched to the leader's class. */
    private static String comparand(String operator, String list) {
        // Both yield a number whatever leads, so their comparand is numeric on every row.
        if (operator.equals("NumberOfDifferentValues") || operator.equals("Sum")) {
            return " > 0";
        }
        String leader = list.split(", ")[0];
        return switch (leader) {
            case "Released", "ApproxDay", "DatedBatches*" -> " == [Released]";
            case "OpensAt" -> " == [OpensAt]";
            default -> " > 0";
        };
    }

    private static String kernel(String condition) {
        return KernelLaws.verdictModel(MODEL, "/Item", "../Title", "FieldFilled(Title) And " + condition);
    }

    private static Map<String, String> column(List<Row> rows, Function<Row, String> pick) {
        Map<String, String> column = new LinkedHashMap<>();
        rows.forEach(row -> column.put(row.list(), pick.apply(row)));
        return column;
    }

    private static Map<String, String> answers(
            List<Row> rows, Function<List<AggregateOperand>, Supplier<?>> factory) {
        Map<String, String> answers = new LinkedHashMap<>();
        rows.forEach(row -> answers.put(row.list(), verdict(factory.apply(operands(row.list())))));
        return answers;
    }

    private static List<AggregateOperand> operands(String list) {
        return operands(M, list);
    }

    private static List<AggregateOperand> operands(BoundModel model, String list) {
        return java.util.Arrays.stream(list.split(", "))
                .map(name -> name.startsWith("/")
                        ? (AggregateOperand) model.flattenGroup(name)
                        : name.endsWith("*")
                        ? (AggregateOperand) model.flattenGroup("/Item/" + name)
                        : (AggregateOperand) model.field("/Item/" + name))
                .toList();
    }

    private static String withoutFormat(String json, String field) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(json);
            ArrayNode elements = (ArrayNode) root.at("/content/modelRoot/rootGroups/0/Group/elements");
            int removed = 0;
            for (var element : elements) {
                JsonNode fieldType = element.path("Field").path("fieldType");
                if (field.equals(element.path("Field").path("name").asText())
                        || field.equals(element.path("name").asText())) {
                    ((ObjectNode) fieldType.path(fieldType.path("type").asText())).remove("format");
                    removed++;
                }
            }
            if (removed != 1) {
                throw new IllegalStateException("expected one '" + field + "' element, found " + removed);
            }
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static AggregateOperand[] rest(List<AggregateOperand> operands) {
        return operands.subList(1, operands.size()).toArray(AggregateOperand[]::new);
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
