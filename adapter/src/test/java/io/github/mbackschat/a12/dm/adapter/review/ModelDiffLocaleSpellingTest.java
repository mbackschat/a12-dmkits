package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * A locale respelled the way A12 reads as the same locale is no configuration change: {@code en-US} and
 * {@code en_US} name one locale (KERNEL-FINDINGS KF290), so a review diff that reported one as added and the other as
 * removed would flag a model A12 sees as unchanged. The control keeps a real addition visible. Own domain (orders).
 */
class ModelDiffLocaleSpellingTest {

    private static String model(String... locales) {
        var draft = DocumentModelDraft.named("orders");
        for (String locale : locales) {
            draft.locale(locale);
        }
        return draft.rootGroup("Order", g -> g.field("Reference", FieldKind.STRING)).build().toJson();
    }

    /** The header's {@code en_US} respelled; the condition language, written the same way after it, is left alone. */
    private static String hyphenated(String json) {
        return json.replaceFirst("\"code\" : \"en_US\"", "\"code\" : \"en-US\"");
    }

    @Test
    void aRespelledLocaleIsNoChange() {
        String base = model("en_US");
        String respelled = hyphenated(base);
        assertTrue(respelled.contains("en-US"), "the fixture is respelled");
        assertTrue(ModelDiff.between(base, respelled).changes().isEmpty(), () -> ModelDiff.between(base, respelled).changes().toString());
    }

    @Test
    void aRespelledLocaleBesideARealAdditionReportsOnlyTheAddition() {
        String base = hyphenated(model("en_US"));
        var change = ModelDiff.between(base, model("en_US", "de_DE")).changes().stream()
                .filter(c -> c.element() == ModelDiff.ElementKind.CONFIG).findFirst().orElseThrow();
        assertEquals(List.of("LOCALE_ADDED"), change.reasons());
    }
}
