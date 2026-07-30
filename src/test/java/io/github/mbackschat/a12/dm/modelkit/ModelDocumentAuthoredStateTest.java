package io.github.mbackschat.a12.dm.modelkit;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ElementType;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument.AuthoredElement;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Locks the typed, single-pass authored-state boundary consumed by semantic diff. */
class ModelDocumentAuthoredStateTest {

    @Test
    void preservesTheMixedAuthoredElementOrderAcrossEveryElementFamily() {
        String structure = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> order
                        .numberField("Quantity", number -> number.fractionDigits(0))
                        .numberField("Total", number -> number.fractionDigits(0))
                        .group("Notes", notes -> notes.field("Text", FieldKind.STRING)))
                .build().toJson();
        EditSession session = EditSession.read(structure)
                .addRule(model -> model.rule(model.group("/Order")).name("QuantityRule")
                        .errorField(model.num("/Order/Quantity"))
                        .when(model.num("/Order/Quantity").lt(0))
                        .code("QUANTITY_NEGATIVE").severity(Severity.ERROR)
                        .message(en("Quantity must not be negative.")).build())
                .addComputation(model -> model.computation(model.num("/Order/Total")).name("TotalComp")
                        .calc(model.num("/Order/Quantity").value())
                        .message(en("Total is copied from quantity.")).build());

        assertEquals(List.of(
                new AuthoredElement(ElementType.GROUP, "/Order"),
                new AuthoredElement(ElementType.FIELD, "/Order/Quantity"),
                new AuthoredElement(ElementType.FIELD, "/Order/Total"),
                new AuthoredElement(ElementType.GROUP, "/Order/Notes"),
                new AuthoredElement(ElementType.FIELD, "/Order/Notes/Text"),
                new AuthoredElement(ElementType.RULE, "/Order/QuantityRule"),
                new AuthoredElement(ElementType.COMPUTATION, "/Order/TotalComp")),
                ModelDocument.read(session.toJson()).authoredState().elements());
    }

    @Test
    void readsACompleteGroupStateWithoutExposingItsChildrenAsAGroupProperty() {
        String json = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> order.group("Lines", lines -> lines
                        .repeatable(5)
                        .label("en_US", "Order lines")
                        .internalDescription("en_US", "Maintained by purchasing")
                        .externalDescription("en_US", "Purchased items")
                        .annotation("lifecycle", "stable")
                        .field("Sku", FieldKind.STRING)
                        .indexField("Sku")
                        .sortField("Sku", SortOrder.DESC)))
                .build().toJson();

        // tag::authored-state[]
        ModelDocument.AuthoredState authored = ModelDocument.read(json).authoredState();
        authored.elements().forEach(element ->
                System.out.println(element.type() + " " + element.path()));

        ModelDocument.GroupState group = authored.groups().get("/Order/Lines");
        System.out.println(group.structure().repeatability());
        // end::authored-state[]

        assertEquals(5, group.structure().repeatability());
        assertEquals("Sku", group.structure().indexField().orElseThrow());
        assertEquals(List.of(new ElementDraft.SortField("Sku", SortOrder.DESC)), group.structure().sortFields());
        assertEquals("Order lines", group.meta().labels().get("en_US"));
        assertEquals(List.of(new ElementDraft.Annotation("lifecycle", "stable")), group.meta().annotations());
        assertEquals("Maintained by purchasing", group.meta().internalDescription().get("en_US"));
        assertEquals("Purchased items", group.meta().externalDescription().get("en_US"));
    }

    @Test
    void readsCompleteNumberLengthAndIntegerDigitState() {
        String json = DocumentModelDraft.named("components").locale("en_US")
                .rootGroup("Parts", parts -> parts.numberField("Month", number -> number
                        .minLength(1).maxLength(2).maxIntegerDigits(2)))
                .build().toJson();

        FieldTypeSpec.NumberSpec number = (FieldTypeSpec.NumberSpec) ModelDocument.read(json)
                .authoredState().fields().get("/Parts/Month").type();

        assertEquals(1, number.minLength());
        assertEquals(2, number.maxLength());
        assertEquals(2, number.maxIntegerDigits());
    }
}
