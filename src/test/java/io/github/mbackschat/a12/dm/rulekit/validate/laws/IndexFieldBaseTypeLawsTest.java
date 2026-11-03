package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;

/**
 * Which pairs of index-field TYPES A12 lets iterate jointly, beyond the String-against-Number pair
 * {@code KERNEL-FINDINGS KF353} measured.
 *
 * <p>Every row is one rule declared in a keyed group, reporting on that group's own {@code Note} and reading a
 * sibling group's {@code Weight}. Every group is keyed by a field of one name, {@code ShipmentKey}, so the
 * pairs differ in the key's declared TYPE and in nothing else, and all of them sit in ONE model, so no row
 * varies the fixture. The source is {@code CheckEntityUtils}'s {@code indexField1.getType().matchBaseType(…)};
 * each type interface admits only itself, except that {@code IStringType} and {@code IPredefinedType} admit each
 * other, and the model mapper builds every temporal kind as one {@code DateTypeImpl} but a date range as its own
 * {@code DateRangeTypeImpl}. Those are predictions, and the rows are what decides them.
 *
 * <p>Typed throughout: the variable is the declaration, never the spelling, so {@link BoundModel} writes every
 * reference.
 */
class IndexFieldBaseTypeLawsTest {

    private static final String ROOT = "/Fulfilment";

    private static final String INVALID_TYPE = "MVK_ERROR_FIELD_INVALID_INDEX_TYPE";

