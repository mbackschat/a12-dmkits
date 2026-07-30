package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * CRUD of element descriptions + annotations through {@link EditSession} / the editors, across <b>field,
 * group, rule, and computation</b> — proving the shared element-path edit reaches both executable element
 * kinds too. Verified by the kernel's own deserialize→serialize round-trip: a value that
 * survives the kernel reading + re-writing the model is one the kernel actually read (the deserializer
 * ignores unknown keys), so this is read-back, not mere emit.
 */
class ElementMetadataEditTest {

    private static final IDocumentModelSerializer SER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    /** Serialize the session's model through the kernel (deserialize → serialize) — survives ⇒ kernel-read. */
    private static String kernelRoundTrip(String json) throws Exception {
        IDocumentModel model = SER.deserialize(new StringReader(json));
        StringWriter out = new StringWriter();
        SER.serialize(model, out, n -> { });
        return out.toString();
    }

    private static EditSession ordersWithRule() {
        String base = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("Order", g -> g
                        .numberField("Qty", n -> n.fractionDigits(0))
                        .numberField("ReviewedQty", n -> n.fractionDigits(0)))
                .build().toJson();
        EditSession s = EditSession.read(base);
        GroupEditor order = s.group("/Order");
        NumberRef qty = order.num("Qty");
        order.addRule(r -> r.name("QtyNonNeg").errorField(qty)
                .when(qty.filled().and(qty.lt(0)))
                .code("QTY_NEG").severity(Severity.ERROR)
                .message(Dsl.en("Qty must not be negative.")).build());
        order.addComputation(order.num("ReviewedQty"), c -> c.name("CopyQty")
                .calc(qty.value())
                .message(Dsl.en("Reviewed quantity follows quantity.")).build());
        return s;
    }

    @Test
    void createsDescriptionsAndAnnotationsOnEveryModelTreeElementKind() throws Exception {
        EditSession s = ordersWithRule();
        s.field("/Order/Qty").setInternalDescription("en_US", "the ordered quantity");
        s.group("/Order").setExternalDescription("en_US", "a customer order").setAnnotation("owner", "orders-team");
        s.rule("/Order/QtyNonNeg").setInternalDescription("en_US", "guards a negative quantity")
                .setAnnotation("ticket", "JIRA-1");
        s.computation("/Order/CopyQty").setExternalDescription("en_US", "copies the quantity for review")
                .setAnnotation("owner", "review-team");
        s.validate();   // kernel-valid with all the metadata

        String round = kernelRoundTrip(s.toJson());
        assertTrue(round.contains("the ordered quantity"), () -> "field description survives: " + round);
        assertTrue(round.contains("a customer order"), "group description survives");
        assertTrue(round.contains("orders-team"), "group annotation survives");
        assertTrue(round.contains("guards a negative quantity"), "RULE description survives — the edit reaches rules");
        assertTrue(round.contains("JIRA-1"), "RULE annotation survives");
        assertTrue(round.contains("copies the quantity for review"),
                "COMPUTATION description survives — the edit reaches computations");
        assertTrue(round.contains("review-team"), "COMPUTATION annotation survives");
    }

    @Test
    void removesExecutableElementDescriptionsAndAnnotationsThroughTheirHeldEditors() throws Exception {
        EditSession s = ordersWithRule();
        s.rule("/Order/QtyNonNeg")
                .setInternalDescription("en_US", "temp rule note")
                .setExternalDescription("en_US", "temp rule hint")
                .setAnnotation("ticket", "JIRA-1");
        s.computation("/Order/CopyQty")
                .setInternalDescription("en_US", "temp computation note")
                .setExternalDescription("en_US", "temp computation hint")
                .setAnnotation("owner", "temporary-team");
        s.validate();
        String before = kernelRoundTrip(s.toJson());
        assertTrue(before.contains("temp rule note") && before.contains("temp computation note"),
                "precondition: both executable-element metadata sets reached the kernel");

        s.rule("/Order/QtyNonNeg")
                .removeInternalDescription("en_US")
                .removeExternalDescription("en_US")
                .removeAnnotation("ticket");
        s.computation("/Order/CopyQty")
                .removeInternalDescription("en_US")
                .removeExternalDescription("en_US")
                .removeAnnotation("owner");
        s.validate();
        String round = kernelRoundTrip(s.toJson());
        for (String removed : new String[] {
                "temp rule note", "temp rule hint", "JIRA-1",
                "temp computation note", "temp computation hint", "temporary-team"
        }) {
            assertFalse(round.contains(removed), () -> "the metadata value was removed: " + removed);
        }
    }
}
