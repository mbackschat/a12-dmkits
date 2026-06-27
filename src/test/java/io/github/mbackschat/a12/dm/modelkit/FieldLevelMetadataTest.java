package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IField;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * Field-level metadata (FEATURE-GAPS): the kernel field flags {@code global}/{@code transient} and the
 * three localized text slots distinct from the label — internal/external description and helper text.
 * Verified by kernel read-back via {@code ModelIndex.field(path) → IField} (the DM-JSON deserializer ignores
 * unknown keys, so a clean load proves nothing — KERNEL-FINDINGS).
 */
class FieldLevelMetadataTest {

    @Test
    void flagsAndDescriptionsRoundTripThroughTheKernel() throws Exception {
        DocumentModelDraft draft = DocumentModelDraft.named("permit").locale("en_US").locale("de_DE")
                .rootGroup("Permit", p -> p
                        .stringField("Ref", s -> s.global().notPersisted()
                                .internalDescription("en_US", "maintenance note")
                                .externalDescription("en_US", "shown to the applicant")
                                .helperText("en_US", "type the permit reference")))
                .build();
        IField field = ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(draft.toJson())))
                .field("/Permit/Ref");

        assertTrue(field.isGlobal(), "global flag honored");
        assertTrue(field.isTransient(), "transient flag honored");
        assertFalse(field.getHelperText().isEmpty(), "helperText honored");
        assertFalse(field.getInternalDescription().isEmpty(), "internalDescription honored");
        assertFalse(field.getExternalDescription().isEmpty(), "externalDescription honored");
    }
}
