package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

/**
 * The two edit gates must be <b>witnesses</b>, on the same terms as {@code BoundModel} and {@code KernelAdapter}:
 * holding one may attest that a gate ran, and it must stop attesting the moment that stops being true.
 *
 * <p>The wrinkle that makes this its own test is that {@link ModelDocument} and {@link EditSession} are
 * <b>mutable</b>, unlike every witness before them. An immutable binding cannot outlive the state it gated;
 * a document can — edit, gate, edit again, and a naively stored receipt now vouches for a tree that no longer
 * exists. That is the ambient-mode defect one layer along: a receipt that is real but is not about <em>this</em>
 * model. So the receipt is <b>state-checked</b> — it is kept against the exact DM-JSON the gate ran on, and any
 * edit that changes the text drops it, with nothing for a caller to remember.
 *
 * <p>The invalidation case is the discriminating one, and it is why this test exists rather than a
 * gate-ran-after-validate assertion, which any stored flag would pass.
 */
class EditGateReceiptTest {

    private static String orderModel() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", g -> g.field("Quantity", FieldKind.NUMBER))
                .build().toJson();
    }

    // --- ModelDocument ------------------------------------------------------------------------------

    @Test
    void anUngatedDocumentAttestsNothing() {
        ModelDocument doc = ModelDocument.read(orderModel());

        assertEquals(Optional.empty(), doc.consistencyGate(),
                "reading DM-JSON runs no gate — only validate() does");
        assertFalse(ConsistencyEvidence.of(doc).gateRan());
    }

    @Test
    void aGatedDocumentAttestsTheGateThatRan() {
        ModelDocument doc = ModelDocument.read(orderModel()).validate();

        assertEquals(Optional.of(Verification.KERNEL_CONFIRMED), doc.consistencyGate());
        assertTrue(ConsistencyEvidence.of(doc).gateRan());
    }

    /**
     * The case the whole design turns on: an edit <em>after</em> the gate must revoke the receipt. A stored
     * {@code Verification} would still read {@code KERNEL_CONFIRMED} here, and the envelope would publish that the
     * kernel confirmed a model containing a field the kernel has never seen.
     */
    @Test
    void anEditAfterTheGateRevokesTheReceipt() {
        ModelDocument doc = ModelDocument.read(orderModel()).validate();

        doc.addField("/Order", "PromoCode", FieldKind.STRING);

        assertEquals(Optional.empty(), doc.consistencyGate(),
                "the gate ran over a tree this document no longer holds");
        assertFalse(ConsistencyEvidence.of(doc).gateRan());
    }

    @Test
    void andReValidatingRestoresIt() {
        ModelDocument doc = ModelDocument.read(orderModel()).validate();
        doc.addField("/Order", "PromoCode", FieldKind.STRING);

        doc.validate();

        assertEquals(Optional.of(Verification.KERNEL_CONFIRMED), doc.consistencyGate());
    }

    // --- EditSession --------------------------------------------------------------------------------

    @Test
    void anUngatedSessionAttestsNothing() {
        EditSession session = EditSession.read(orderModel());

        assertEquals(Optional.empty(), session.consistencyGate());
        assertFalse(ConsistencyEvidence.of(session).gateRan());
    }

    @Test
    void aGatedSessionAttestsTheGateThatRan() {
        EditSession session = EditSession.read(orderModel()).validate();

        assertEquals(Optional.of(Verification.KERNEL_CONFIRMED), session.consistencyGate());
        assertTrue(ConsistencyEvidence.of(session).gateRan());
    }

    @Test
    void anEditAfterTheSessionGateRevokesTheReceipt() {
        EditSession session = EditSession.read(orderModel()).validate();

        session.addField("/Order", "PromoCode", FieldKind.STRING);

        assertEquals(Optional.empty(), session.consistencyGate(),
                "apply's terminal gate is the only thing that may attest for a session's final state");
        assertFalse(ConsistencyEvidence.of(session).gateRan());
    }
}
