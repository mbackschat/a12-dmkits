package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Rule targets, localized messages, and authored metadata are diff-visible. */
class ModelDiffRuleEnvelopeTest {

    private static final String RULE = "/Invoice/PositiveAmount";

    @Test
    void detectsChangedErrorEntity() {
        ElementChange change = change(ModelDiff.between(ruled("Left", List.of("en_US"), "old"),
                ruled("Right", List.of("en_US"), "old")), RULE);

        assertEquals("/Invoice/Left", delta(change, "errorEntity").before());
        assertEquals("/Invoice/Right", delta(change, "errorEntity").after());
        assertEquals(ModelDiff.Risk.MEDIUM, change.risk());
        assertTrue(change.reasons().contains("REFERENCE_CHANGED"));
    }

    @Test
    void detectsLocalizedMessageAdditionRemovalAndChange() {
        ElementChange changed = change(ModelDiff.between(
                ruled("Left", List.of("en_US"), "old"), ruled("Left", List.of("en_US"), "new")), RULE);
        assertEquals("old en_US", delta(changed, "message.en_US").before());
        assertEquals("new en_US", delta(changed, "message.en_US").after());
        assertEquals(ModelDiff.Risk.LOW, changed.risk());
        assertEquals(List.of("MESSAGE_CHANGED"), changed.reasons());

        ElementChange added = change(ModelDiff.between(
                ruled("Left", List.of("en_US"), "same"),
                ruled("Left", List.of("en_US", "de_DE"), "same")), RULE);
        assertEquals("same de_DE", delta(added, "message.de_DE").after());

        ElementChange removed = change(ModelDiff.between(
                ruled("Left", List.of("en_US", "de_DE"), "same"),
                ruled("Left", List.of("en_US"), "same")), RULE);
        assertEquals("same de_DE", delta(removed, "message.de_DE").before());
    }

    @Test
    void detectsRuleDescriptionsAndAnnotations() {
        String base = ruled("Left", List.of("en_US"), "same");
        EditSession session = EditSession.read(base);
        session.rule(RULE).setInternalDescription("en_US", "Authoring note")
                .setExternalDescription("en_US", "Positive amount")
                .setAnnotation("owner", "billing");

        ElementChange change = change(ModelDiff.between(base, session.validate().toJson()), RULE);
        assertEquals(List.of("annotation.owner", "externalDescription.en_US", "internalDescription.en_US"),
                change.deltas().stream().map(ModelDiff.Delta::aspect).sorted().toList());
        assertEquals(ModelDiff.Risk.LOW, change.risk());
        assertEquals(List.of("METADATA_CHANGED"), change.reasons());
    }

    private static String ruled(String errorField, List<String> locales, String messagePrefix) {
        DocumentModelDraft.Builder draft = DocumentModelDraft.named("invoice");
        locales.forEach(draft::locale);
        String base = draft.rootGroup("Invoice", invoice -> invoice
                        .numberField("Left", number -> number.fractionDigits(0))
                        .numberField("Right", number -> number.fractionDigits(0)))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule(model -> {
            var left = model.num("/Invoice/Left");
            var right = model.num("/Invoice/Right");
            List<LocalizedText> messages = new ArrayList<>();
            locales.forEach(locale -> messages.add(new LocalizedText(locale, messagePrefix + " " + locale)));
            return model.rule(model.group("/Invoice")).name("PositiveAmount")
                    .errorField(errorField.equals("Left") ? left : right)
                    .when(left.le(0).or(right.le(0))).code("AMOUNT_POSITIVE").severity(Severity.ERROR)
                    .message(messages.toArray(LocalizedText[]::new)).build();
        });
        return session.validate().toJson();
    }

    private static ElementChange change(ModelDiff diff, String id) {
        return diff.changes().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no change for " + id + " in " + diff.changes()));
    }

    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + aspect + "' delta in " + change.deltas()));
    }
}
