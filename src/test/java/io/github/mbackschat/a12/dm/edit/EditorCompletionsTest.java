package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import io.github.mbackschat.a12.dm.modelkit.SortOrder;

/**
 * F7 — editor + session completions (DEV-API-GAPS §2). The editors lagged the session surface: {@link
 * GroupEditor} could not set label/repeatable/index-field/sort-fields, {@link FieldEditor} could not set
 * helper-text/global/transient/required-error-messages nor apply a {@link FieldModification}, and neither
 * {@code inlineInclude} (the inverse of {@link EditSession#extractGroupAsInclude}) nor {@code setModelId} had
 * a session verb. These lock the newly-forwarded surface end to end.
 */
class EditorCompletionsTest {

    @Test
    void groupEditorSetsLabelRepeatableIndexAndSortFields() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        s.addGroup("/Subscription", "Lines");
        GroupEditor lines = s.group("/Subscription/Lines");
        lines.addField("Sku", FieldKind.STRING).addField("Amount", FieldKind.NUMBER);
        lines.setLabel(Map.of("en_US", "Order Lines", "de_DE", "Bestellzeilen"))
                .setRepeatable(50)
                .setIndexField("Sku")
                .setSortFields(List.of(new ElementDraft.SortField("Amount", SortOrder.DESC)));
        s.validate();

        ModelDocument doc = ModelDocument.read(s.toJson());
        assertTrue(doc.isRepeating("/Subscription/Lines"), "the group is now repeatable");
        assertEquals(List.of(new ElementDraft.SortField("Amount", SortOrder.DESC)),
                doc.sortFields("/Subscription/Lines"), "the sort field is declared");
        assertTrue(s.toJson().contains("Order Lines"), "the label is written");
    }

    @Test
    void groupEditorCanClearRepeatability() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        s.addGroup("/Subscription", "Notes");
        GroupEditor notes = s.group("/Subscription/Notes");
        notes.addField("Text", FieldKind.STRING).setRepeatable(5).setNonRepeatable();
        s.validate();
        assertFalse(ModelDocument.read(s.toJson()).isRepeating("/Subscription/Notes"), "repeatability cleared");
    }

    @Test
    void fieldEditorSetsHelperGlobalTransientAndRequiredMessages() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        s.field("/Subscription/Addons/MonthlyFee")
                .setHelperText(Map.of("en_US", "Monthly fee in EUR"))
                .setGlobal(true)
                .setTransient(false)
                .setRequiredness(Requiredness.REQUIRED)
                .setRequiredErrorMessages(Map.of("en_US", "A monthly fee is required.", "de_DE", "Monatsgebühr erforderlich."));
        s.validate();

        String json = s.toJson();
        assertTrue(json.contains("Monthly fee in EUR"), () -> "helper text written: " + json);
        assertTrue(json.contains("\"global\" : true"), () -> "global flag written: " + json);
        assertTrue(json.contains("A monthly fee is required."), () -> "required message written: " + json);
    }

    @Test
    void fieldEditorAppliesAFieldModification() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        s.field("/Subscription/Addons/MonthlyFee").modify(FieldModification.builder()
                .helperText(Map.of("en_US", "set via modify")).global(true).build());
        s.validate();
        assertTrue(s.toJson().contains("set via modify"), "the FieldModification applied through the editor");
    }

    @Test
    void sessionSetsTheModelId() {
        EditSession s = EditSession.read(Fixtures.subscriptionJson());
        s.setModelId("subscription-v2");
        s.validate();
        assertEquals("subscription-v2", ModelDocument.read(s.toJson()).id());
    }

    @Test
    void sessionInlinesAnIncludeBackIntoTheBase() {
        String pricelist = DocumentModelDraft.named("pricelist").locale("en_US")
                .rootGroup("Pricelist", p -> p.field("Currency", FieldKind.STRING)).build().toJson();
        ModelResolver resolver = id -> "pricelist".equals(id) ? pricelist : null;
        EditSession s = EditSession.read(DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c.field("StoreName", FieldKind.STRING)).build().toJson(), resolver);
        s.addInclude("pricelist", "pricelist", "/Catalog", "Retail");
        s.validate();

        s.inlineInclude("pricelist", pricelist);   // the inverse of extractGroupAsInclude — fold the content back
        s.validate();

        ModelDocument doc = ModelDocument.read(s.toJson());
        assertTrue(doc.groupPaths().contains("/Catalog/Retail"), "the mounted content is now a real group");
        assertTrue(doc.includes().isEmpty(), "the header include reference is dropped");
    }
}
