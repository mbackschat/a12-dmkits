package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Typed, single-pass authored read-back used by headless model consumers such as semantic diff. */
class ModelDocumentAuthoredFieldRuleStateTest {

    @Test
    void readsCompleteTypedFieldState() {
        String json = DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice
                        .numberField("Amount", amount -> amount
                                .fractionDigits(1, 2).min("0.01").max("9999.99")
                                .unit(NumberTrait.AMOUNT).zeroNotAllowed().leadingZerosAllowed()
                                .required().label("en_US", "Amount").helperText("en_US", "Gross amount")
                                .internalDescription("en_US", "Authoring note")
                                .externalDescription("en_US", "Amount due")
                                .annotation("owner", "billing")))
                .build().toJson();

        ModelDocument.FieldState field = ModelDocument.read(json).authoredState().fields().get("/Invoice/Amount");
        FieldTypeSpec.NumberSpec type = assertInstanceOf(FieldTypeSpec.NumberSpec.class, field.type());

        assertEquals(1, type.minFractionalDigits());
        assertEquals(2, type.maxFractionalDigits());
        assertEquals(NumberTrait.AMOUNT, type.trait());
        assertTrue(type.zeroNotAllowed());
        assertTrue(type.leadingZerosAllowed());
        assertEquals(Requiredness.REQUIRED, field.requirements().requiredness());
        assertEquals(Map.of("en_US", "Amount"), field.meta().labels());
        assertEquals(Map.of("en_US", "Gross amount"), field.meta().helperText());
        assertEquals("billing", field.meta().annotations().getFirst().value());
    }

    @Test
    void readsTypedefAndEnumerationConfigurationWithoutRawJson() {
        String json = DocumentModelDraft.named("claim").locale("en_US")
                .typeDef("ClaimCode", type -> type.string(text -> text.minLength(3).maxLength(12)))
                .rootGroup("Claim", claim -> claim
                        .typedefField("Code", "ClaimCode")
                        .enumField("Status", status -> status
                                .value("OPEN", label -> label.label("en_US", "Open"))
                                .value("CLOSED", label -> label.label("en_US", "Closed"))
                                .category("Lifecycle", "active", "finished")
                                .alphabeticalSorting().invalidValueMessage("Unknown status")))
                .build().toJson();

        ModelDocument.AuthoredState state = ModelDocument.read(json).authoredState();
        FieldTypeSpec.TypeDefRef typeRef = assertInstanceOf(
                FieldTypeSpec.TypeDefRef.class, state.fields().get("/Claim/Code").type());
        FieldTypeSpec.EnumSpec enumeration = assertInstanceOf(
                FieldTypeSpec.EnumSpec.class, state.fields().get("/Claim/Status").type());

        assertEquals("ClaimCode", typeRef.typeDefinitionId());
        assertEquals("OPEN", enumeration.values().getFirst().value());
        assertEquals("Open", enumeration.values().getFirst().labels().get("en_US"));
        assertEquals("Lifecycle", enumeration.categories().getFirst().name());
        assertTrue(enumeration.alphabeticalSorting());
        assertEquals(Map.of("en_US", "Unknown status"), enumeration.invalidValueMessages());
    }

    @Test
    void readsRequiredMessagesAndRuleMetadata() {
        String base = DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", invoice -> invoice.numberField("Amount", amount -> amount.required()))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.field("/Invoice/Amount").setRequiredErrorMessages(Map.of("en_US", "Amount is required"));
        session.addRule(model -> model.rule(model.group("/Invoice"))
                .name("PositiveAmount").errorField(model.num("/Invoice/Amount"))
                .when(model.num("/Invoice/Amount").le(0)).code("AMOUNT_POSITIVE")
                .severity(Severity.ERROR).message(new LocalizedText("en_US", "Amount must be positive")).build());
        session.rule("/Invoice/PositiveAmount")
                .setInternalDescription("en_US", "Checks the invoice amount")
                .setExternalDescription("en_US", "Positive amount")
                .setAnnotation("owner", "billing");

        ModelDocument.AuthoredState state = ModelDocument.read(session.toJson()).authoredState();

        // tag::authored-state[]
        ModelDocument.AuthoredState authored = ModelDocument.read(session.toJson()).authoredState();
        authored.elements().forEach(element ->
                System.out.println(element.type() + " " + element.path()));

        ModelDocument.GroupState group = authored.groups().get("/Invoice");
        System.out.println(group.structure().repeatability());

        ModelDocument.FieldState field = authored.fields().get("/Invoice/Amount");
        System.out.println(field.type() + " " + field.requirements().requiredness());

        ModelDocument.RuleState rule = authored.rules().get("/Invoice/PositiveAmount");
        System.out.println(rule.meta().annotations());
        // end::authored-state[]

        assertEquals(Map.of("en_US", "Amount is required"),
                state.fields().get("/Invoice/Amount").requirements().messages());
        ModelDocument.RuleState storedRule = state.rules().get("/Invoice/PositiveAmount");
        assertEquals(Map.of("en_US", "Checks the invoice amount"), storedRule.meta().internalDescription());
        assertEquals(Map.of("en_US", "Positive amount"), storedRule.meta().externalDescription());
        assertEquals("billing", storedRule.meta().annotations().getFirst().value());
    }
}
