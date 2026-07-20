package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The typed authoring boundary preserves atomic graphemes and rejects every invalid definition class precisely. */
class SupportedCharactersAuthoringContractTest {

    private static final String ACUTE = "\u0301";
    private static final String GRAVE = "\u0300";
    private static final String CEDILLA = "\u0327";
    private static final String E_ACUTE = "e" + ACUTE;

    @Test
    void draftAndEditPathsPreserveACombinedGraphemeAsOneEntry() {
        String json = DocumentModelDraft.named("permit").locale("en_US")
                .supportedCharacters("A-Z", E_ACUTE)
                .rootGroup("Permit", p -> p.field("Name", FieldKind.STRING))
                .build().toJson();

        ModelDocument authored = ModelDocument.read(json);
        assertEquals(List.of("A-Z", E_ACUTE), authored.config().supportedCharacters());

        authored.setSupportedCharacters(List.of(E_ACUTE));
        assertEquals(List.of(E_ACUTE), ModelDocument.read(authored.toJson()).config().supportedCharacters());
    }

    @Test
    void invalidDefinitionsFailBeforeMutatingTheModel() {
        ModelDocument doc = ModelDocument.read(DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p.field("Name", FieldKind.STRING))
                .build().toJson());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> doc.setSupportedCharacters(List.of("ab")));

        assertTrue(failure.getMessage().contains("index 0"));
        assertTrue(failure.getMessage().contains("combined grapheme"));
        assertTrue(doc.config().supportedCharacters().isEmpty(), "a refused definition must not mutate the model");
    }

    @Test
    void reportsEveryKernelDefinitionBoundaryWithTheOffendingIndex() {
        assertProblem(List.of(""), 0, "empty");
        assertProblem(List.of("\uD83D\uDE00"), 0, "surrogate");
        assertProblem(List.of("Z-A"), 0, "after");
        assertProblem(List.of("ab"), 0, "combined grapheme");
        assertProblem(List.of("e" + ACUTE + GRAVE + CEDILLA), 0, "maximum");
        assertProblem(List.of("A", "q" + ACUTE + "r", "q" + ACUTE, "r" + GRAVE), 1, "overlap");
    }

    private static void assertProblem(List<String> entries, int index, String detail) {
        SupportedCharactersContract.Problem problem =
                SupportedCharactersContract.firstProblem(entries).orElseThrow();
        assertEquals(index, problem.index());
        assertTrue(problem.message().contains(detail), problem::message);
    }
}
