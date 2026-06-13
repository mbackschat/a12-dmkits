package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.edit.EditSession;

/**
 * Model-level header metadata (FEATURE-GAPS §2 Config): a per-locale model **label** and a model-level
 * **annotation**, emitted into the previously-empty header arrays. Verified by a deserialize → serialize
 * round-trip through the kernel's <em>own</em> serializer: the header values our emitter writes must survive
 * the kernel reading the model and writing it back. The DM-JSON deserializer ignores unknown keys
 * (KERNEL-FINDINGS), so a value that survives the kernel's round-trip is one the kernel actually
 * <em>read</em> — not merely tolerated. The model is also kernel-valid.
 */
class ModelHeaderTest {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    @Test
    void modelLabelAndAnnotationSurviveAKernelRoundTrip() throws Exception {
        String json = DocumentModelDraft.named("permit").locale("en_US").locale("de_DE")
                .label("en_US", "Building Permit").label("de_DE", "Baugenehmigung")
                .annotation("owner", "permits-team")
                .rootGroup("Permit", p -> p.field("Ref", FieldKind.STRING))
                .build().toJson();

        ModelLoader.loadSelfContained(new StringReader(json));   // kernel-valid

        // the kernel reads the header label/annotation and writes them back — they survive its round-trip
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModel model = serializer.deserialize(new StringReader(json));
        StringWriter out = new StringWriter();
        serializer.serialize(model, out, n -> { });
        String roundTripped = out.toString();

        assertTrue(roundTripped.contains("Building Permit"), () -> "the en_US model label survives: " + roundTripped);
        assertTrue(roundTripped.contains("Baugenehmigung"), () -> "the de_DE model label survives: " + roundTripped);
        assertTrue(roundTripped.contains("permits-team"), () -> "the model annotation survives: " + roundTripped);
    }

    @Test
    void editingAnExistingModelsHeaderRoundTripsThroughTheKernel() throws Exception {
        // the edit path: set the header label/annotation on a loaded model via the config editor
        String base = DocumentModelDraft.named("permit").locale("en_US")
                .rootGroup("Permit", p -> p.field("Ref", FieldKind.STRING))
                .build().toJson();

        EditSession session = EditSession.read(base);
        session.config().label("en_US", "Building Permit").annotation("owner", "permits-team");
        session.validate();   // kernel-valid after the in-session header edit
        String edited = session.toJson();

        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModel model = serializer.deserialize(new StringReader(edited));
        StringWriter out = new StringWriter();
        serializer.serialize(model, out, n -> { });
        assertTrue(out.toString().contains("Building Permit"), () -> "the edited label survives: " + out);
        assertTrue(out.toString().contains("permits-team"), () -> "the edited annotation survives: " + out);
    }

    /**
     * The document-model <b>super/subtype</b> hierarchy (model inheritance) is an A12 modeling <em>convention</em>
     * over the generic header-annotation mechanism — <b>not</b> a kernel structural feature and not a
     * {@code modelReferences} purpose. Verified from the real 2025.06-ext5 e-commerce models: {@code Product_DM}
     * carries {@code abstract=true} + {@code subTypes="…"}, {@code ProductMovie_DM} carries {@code superTypes="…"}
     * (comma-separated model ids); shared <em>structure</em> is factored via {@code include}. So our model-level
     * annotation surface authors it directly — no dedicated feature needed. KERNEL-INTEGRATION-NOTES.
     */
    @Test
    void theModelSuperSubtypeConventionRoundTrips() throws Exception {
        String subtype = DocumentModelDraft.named("ProductMovie").locale("en_US")
                .annotation("superTypes", "ProductSingle,ProductBase")   // comma-separated supertype model ids
                .rootGroup("Movie", m -> m.field("Title", FieldKind.STRING))
                .build().toJson();
        assertTrue(kernelRoundTrip(subtype).contains("superTypes"), "the subtype's superTypes annotation survives");

        String base = DocumentModelDraft.named("Product").locale("en_US")
                .annotation("abstract", "true")
                .annotation("subTypes", "ProductMovie,ProductBook")
                .rootGroup("Product", p -> p.field("Sku", FieldKind.STRING))
                .build().toJson();
        String rt = kernelRoundTrip(base);
        assertTrue(rt.contains("abstract") && rt.contains("subTypes"),
                () -> "the base's abstract + subTypes annotations survive: " + rt);
    }

    /** Deserialize → serialize through the kernel's own serializer (what survives is what the kernel actually read). */
    private static String kernelRoundTrip(String json) throws Exception {
        ModelLoader.loadSelfContained(new StringReader(json));   // kernel-valid
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModel model = serializer.deserialize(new StringReader(json));
        StringWriter out = new StringWriter();
        serializer.serialize(model, out, n -> { });
        return out.toString();
    }
}
