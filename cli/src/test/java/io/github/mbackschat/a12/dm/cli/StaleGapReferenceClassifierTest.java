package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Focused canaries for IF141's reference-local, logical-comment classifier. */
class StaleGapReferenceClassifierTest {

    @Test
    void positiveCanaryDetectsADeletedOpenReference() {
        assertEquals(
                List.of(20),
                StaleGapReferenceClassifier.deletedOpenReferences("// TODO: IG20", Set.of()));
    }

    @Test
    void mixedReferencesOnOneLineAreClassifiedIndependently() {
        assertEquals(
                List.of(80),
                StaleGapReferenceClassifier.deletedOpenReferences(
                        "// IG20 graduated and IG80 pending; IG94 pending; IG21 provenance",
                        Set.of(94)));
    }

    @Test
    void wrappedCommentsFormOneLogicalComment() {
        assertEquals(
                List.of(80),
                StaleGapReferenceClassifier.deletedOpenReferences(
                        """
                        /**
                         * Future work
                         * awaits IG80.
                         */
                        """,
                        Set.of()));
    }

    @Test
    void openPhrasesAreCaseInsensitiveAndIncludeTheObservedDoesNotYetForm() {
        assertEquals(
                List.of(67, 80),
                StaleGapReferenceClassifier.deletedOpenReferences(
                        """
                        // Does NOT (yet) support mounted references, tracked by IG67.
                        // AWAITS IG80.
                        """,
                        Set.of()));
    }

    @Test
    void liveAndExplicitlyHistoricalReferencesAreAccepted() {
        assertEquals(
                List.of(),
                StaleGapReferenceClassifier.deletedOpenReferences(
                        """
                        // TODO IG94.
                        // IG20 -> IF42; graduated from IG21; IG63 was retired; no longer pending IG64.
                        // It once excluded codes pending IG20.
                        """,
                        Set.of(94)));
    }
}
