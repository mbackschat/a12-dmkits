package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * What a value-list quantifier's FIELD list may carry: one base type, and only a countable few of them.
 *
 * <p>{@code CheckFeldListeUndWerteListenBedingungImpl.check} asks its list two questions in a fixed order —
 * do all the expanded fields share a base type ({@code MVK_VARYING_TYPES_NOT_ALLOWED}), and is each of them
 * a string, an enumeration or a number ({@code MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED}) — and then asks a third
 * of the value list opposite them ({@code MVK_INVALID_TYPES_FOR_COMPARISON}). The order is what makes the
 * grid necessary: a list of two Dates fails the second question and not the first, and a Date beside a text
 * field fails the first, so one column would attribute both to whichever gate happened to answer.
 *
 * <p><b>Most rows are written as raw condition text, deliberately.</b> The typed quantifiers are kind-typed
 * ({@code StringRef}, {@code EnumRef}, {@code GroupListRef} overloads), so a mixed-kind list is not
 * representable through them at all — these shapes reach a checker only as stored DM-JSON, which is exactly
 * the input the portable checker reads. The admitted control is authored through {@link Dsl} so the grid
 * still shows the typed path reaching the same verdict.
 */
class ValueListQuantifierOperandKindLawsTest {

    private static final String GROUP = "/Order";

    private static final String ORDER_JSON = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order", g -> {
                g.stringField("Reference", f -> { });
                g.stringField("GiftNote", f -> { });
                g.numberField("Quantity", n -> n.fractionDigits(0));
                g.numberField("Backordered", n -> n.fractionDigits(0));
                g.field("Expedited", FieldKind.BOOLEAN);
                g.field("GiftWrapped", FieldKind.BOOLEAN);
                g.field("PlacedOn", FieldKind.DATE);
                g.field("ShippedOn", FieldKind.DATE);
                g.enumField("Channel", e -> {
                    e.value("WEB");
                    e.value("STORE");
                });
                g.enumField("Origin", e -> {
                    e.value("WEB");
                    e.value("PARTNER");
                });
            }).build().toJson();

    private static final BoundModel ORDER = BoundModel.ofJson(ORDER_JSON);

    /** The typed path's own rendering, so the admitted control does not rest on hand-written text. */
    private static String typedVerdict(Condition call) {
        Condition guarded = Dsl.fieldFilled(ORDER.field("/Order/Reference")).and(call);
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference",
                new DslRenderer(ConditionLanguage.EN, GROUP).render(guarded));
    }

    private static String verdict(String call) {
        return KernelLaws.verdictModel(ORDER_JSON, GROUP, "../Reference", "FieldFilled(Reference) And " + call);
    }

    /** The base-type gate, and the kind gate that runs straight after it. */
    @Test
    void whatTheFieldListMayCarry() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two text declarations — the typed path",
                typedVerdict(Dsl.atLeastOneFieldValueIncludedInValueList(List.of("A", "B"),
                        ORDER.str("/Order/GiftNote"), ORDER.str("/Order/Reference"))));
        actual.put("a text declaration beside a number one",
                verdict("AtLeastOneFieldValueIncludedInValueList(GiftNote, Quantity In \"A\", \"B\")"));
        actual.put("two number declarations, against a literal list",
                verdict("AtLeastOneFieldValueIncludedInValueList(Quantity, Backordered In \"1\", \"2\")"));
        actual.put("two Boolean declarations",
                verdict("AtLeastOneFieldValueIncludedInValueList(Expedited, GiftWrapped In \"A\", \"B\")"));
        actual.put("two date declarations",
                verdict("AtLeastOneFieldValueIncludedInValueList(PlacedOn, ShippedOn In \"A\", \"B\")"));
        actual.put("two enumeration declarations",
                verdict("AtLeastOneFieldValueIncludedInValueList(Channel, Origin In \"WEB\")"));
        actual.put("a text declaration beside a number one, under another quantifier",
                verdict("NoFieldValueIncludedInValueList(GiftNote, Quantity In \"A\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two text declarations — the typed path", KernelLaws.ADMITTED);
        expected.put("a text declaration beside a number one", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("two number declarations, against a literal list", "MVK_INVALID_TYPES_FOR_COMPARISON");
        expected.put("two Boolean declarations", "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        expected.put("two date declarations", "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED");
        expected.put("two enumeration declarations", KernelLaws.ADMITTED);
        expected.put("a text declaration beside a number one, under another quantifier",
                "MVK_VARYING_TYPES_NOT_ALLOWED");
        assertEquals(expected, actual, "what the field list may carry");
    }

    /** The VALUE list is measured against the first field's type, and a repeat pre-empts every gate. */
    @Test
    void whatTheValueListMayCarryAndWhatPreEmptsTheWholeWalk() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an entity value list of the same base type",
                verdict("AtLeastOneFieldValueIncludedInValueList(GiftNote In Reference)"));
        actual.put("an entity value list of a different base type",
                verdict("AtLeastOneFieldValueIncludedInValueList(GiftNote In Quantity)"));
        actual.put("the same declaration listed twice in the field list",
                verdict("AtLeastOneFieldValueIncludedInValueList(GiftNote, GiftNote In \"A\")"));
        actual.put("a declaration repeated across the field and value lists",
                verdict("AtLeastOneFieldValueIncludedInValueList(GiftNote In GiftNote)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an entity value list of the same base type", KernelLaws.ADMITTED);
        expected.put("an entity value list of a different base type", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("the same declaration listed twice in the field list", "MVK_DUPLICATE_PARAM1");
        expected.put("a declaration repeated across the field and value lists", "MVK_DUPLICATE_PARAM1");
        assertEquals(expected, actual, "what the value list may carry, and what pre-empts the walk");
    }
}
