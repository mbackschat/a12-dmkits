package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * A review artefact must carry the receipt of the gate that <em>actually</em> ran, on the same basis as
 * {@link BoundModel#consistencyGate()} (CLI-SPEC §3b "The result envelope").
 *
 * <p>Both artefacts were used by the CLI as witnesses-by-type — "holding a report proves the gate ran, so only
 * the mode has to be read from the environment". Two things were wrong with that. The <b>mode</b> came from
 * {@code Backends.verification()}, which describes the environment and not a gate, so a report assembled over
 * the deliberately unchecked {@code KernelAdapter.of} would still have published {@code KERNEL_CONFIRMED}. And
 * the <b>witness</b> did not hold either, because both types were {@code public record}s: a record's canonical
 * constructor cannot be narrower than the record, so {@code new ModelReport(…)} minted a receipt with no kernel
 * anywhere near it — the same public-canonical-constructor hole that was closed one layer up in the envelope.
 *
 * <p>So the receipt is now <b>stored from the adapter that assembled the artefact</b>, and the types are closed.
 * The pairing matters: storing a receipt in a forgeable type only moves the hole, and closing a type that still
 * reads the ambient mode fixes nothing.
 */
class ReviewGateReceiptTest {

    private static String model() {
        return DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", o -> o.field("Total", FieldKind.NUMBER)).build().toJson();
    }

    @Test
    void aReportAssembledThroughTheGateCarriesItsReceipt() {
        assertEquals(Optional.of(Backends.verification()), ModelReport.of(model()).consistencyGate(),
                "ModelReport.of runs checkAndOpen, so the gate that ran is the one reported");
    }

    /**
     * The discriminating case, and the reason the receipt is stored rather than read from the environment: the
     * <em>same</em> assembly over the unchecked adapter must come out with no receipt at all. Under the old
     * ambient read this was indistinguishable from the gated case.
     */
    @Test
    void butOneAssembledOverTheUncheckedAdapterCarriesNone() {
        String json = model();
        BoundModel bound = BoundModel.ofJsonLenient(json, ReferenceResolvers.selfContained());

        ModelReport ungated = ModelReport.assemble(json, bound, KernelAdapter.of(bound));

        assertEquals(Optional.empty(), ungated.consistencyGate(),
                "KernelAdapter.of is the unchecked factory — no gate ran, so there is no receipt to publish");
    }

    @Test
    void aDiffLoadsBothSidesThroughTheGateAndCarriesTheReceipt() {
        String head = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", o -> o.field("Total", FieldKind.NUMBER).field("Note", FieldKind.STRING))
                .build().toJson();

        ModelDiff diff = ModelDiff.between(model(), head);

        assertEquals(Optional.of(Backends.verification()), diff.consistencyGate(),
                "both sides load through checkAndOpen, so a diff cannot exist without two gates having run");
        assertFalse(diff.changes().isEmpty(), "sanity: the two states really do differ");
    }

    /**
     * The unforgeability half. A receipt that can be constructed by hand is not evidence, so neither artefact may
     * expose a public constructor — the property the CLI's evidence factories rely on, asserted here rather than
     * assumed, because it silently held true for {@code BoundModel} and silently did not for these two.
     */
    @Test
    void neitherArtefactCanBeConstructedByHand() {
        assertEquals(0, ModelReport.class.getConstructors().length,
                () -> "a hand-built ModelReport would mint a gate receipt with no gate: "
                        + Arrays.toString(ModelReport.class.getConstructors()));
        assertEquals(0, ModelDiff.class.getConstructors().length,
                () -> "same for ModelDiff: " + Arrays.toString(ModelDiff.class.getConstructors()));
        assertTrue(ModelReport.class.getDeclaredConstructors().length > 0
                        && ModelDiff.class.getDeclaredConstructors().length > 0,
                "sanity: the constructors exist, they are just not reachable from outside");
    }
}