    private static final String JSON = DocumentModelDraft.named("keyed-shipments").locale("en_US")
            .rootGroup("Fulfilment", root -> root
                    .group("Summary", s -> s.field("Remark", FieldKind.STRING)
                            .numberField("Total", n -> n.fractionDigits(0))
                            .field("ShipDate", FieldKind.DATE)
                            .dateTimeField("ShipMoment", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                            .dateRangeField("ShipWindow", r -> r.format("yyyy-MM-dd").separator("/")))
                    .group("Returns", r -> r.repeatable(5).numberField("Weight", n -> n.fractionDigits(0)))
                    .group("Invoices", keyed(k -> k.field("ShipmentKey", FieldKind.STRING)))
                    .group("ByText", keyed(k -> k.field("ShipmentKey", FieldKind.STRING)))
                    .group("ByCustom", keyed(k -> k.customField("ShipmentKey", f -> f.type("CarrierCode"))))
                    .group("ByNumber", keyed(k -> k.numberField("ShipmentKey", n -> n.fractionDigits(0))))
                    .group("ByNumberAlt", keyed(k -> k.numberField("ShipmentKey", n -> n.fractionDigits(0, 2))))
                    .group("ByCode", keyed(k -> k.enumField("ShipmentKey", e -> e.value("A").value("B"))))
                    .group("ByCodeAlt", keyed(k -> k.enumField("ShipmentKey", e -> e.value("X").value("Y"))))
                    .group("ByFlag", keyed(k -> k.field("ShipmentKey", FieldKind.BOOLEAN)))
                    .group("ByConfirm", keyed(k -> k.field("ShipmentKey", FieldKind.CONFIRM)))
                    .group("ByDate", keyed(k -> k.field("ShipmentKey", FieldKind.DATE)))
                    .group("ByMoment", keyed(k -> k.dateTimeField("ShipmentKey",
                            f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))))
                    .group("ByTime", keyed(k -> k.timeField("ShipmentKey", f -> f.format("HH:mm:ss"))))
                    .group("ByFragment", keyed(k -> k.dateFragmentField("ShipmentKey", f -> f.fragmentFormat("MM"))))
                    .group("ByRange", keyed(k -> k.dateRangeField("ShipmentKey",
                            r -> r.format("yyyy-MM-dd").separator("/")))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(JSON);

    /** A repeatable group keyed by {@code ShipmentKey}, whose declaration {@code key} adds. */
    private static Consumer<DocumentModelDraft.GroupBuilder> keyed(Consumer<DocumentModelDraft.GroupBuilder> key) {
        return g -> {
            g.repeatable(5).indexField("ShipmentKey");
            key.accept(g);
            g.field("Note", FieldKind.STRING).numberField("Weight", n -> n.fractionDigits(0));
        };
    }

    /** The rule declared in {@code from}, on its own {@code Note}, reading {@code other}'s {@code Weight}. */
    private static String reading(String from, String other) {
        String group = ROOT + "/" + from;
        String note = group + "/Note";
        Condition condition = Dsl.fieldFilled(M.field(note)).and(M.num(ROOT + "/" + other + "/Weight").gt(0));
        String errorRel = EntityPath.of(group + "/ProbeRule").relativize(EntityPath.of(note));
        return KernelLaws.verdictModel(JSON, group, errorRel, condition);
    }

    /** a12-kernel-lean {@code SPEC-2026-09-25-03}'s first delta (their report), on this fixture. */
    @Test
    void aStringKeyPairsOnlyWithATextKey() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String other : new String[] {"ByText", "ByCustom", "ByNumber", "ByCode", "ByFlag", "ByConfirm",
                "ByDate", "ByMoment", "ByTime", "ByFragment", "ByRange"}) {
            actual.put(other, reading("Invoices", other));
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ByText", KernelLaws.ADMITTED);
        expected.put("ByCustom", KernelLaws.ADMITTED);
        expected.put("ByNumber", INVALID_TYPE);
        expected.put("ByCode", INVALID_TYPE);
        expected.put("ByFlag", INVALID_TYPE);
        expected.put("ByConfirm", INVALID_TYPE);
        expected.put("ByDate", INVALID_TYPE);
        expected.put("ByMoment", INVALID_TYPE);
        expected.put("ByTime", INVALID_TYPE);
        expected.put("ByFragment", INVALID_TYPE);
        expected.put("ByRange", INVALID_TYPE);
        assertEquals(expected, actual, "a String key beside each other kind of key");
    }

    /** The pairs that name no String key, which that entry's limit leaves open. */
    @Test
    void theOtherKindsPairByTheirOwnBaseType() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Number beside Number of another scale", reading("ByNumber", "ByNumberAlt"));
        actual.put("Enumeration beside Enumeration of other values", reading("ByCode", "ByCodeAlt"));
        actual.put("Date beside DateTime", reading("ByDate", "ByMoment"));
        actual.put("Date beside Time", reading("ByDate", "ByTime"));
        actual.put("Date beside a date fragment", reading("ByDate", "ByFragment"));
        actual.put("DateTime beside Time", reading("ByMoment", "ByTime"));
        actual.put("Date beside a date range", reading("ByDate", "ByRange"));
        actual.put("Boolean beside Confirm", reading("ByFlag", "ByConfirm"));
        actual.put("Number beside Enumeration", reading("ByNumber", "ByCode"));
        actual.put("Number beside Date", reading("ByNumber", "ByDate"));
        actual.put("Enumeration beside Boolean", reading("ByCode", "ByFlag"));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Number beside Number of another scale", KernelLaws.ADMITTED);
        expected.put("Enumeration beside Enumeration of other values", KernelLaws.ADMITTED);
        expected.put("Date beside DateTime", KernelLaws.ADMITTED);
        expected.put("Date beside Time", KernelLaws.ADMITTED);
        expected.put("Date beside a date fragment", KernelLaws.ADMITTED);
        expected.put("DateTime beside Time", KernelLaws.ADMITTED);
        expected.put("Date beside a date range", INVALID_TYPE);
        expected.put("Boolean beside Confirm", INVALID_TYPE);
        expected.put("Number beside Enumeration", INVALID_TYPE);
        expected.put("Number beside Date", INVALID_TYPE);
        expected.put("Enumeration beside Boolean", INVALID_TYPE);
        assertEquals(expected, actual, "two keys of which neither is a String");
    }

