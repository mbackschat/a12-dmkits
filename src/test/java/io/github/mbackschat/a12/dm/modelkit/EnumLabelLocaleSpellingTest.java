package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * The builder's all-or-nothing check on enumeration labels must meet a declared locale however it is spelled: A12
 * reads {@code en-US} and {@code en_US} as one locale (KERNEL-FINDINGS KF290), and the builder writes both as
 * {@code en_US} (KF676), so a value labelled {@code en_US} answers a declared {@code en-US}. The kernel load is the
 * oracle for the admitted row; the control keeps the refusal A12 does make, a declared locale left unanswered
 * ({@code MVK_COUNT_OF_VALUES_INVALID}, KF321). Own domain (insurance cover).
 */
class EnumLabelLocaleSpellingTest {

    @Test
    void aLabelAnswersADeclaredLocaleSpelledTheOtherWay() {
        String json = assertDoesNotThrow(() -> draft(l -> l.label("en_US", "Basic").label("de-DE", "Basis")));
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(json));
    }

    @Test
    void aDeclaredLocaleLeftUnansweredIsStillRefused() {
        assertThrows(IllegalArgumentException.class, () -> draft(l -> l.label("de_DE", "Basis")));
    }

    private static String draft(java.util.function.Consumer<LabelBuilder> basicLabels) {
        return DocumentModelDraft.named("cover").locale("en-US").locale("de_DE")
                .rootGroup("Policy", p -> p.enumField("Cover", e -> e
                        .value("BASIC", basicLabels)
                        .value("FULL", l -> l.label("en_US", "Full").label("de_DE", "Voll"))))
                .build().toJson();
    }
}
