package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * KERNEL-FINDINGS §11 supplement — a computation written with a {@code commonPrecondition} + ONE unconditional
 * alternative reads back FOLDED: the persisted DM-JSON carries the {@code commonPrecondition} key verbatim, but
 * the kernel stores the computation as one rule condition in its normalized form and re-derives the
 * common/per-alternative split on extraction, so the gate comes back as the single alternative's own
 * precondition ({@code commonPrecondition = null}) — semantically identical, and distinct only with ≥2
 * alternatives. This is the read-back representation fact {@code computation read} and {@code model report}
 * both surface (MODEL-REVIEW-SPEC §4a).
 */
class ComputationCommonPreconditionFoldTest {

    @Test
    void singleAlternativeCommonPreconditionReadsBackAsTheAlternativesPrecondition() {
        String base = DocumentModelDraft.named("fold-probe").locale("en_US")
                .rootGroup("Order", g -> g
                        .field("Subtotal", FieldKind.NUMBER)
                        .field("ShippingFee", FieldKind.NUMBER)
                        .field("BaseShipping", FieldKind.NUMBER))
                .build().toJson();
        EditSession s = EditSession.read(base);
        s.addComputation(m -> m.computation(m.num("/Order/ShippingFee"))
                .name("ShippingFeeComp")
                .commonPrecondition(m.num("/Order/Subtotal").gt(0))
                .calc(m.num("/Order/BaseShipping").value())
                .message(en("Shipping applies once there is a subtotal.")).build());
        String persisted = s.toJson();
        assertEquals(true, persisted.contains("commonPrecondition"),
                "the WRITE is verbatim — the DM-JSON carries the commonPrecondition key; the fold is read-side");

        KernelAdapter adapter = KernelAdapter.ofJson(persisted);
        String name = adapter.computationFullNames().iterator().next();
        DraftComputation draft = ((ReadComputation.Converted) adapter.readComputation(name)).computation().draft();

        assertNull(draft.commonPrecondition(),
                "the kernel's normalized-form extraction folds a single-alternative common gate — it must read back null");
        assertEquals(1, draft.alternatives().size(), "still exactly one alternative");
        DraftComputation.Alternative alt = draft.alternatives().get(0);
        assertEquals("[Subtotal] > 0", alt.precondition(),
                "the former common gate reads back as the single alternative's OWN precondition");
        assertEquals("[BaseShipping]", alt.operation(), "the operation is untouched by the fold");
    }
}
