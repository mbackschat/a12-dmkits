package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * Runtime behavioral law for the {@code modelConfig} <b>supported-character set</b> (KERNEL-FINDINGS).
 * The set is not merely authored metadata — the interpreted {@code IDocumentRtService.validateFull}
 * <em>enforces</em> it: a string field value containing a character outside the set raises the formal
 * error {@code ZeichenNichtImZeichensatz} ("character not in the character set"); a value drawn wholly
 * from the set is clean. This is the fires/does-not-fire counterpart of the structural round-trip in
 * {@code modelkit.SupportedCharactersTest}.
 */
class SupportedCharactersLawsTest {

    /** The kernel's formal error for a value carrying a character outside {@code supportedCharacters}. */
    private static final String OUT_OF_SET = "ZeichenNichtImZeichensatz";

    /** A model whose config restricts every value to the upper-case Latin letters {@code A-Z}. */
    private static String upperCaseOnly() {
        return DocumentModelDraft.named("permit").locale("en_US")
                .supportedCharacters("A-Z")
                .rootGroup("Permit", p -> p.field("Ref", FieldKind.STRING))
                .build().toJson();
    }

    @Test
    void aValueWithACharacterOutsideTheSetIsRejected() {
        assertTrue(RuntimeLaws.fires(upperCaseOnly(), OUT_OF_SET, Set.of("/Permit"), Map.of("/Permit/Ref", "abc")),
                "a lower-case value is outside the A-Z set → the kernel raises the out-of-charset error");
    }

    @Test
    void aValueDrawnFromTheSetIsAccepted() {
        assertFalse(RuntimeLaws.fires(upperCaseOnly(), OUT_OF_SET, Set.of("/Permit"), Map.of("/Permit/Ref", "ABC")),
                "an all-upper-case value is within the set → no out-of-charset error");
    }
}
