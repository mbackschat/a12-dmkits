package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.core.ModelFormat;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/** The complete authored model configuration is one stable semantic-diff element. */
class ModelDiffConfigTest {

    @Test
    void projectsModelIdentityAndVersionAndDiffsIdentityUnderAStableId() {
        String base = model("orders");
        var state = ModelDiffState.load(base, null);
        assertEquals(ModelFormat.VERSION,
                ConfigurationDiffProjector.project(state).get("model").get("modelVersion"));

        EditSession head = EditSession.read(base);
        head.setModelId("purchases").validate();

        ElementChange config = configChange(ModelDiff.between(base, head.toJson()));
        assertEquals("model", config.id());
        assertEquals("orders", delta(config, "id").before());
        assertEquals("purchases", delta(config, "id").after());
        assertEquals(ModelDiff.Risk.MEDIUM, config.risk());
        assertEquals(List.of("CONFIG_CHANGED"), config.reasons());
    }

    @Test
    void detectsEveryScalarConfigurationSlot() {
        String base = model("orders");
        EditSession head = EditSession.read(base);
        head.config()
                .decimalSeparator(",")
                .timeZone("UTC")
                .conditionLanguage("de_DE")
                .fieldRefByShortNameAllowed(false)
                .baseYear(1995);
        head.validate();

        ElementChange config = configChange(ModelDiff.between(base, head.toJson()));
        assertDelta(config, "decimalSeparator", ".", ",");
        assertDelta(config, "timeZone", "Europe/Berlin", "UTC");
        assertDelta(config, "conditionLanguage", "en_US", "de_DE");
        assertDelta(config, "fieldRefByShortNameAllowed", "true", "false");
        assertDelta(config, "baseYear", null, "1995");
        assertEquals(ModelDiff.Risk.MEDIUM, config.risk());
        assertEquals(List.of("CONFIG_CHANGED"), config.reasons());
    }

    @Test
    void classifiesSupportedCharacterExpansionAndRestrictionWithoutOrderNoise() {
        String unrestricted = model("orders");
        String letters = modelWithCharacters("A-Z");

        ElementChange restrictDefault = configChange(ModelDiff.between(unrestricted, letters));
        assertEquals(ModelDiff.Risk.MEDIUM, restrictDefault.risk());
        assertEquals(List.of("CONSTRAINT_TIGHTENED"), restrictDefault.reasons());
        ElementChange restoreDefault = configChange(ModelDiff.between(letters, unrestricted));
        assertEquals(ModelDiff.Risk.HIGH, restoreDefault.risk());
        assertEquals(List.of("CONSTRAINT_LOOSENED"), restoreDefault.reasons());

        EditSession expanded = EditSession.read(letters);
        expanded.config().supportedCharacters("A-Z", "0-9");
        expanded.validate();

        ElementChange loosening = configChange(ModelDiff.between(letters, expanded.toJson()));
        assertDelta(loosening, "supportedCharacters", "[\"A-Z\"]", "[\"0-9\",\"A-Z\"]");
        assertEquals(ModelDiff.Risk.HIGH, loosening.risk());
        assertEquals(List.of("CONSTRAINT_LOOSENED"), loosening.reasons());

        ElementChange tightening = configChange(ModelDiff.between(expanded.toJson(), letters));
        assertEquals(ModelDiff.Risk.MEDIUM, tightening.risk());
        assertEquals(List.of("CONSTRAINT_TIGHTENED"), tightening.reasons());

        EditSession reordered = EditSession.read(expanded.toJson());
        reordered.config().supportedCharacters("0-9", "A-Z");
        reordered.validate();
        assertTrue(ModelDiff.between(expanded.toJson(), reordered.toJson()).changes().isEmpty(),
                "set-like configuration order must not create review noise");
    }

    @Test
    void distinguishesLocaleAdditionFromRemoval() {
        String base = model("orders");
        EditSession bilingual = EditSession.read(base);
        bilingual.addLocale("de_DE").validate();

        ElementChange added = configChange(ModelDiff.between(base, bilingual.toJson()));
        assertDelta(added, "locales", "[\"en_US\"]", "[\"de_DE\",\"en_US\"]");
        assertEquals(ModelDiff.Risk.LOW, added.risk());
        assertEquals(List.of("LOCALE_ADDED"), added.reasons());

        ElementChange removed = configChange(ModelDiff.between(bilingual.toJson(), base));
        assertEquals(ModelDiff.Risk.MEDIUM, removed.risk());
        assertEquals(List.of("LOCALE_REMOVED"), removed.reasons());
    }

    @Test
    void detectsLabelsCommentAndAnnotationsButCanonicalizesRoles() {
        String base = DocumentModelDraft.named("orders").locale("en_US")
                .label("en_US", "Orders")
                .comment("Internal draft")
                .annotation("owner", "sales")
                .annotation("roles", "admin,auditor")
                .rootGroup("Order", order -> order.stringField("Number", field -> { }))
                .build().toJson();
        EditSession head = EditSession.read(base);
        head.config()
                .label("en_US", "Purchases")
                .comment("Ready for review")
                .annotation("owner", "procurement")
                .annotation("roles", "auditor, admin");
        head.validate();

        ElementChange config = configChange(ModelDiff.between(base, head.toJson()));
        assertDelta(config, "label.en_US", "Orders", "Purchases");
        assertDelta(config, "comment", "Internal draft", "Ready for review");
        assertDelta(config, "annotation.owner", "sales", "procurement");
        assertFalse(config.deltas().stream().anyMatch(candidate -> candidate.aspect().equals("roles")),
                () -> "role order and spacing are not semantic: " + config.deltas());
        assertEquals(ModelDiff.Risk.LOW, config.risk());
        assertEquals(List.of("METADATA_CHANGED"), config.reasons());
    }

    private static String model(String id) {
        return DocumentModelDraft.named(id).locale("en_US")
                .rootGroup("Order", order -> order.stringField("Number", field -> { }))
                .build().toJson();
    }

    private static String modelWithCharacters(String... characters) {
        return DocumentModelDraft.named("orders").locale("en_US")
                .supportedCharacters(characters)
                .rootGroup("Order", order -> order.stringField("Number", field -> { }))
                .build().toJson();
    }

    private static ElementChange configChange(ModelDiff diff) {
        return diff.changes().stream().filter(candidate -> candidate.element() == ElementKind.CONFIG)
                .findFirst().orElseThrow(() -> new AssertionError("missing config change: " + diff.changes()));
    }

    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + aspect + " delta: " + change.deltas()));
    }

    private static void assertDelta(ElementChange change, String aspect, String before, String after) {
        assertEquals(before, delta(change, aspect).before(), aspect + " before");
        assertEquals(after, delta(change, aspect).after(), aspect + " after");
    }
}
