package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * Config: <b>supported characters</b> (FEATURE-GAPS) — the {@code modelConfig}
 * allowed-character set the kernel validates field values against. Each entry is a single character or a
 * range like {@code " -~"} (space through tilde); an empty list means all Unicode-Plane-0 characters are
 * allowed ({@code IDocumentModelConfig}).
 *
 * <p>Verified by a <b>kernel read-back</b> through the typed config API
 * ({@code IDocumentModelConfig.getSupportedCharacters()}), not just emit + load: the DM-JSON deserializer
 * ignores unknown keys, so only reading the value back proves the engine honored what we emitted.
 */
class SupportedCharactersTest {

    private static List<String> supportedCharactersAfterRoundTrip(String json) {
        return KernelModelOracle.ofSelfContained(json).model()
                .getContent().getDocumentModelConfig().getSupportedCharacters();
    }

    @Test
    void authoredSupportedCharactersRoundTripThroughTheKernel() throws Exception {
        String json = DocumentModelDraft.named("permit").locale("en_US")
                .supportedCharacters("A-Z", "a-z", "0-9", " ", "-")
                .rootGroup("Permit", p -> p.field("Ref", FieldKind.STRING))
                .build().toJson();

        assertEquals(List.of("A-Z", "a-z", "0-9", " ", "-"), supportedCharactersAfterRoundTrip(json),
                "the kernel reads back the authored supported-character set, in order");
    }

    @Test
    void editingAModelSetsTheSupportedCharacterSet() throws Exception {
        String base = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p.field("Ref", FieldKind.STRING))
                .build().toJson();
        // a model authored without the slot defaults to "all characters allowed" (empty list)
        assertTrue(supportedCharactersAfterRoundTrip(base).isEmpty(), "no slot → empty (all allowed)");

        EditSession session = EditSession.read(base);
        session.config().supportedCharacters("A-Z", "0-9");
        session.validate();   // kernel-valid after the config edit
        String edited = session.toJson();

        assertEquals(List.of("A-Z", "0-9"), supportedCharactersAfterRoundTrip(edited),
                "the in-session config edit sets the supported-character set the kernel reads back");
    }
}
