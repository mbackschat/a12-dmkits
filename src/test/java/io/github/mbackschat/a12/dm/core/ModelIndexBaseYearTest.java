package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * {@link ModelIndex#baseYear()} — the model-level declaration, beside {@link ModelIndex#locales()}.
 *
 * <p>It is read rather than inferred because it changes a kernel GATE, not just a rendered value: the
 * entity-list aggregates compare their operands' declared component sets after adding YEAR to <b>all</b> of
 * them when the model declares a base year (KERNEL-FINDINGS KF193). Supplementation only ever ADMITS more, so
 * a client-side check that could not see the declaration would refuse pairs the kernel accepts — the
 * over-rejection the ledger cited when it left this gate delegated.
 */
class ModelIndexBaseYearTest {

    private static ModelIndex index(Integer baseYear) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("base-year").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return ModelIndex.of(
                b.rootGroup("Doc", d -> d.dateField("When", f -> f.format("yyyy-MM-dd"))).build().toJson(),
                ReferenceResolvers.selfContained());
    }

    @Test
    void aDeclaredBaseYearIsRead() {
        assertEquals(Optional.of(2000), index(2000).baseYear());
    }

    @Test
    void aModelDeclaringNoneAnswersEmpty() {
        assertTrue(index(null).baseYear().isEmpty());
    }

    /** The value itself matters to arithmetic; the gate only asks whether one is declared at all. */
    @Test
    void anyDeclaredYearCountsAsDeclared() {
        assertEquals(Optional.of(1900), index(1900).baseYear());
        assertEquals(Optional.of(2100), index(2100).baseYear());
    }
}
