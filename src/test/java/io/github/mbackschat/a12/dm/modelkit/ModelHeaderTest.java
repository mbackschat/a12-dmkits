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
}
