package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * {@link ModelIndex#temporalFormat} over every kind its contract names. The date-bearing four store the
 * declaration under {@code format}; a date FRAGMENT stores the same thing under {@code formatOfFragment},
 * which is why reading one key answers empty for a field that plainly declares a format.
 *
 * <p>It matters beyond tidiness: the extraction gates key on this string, and a fragment participates in
 * them — a {@code yyyy} fragment answers {@code YearFromDate} and is refused {@code MonthFromDate}
 * (KERNEL-FINDINGS KF426) — so an empty answer here silently disables the gate rather than failing it.
 */
class ModelIndexTemporalFormatTest {

    private static final ModelIndex INDEX = ModelIndex.of(
            DocumentModelDraft.named("temporal-declarations").locale("en_US")
                    .rootGroup("Doc", d -> d
                            .dateField("Date", f -> f.format("dd.MM.yyyy"))
                            .timeField("Time", f -> f.format("HH:mm:ss"))
                            .dateTimeField("Stamp", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                            .dateFragmentField("Year", f -> f.fragmentFormat("yyyy"))
                            .dateRangeField("Window", f -> f.format("dd.MM.yyyy").separator("-"))
                            .dateField("Undeclared", f -> { })
                            .field("Text", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING))
                    .build().toJson(),
            ReferenceResolvers.selfContained());

    @Test
    void aDeclaredFormatIsReadForEveryTemporalKindTheContractNames() {
        assertEquals(Optional.of("dd.MM.yyyy"), INDEX.temporalFormat("/Doc/Date"));
        assertEquals(Optional.of("HH:mm:ss"), INDEX.temporalFormat("/Doc/Time"));
        assertEquals(Optional.of("yyyy-MM-dd'T'HH:mm:ss"), INDEX.temporalFormat("/Doc/Stamp"));
        assertEquals(Optional.of("dd.MM.yyyy"), INDEX.temporalFormat("/Doc/Window"));
    }

    /** The fragment's declaration lives under its own key, and is a declared temporal format all the same. */
    @Test
    void aDateFragmentsDeclarationIsATemporalFormat() {
        assertEquals(Optional.of("yyyy"), INDEX.temporalFormat("/Doc/Year"));
    }

    /**
     * A non-temporal field has nothing to answer. A temporal one authored through modelkit always does:
     * the builder MATERIALIZES the kind default into the JSON, so "declared no format" is not a state a
     * modelkit-authored model can reach — which is worth pinning, because a fixture built that way looks
     * like it exercises the absent-declaration path and does not.
     */
    @Test
    void aNonTemporalFieldAnswersEmptyAndModelkitMaterializesTheKindDefault() {
        assertEquals(Optional.empty(), INDEX.temporalFormat("/Doc/Text"));
        assertEquals(Optional.of("yyyy-MM-dd"), INDEX.temporalFormat("/Doc/Undeclared"));
    }

    /** A model that genuinely omits the key — the shape only a non-modelkit author produces. */
    @Test
    void aTrulyAbsentDeclarationAnswersEmpty() {
        String stripped = DocumentModelDraft.named("absent").locale("en_US")
                .rootGroup("Doc", d -> d.dateField("Date", f -> f.format("dd.MM.yyyy")))
                .build().toJson()
                .replaceAll("\\s*\"format\"\\s*:\\s*\"dd.MM.yyyy\"\\s*", "");
        assertTrue(!stripped.contains("dd.MM.yyyy"), "the corruption under test did not apply");
        assertEquals(Optional.empty(),
                ModelIndex.of(stripped, ReferenceResolvers.selfContained()).temporalFormat("/Doc/Date"));
    }
}