    /**
     * A {@code $}-marked filter reference where no iteration encloses the filter: from the fixed {@code Summary},
     * reporting on its {@code Remark}, a sum over {@code Returns*} whose body reads another group. The marker
     * names the enclosing row, and there is none, so the question is which class the reference draws.
     * a12-kernel-lean {@code SPEC-2026-09-25-03}'s second delta (their report) is the first two rows.
     */
    @Test
    void aMarkedReferenceWithNoEnclosingIterationDrawsTheWalksClass() {
        NumberRef returned = M.num(ROOT + "/Returns/Weight");
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("marked, into the keyed Invoices", summing(M.num(ROOT + "/Invoices/Weight").outer().eq(returned)));
        actual.put("marked, into the unkeyed filtered group", summing(returned.outer().eq(returned)));
        actual.put("unmarked, into the keyed Invoices", summing(M.num(ROOT + "/Invoices/Weight").eq(returned)));
        actual.put("marked, into the fixed Summary", summing(M.num(ROOT + "/Summary/Total").outer().eq(returned)));
        actual.put("a filter on its own group — control", summing(returned.gt(0)));
        actual.put("the keyed read, outside any filter", fromSummary(M.num(ROOT + "/Invoices/Weight").gt(0)));
        actual.put("the unkeyed read, outside any filter", fromSummary(returned.gt(0)));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("marked, into the keyed Invoices", "MVK_ERROR_FIELD_NO_INDEX");
        expected.put("marked, into the unkeyed filtered group", "MVK_NO_WILDCARD");
        expected.put("unmarked, into the keyed Invoices", "MVK_INVALID_ITERATION_IN_SET_EXPRESSION");
        expected.put("marked, into the fixed Summary", KernelLaws.ADMITTED);
        expected.put("a filter on its own group — control", KernelLaws.ADMITTED);
        expected.put("the keyed read, outside any filter", "MVK_ERROR_FIELD_NO_INDEX");
        expected.put("the unkeyed read, outside any filter", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "a marked reference no iteration encloses");
    }

    /**
     * The same table on A12's other index-type gate, a semantic index's key against the index field
     * ({@code CheckSemanticIndexImpl}, {@code MVK_INDEX_TYPE_INCONSISTENT}). {@code SemanticIndexKeyGateLawsTest}
     * measures it against a String and an Enumeration index field only, so the temporal rows are new there.
     */
    @Test
    void aSemanticIndexKeyMeetsTheSameTable() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a date index, a date key — control", keyed("ByDate", "ShipDate"));
        actual.put("a date index, a date-time key", keyed("ByDate", "ShipMoment"));
        actual.put("a date index, a date-range key", keyed("ByDate", "ShipWindow"));
        actual.put("a date-range index, a date-range key", keyed("ByRange", "ShipWindow"));
        actual.put("a date-range index, a date key", keyed("ByRange", "ShipDate"));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a date index, a date key — control", KernelLaws.ADMITTED);
        expected.put("a date index, a date-time key", KernelLaws.ADMITTED);
        expected.put("a date index, a date-range key", "MVK_INDEX_TYPE_INCONSISTENT");
        expected.put("a date-range index, a date-range key", KernelLaws.ADMITTED);
        expected.put("a date-range index, a date key", "MVK_INDEX_TYPE_INCONSISTENT");
        assertEquals(expected, actual, "a semantic index's key beside the index field");
    }

    /** The rule declared in the fixed {@code Summary}, on its {@code Remark}, reading {@code group} by {@code key}. */
    private static String keyed(String group, String key) {
        return fromSummary(M.num(ROOT + "/" + group + "/Weight").forIndex(M.field(ROOT + "/Summary/" + key)).gt(0));
    }

    /** The rule declared in the fixed {@code Summary}, on its {@code Remark}, summing {@code Returns*} by filter. */
    private static String summing(Condition filter) {
        return fromSummary(Dsl.sum(M.flatten(ROOT + "/Returns*/Weight").having(filter)).gt(0));
    }

    private static String fromSummary(Condition probe) {
        String group = ROOT + "/Summary";
        String remark = group + "/Remark";
        String errorRel = EntityPath.of(group + "/ProbeRule").relativize(EntityPath.of(remark));
        return KernelLaws.verdictModel(JSON, group, errorRel, Dsl.fieldFilled(M.field(remark)).and(probe));
    }
}
