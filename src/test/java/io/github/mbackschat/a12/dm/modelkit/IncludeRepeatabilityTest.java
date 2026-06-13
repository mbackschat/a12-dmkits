package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * Include <b>repeatability</b> (FEATURE-GAPS §2 Type-defs/includes): a mount can carry a repeatability > 1,
 * mounting the included model as a <em>repeatable</em> sub-form (the mount was previously hardcoded to 1).
 * Kernel is the oracle: the base model with a repeatable mount must expand + validate, and the mount group
 * must read back as repeating. Own domain (an order mounting an address sub-form three times).
 */
class IncludeRepeatabilityTest {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    /** The reusable included sub-form. */
    private static String addressModel() {
        return DocumentModelDraft.named("address").locale("en_US")
                .rootGroup("Address", a -> a.field("Street", FieldKind.STRING).field("City", FieldKind.STRING))
                .build().toJson();
    }

    /** A base order that mounts the address as a repeatable (3-row) sub-form. */
    private static String orderWithRepeatableMount() {
        return DocumentModelDraft.named("order").locale("en_US")
                .include("address", "address")
                .rootGroup("Order", o -> o
                        .field("OrderRef", FieldKind.STRING)
                        .mount("ShipTo", "address", 3))
                .build().toJson();
    }

    private static IDocumentModelReferenceResolver resolver() {
        return reference -> {
            if ("address".equals(reference)) {
                try {
                    return FACTORY.createDocumentModelSerializer().deserialize(new StringReader(addressModel()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalStateException("unknown reference: " + reference);
        };
    }

    @Test
    void aRepeatableMountExpandsToARepeatingGroup() throws Exception {
        IDocumentModel model = ModelLoader.load(new StringReader(orderWithRepeatableMount()), resolver());
        ModelIndex index = ModelIndex.of(model);
        assertTrue(index.isRepeating("/Order/ShipTo"),
                "a mount with repeatability > 1 must expand to a repeating group");
    }

    @Test
    void mountingARepeatableIncludeInSessionIsKernelValidAndRepeats() {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.field("OrderRef", FieldKind.STRING))
                .build().toJson();
        io.github.mbackschat.a12.dm.edit.EditSession session =
                io.github.mbackschat.a12.dm.edit.EditSession.read(base, resolver());
        session.addInclude("address", "address", "/Order", "BillTo");   // declare + single mount
        session.include("address").mount("/Order", "ShipTo", 3);        // repeatable mount of the same include
        session.validate();

        ModelDocument result = ModelDocument.read(session.toJson());
        assertTrue(result.isRepeating("/Order/ShipTo"), "the in-session repeatable mount repeats");
        assertTrue(!result.isRepeating("/Order/BillTo"), "the single mount stays non-repeating");
    }
}
