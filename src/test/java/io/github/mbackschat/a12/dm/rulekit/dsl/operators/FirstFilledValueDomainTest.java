package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;

class FirstFilledValueDomainTest {

    @Test
    void rejectsTheConfirmKindThatTheOwnerRejects() {
        BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("approval").locale("en_US")
                .rootGroup("Approval", root -> root.group("Rows", rows -> rows.repeatable(10)
                        .field("Confirmed", FieldKind.CONFIRM)))
                .build().toJson());

        assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(model.flatten("/Approval/Rows*/Confirmed")));
    }

    @Test
    void exposesEveryAdditionalOwnerAdmittedKindThroughTheTypedFacade() {
        BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("approval").locale("en_US")
                .rootGroup("Approval", root -> root
                        .field("Approved", FieldKind.BOOLEAN)
                        .customField("Code", field -> field.type("ApprovalCode"))
                        .dateFragmentField("Month", field -> field.fragmentFormat("MM"))
                        .group("Rows", rows -> rows.repeatable(10)
                                .field("Approved", FieldKind.BOOLEAN)
                                .customField("Code", field -> field.type("ApprovalCode"))
                                .stringField("Label", field -> { })
                                .enumField("Status", field -> field.value("OK").value("WAIT"))
                                .dateField("MonthDate", field -> field.format("MM"))
                                .dateFragmentField("Month", field -> field.fragmentFormat("MM"))))
                .build().toJson());
        DslRenderer renderer = new DslRenderer();

        assertEquals("FirstFilledValue(/Approval/Rows*/Approved) == True", renderer.render(
                Dsl.firstFilledBoolean(model.flatten("/Approval/Rows*/Approved")).isTrue()));
        assertEquals("FirstFilledValue(/Approval/Rows*/Code) == \"OK\"", renderer.render(
                Dsl.firstFilledCustom(model.flatten("/Approval/Rows*/Code")).eq("OK")));
        assertEquals("FirstFilledValue(/Approval/Rows*/Month) == [/Approval/Month]", renderer.render(
                Dsl.firstFilledDateFragment(model.flatten("/Approval/Rows*/Month"))
                        .eq(model.dateFragment("/Approval/Month"))));
        assertEquals("FirstFilledValue(/Approval/Rows*/Code, /Approval/Rows*/Label, /Approval/Rows*/Status) == \"OK\"",
                renderer.render(Dsl.firstFilledCustom(
                        model.flatten("/Approval/Rows*/Code"),
                        model.flatten("/Approval/Rows*/Label"),
                        model.flatten("/Approval/Rows*/Status")).eq("OK")));
        assertEquals("FirstFilledValue(/Approval/Rows*/Month, /Approval/Rows*/MonthDate) == [/Approval/Month]",
                renderer.render(Dsl.firstFilledDateFragment(
                        model.flatten("/Approval/Rows*/Month"),
                        model.flatten("/Approval/Rows*/MonthDate"))
                        .eq(model.dateFragment("/Approval/Month"))));
        assertEquals("FirstFilledValue(/Approval/Rows/Code, /Approval/Rows/Label, /Approval/Rows/Status) == \"OK\"",
                renderer.render(Dsl.firstFilledCustom(
                        model.custom("/Approval/Rows/Code").field(),
                        model.str("/Approval/Rows/Label").field(),
                        model.enumOf("/Approval/Rows/Status").field()).eq("OK")));
        assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(
                        model.field("/Approval/Approved").firstFilledOperand(), List.of()));
    }

    @Test
    void preservesTheOwnersGroupAndSemanticIndexOperandShapes() {
        BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("selection").locale("en_US")
                .rootGroup("Selection", root -> root
                        .numberField("Fallback", field -> { })
                        .stringField("Expected", field -> { })
                        .enumField("Priority", field -> field.value("STANDARD").value("EXPRESS")
                                .category("Speed", "NORMAL", "FAST"))
                        .group("Address", address -> address
                                .stringField("Street", field -> { })
                                .stringField("City", field -> { }))
                        .group("Rows", rows -> rows.repeatable(10).indexField("Key")
                                .stringField("Key", field -> { })
                                .numberField("Primary", field -> { })
                                .numberField("Secondary", field -> { })
                                .enumField("Priority", field -> field.value("STANDARD").value("EXPRESS")
                                        .category("Speed", "NORMAL", "FAST")))
                        .group("NumberRows", rows -> rows.repeatable(10)
                                .numberField("Primary", field -> { })
                                .numberField("Secondary", field -> { }))
                        .group("FilterRows", rows -> rows.repeatable(10)
                                .numberField("Candidate", field -> { })
                                .stringField("Key", field -> { })
                                .enumField("Priority", field -> field.value("STANDARD").value("EXPRESS")
                                        .category("Speed", "NORMAL", "FAST")))
                        .group("IndexedNumbers", rows -> rows.repeatable(10).indexField("Key")
                                .numberField("Key", field -> { })
                                .numberField("Primary", field -> { })
                                .numberField("Secondary", field -> { })))
                .build().toJson());
        DslRenderer renderer = new DslRenderer();

        assertEquals("FirstFilledValue(/Selection/Address) == \"x\"", renderer.render(
                Dsl.firstFilledString(model.firstFilledGroup("/Selection/Address")).eq("x")));
        assertEquals("FirstFilledValue(/Selection/NumberRows*) > 0", renderer.render(
                Dsl.firstFilledNumber(model.firstFilledGroup("/Selection/NumberRows*")).gt(0)));
        assertEquals("FirstFilledValue(/Selection/Rows/Primary For \"A\", /Selection/Fallback) > 0",
                renderer.render(Dsl.firstFilledNumber(
                        model.num("/Selection/Rows/Primary").semanticIndex("A").index().firstFilledOperand(),
                        model.num("/Selection/Fallback").field().firstFilledOperand()).gt(0)));
        assertEquals("FirstFilledValue(/Selection/Rows/Primary For /Selection/Rows/Key, /Selection/Fallback) > 0",
                renderer.render(Dsl.firstFilledNumber(
                        model.num("/Selection/Rows/Primary")
                                .semanticIndex(model.field("/Selection/Rows/Key")).index().firstFilledOperand(),
                        model.num("/Selection/Fallback").field().firstFilledOperand()).gt(0)));
        assertEquals("FirstFilledValue(/Selection/Priority -> Speed, /Selection/Expected) == \"FAST\"",
                renderer.render(Dsl.firstFilledString(
                        model.enumOf("/Selection/Priority").category("Speed").firstFilledOperand(),
                        model.str("/Selection/Expected").field().firstFilledOperand()).eq("FAST")));
        assertEquals("FirstFilledValue(/Selection/Rows/Priority -> Speed For \"A\", /Selection/Expected)"
                        + " == \"FAST\"",
                renderer.render(Dsl.firstFilledString(
                        model.enumOf("/Selection/Rows/Priority").category("Speed")
                                .firstFilledOperand().forIndex("A"),
                        model.str("/Selection/Expected").field().firstFilledOperand()).eq("FAST")));
        assertEquals("FirstFilledValue(/Selection/FilterRows*/Priority -> Speed Having "
                        + "[/Selection/FilterRows/Key] == \"MATCH\", /Selection/Expected) == \"FAST\"",
                renderer.render(Dsl.firstFilledString(
                        model.flatten("/Selection/FilterRows*/Priority").firstFilledOperand()
                                .withCategory(model.enumOf("/Selection/FilterRows/Priority").category("Speed"))
                                .having(model.str("/Selection/FilterRows/Key").eq("MATCH")),
                        model.str("/Selection/Expected").field().firstFilledOperand()).eq("FAST")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.firstFilledEnum(
                model.enumOf("/Selection/Priority").category("Speed").firstFilledOperand(),
                model.str("/Selection/Expected").field().firstFilledOperand()));
        assertEquals("FirstFilledValue(/Selection/IndexedNumbers For \"1\") > 0", renderer.render(
                Dsl.firstFilledNumber(model.firstFilledGroupFor("/Selection/IndexedNumbers", "1")).gt(0)));
        assertEquals("FirstFilledValue(/Selection/IndexedNumbers For /Selection/Fallback) > 0", renderer.render(
                Dsl.firstFilledNumber(model.firstFilledGroupFor(
                        "/Selection/IndexedNumbers", model.num("/Selection/Fallback").field())).gt(0)));

        var indexed = model.num("/Selection/Rows/Primary").semanticIndex("A").index();
        assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(indexed.firstFilledOperand(),
                        List.of(indexed.firstFilledOperand())));
        assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(model.firstFilledGroup("/Selection/Address"),
                        List.of(model.field("/Selection/Address/Street").firstFilledOperand())));
        assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(model.firstFilledGroup("/Selection"), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> model.firstFilledGroupFor("/Selection/IndexedNumbers*", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> model.flatten("/Selection/Rows*/Priority").firstFilledOperand().forIndex("A"));

        var filtered = model.flatten("/Selection/FilterRows*/Candidate");
        assertThrows(IllegalArgumentException.class, () -> filtered.having(
                Dsl.firstFilledNumber(model.firstFilledGroupFor("/Selection/IndexedNumbers", "1").outer())
                        .gt(0)));
        assertThrows(IllegalArgumentException.class, () -> filtered.having(
                Dsl.firstFilledNumber(model.firstFilledGroupFor(
                        "/Selection/IndexedNumbers", model.num("/Selection/Fallback").field().outer()).outer())
                        .gt(0)));
    }
}
