package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * The attachment template serves the locales A12 reads as {@code en_US} and {@code de_DE} however they are spelled
 * (KERNEL-FINDINGS KF290), and the builder writes each in A12's spelling (KF676). So a model declaring {@code en-US}
 * and {@code de-DE} must build byte-identically to one declaring {@code en_US} and {@code de_DE}, German messages
 * included; the kernel load confirms A12 takes it. Own domain (an order's invoice).
 */
class AttachmentLocaleSpellingTest {

    @Test
    void anAttachmentBuildsTheSameUnderEitherSpellingOfItsLocales() {
        String canonical = model("en_US", "de_DE");
        String hyphenated = assertDoesNotThrow(() -> model("en-US", "de-DE"));
        assertEquals(canonical, hyphenated);
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(hyphenated));
    }

    private static String model(String english, String german) {
        return DocumentModelDraft.named("order").locale(english).locale(german)
                .attachmentGroup("Invoice", a -> { })
                .build().toJson();
    }
}
