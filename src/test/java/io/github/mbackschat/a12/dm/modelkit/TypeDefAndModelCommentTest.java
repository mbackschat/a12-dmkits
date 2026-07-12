package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * Descriptions/annotations on <b>type definitions</b> (the kernel's {@code IFieldTypeDefinition} is
 * {@code IDescribed}+{@code IAnnotated}) and the model-level <b>comment</b> ({@code modelInfo.comment}).
 * Type-defs are addressed by <em>id</em> through the same metadata API as elements (the locator falls back
 * to {@code typeDefinitions}). Read-back via the kernel's deserialize→serialize round-trip (survives ⇒ read).
 */
class TypeDefAndModelCommentTest {

    private static final IDocumentModelSerializer SER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    private static String kernelRoundTrip(String json) throws Exception {
        IDocumentModel model = SER.deserialize(new StringReader(json));
        StringWriter out = new StringWriter();
        SER.serialize(model, out, n -> { });
        return out.toString();
    }

    private static String base() {
        return DocumentModelDraft.named("m").locale("en_US")
                .typeDef("Money", t -> t.number(n -> n.fractionDigits(2)))
                .rootGroup("Order", g -> g.typedefField("Total", "Money"))
                .build().toJson();
    }

    @Test
    void authoredTypeDefMetadataAndModelCommentSurvive() throws Exception {
        String json = DocumentModelDraft.named("m").locale("en_US")
                .comment("a demo order model")
                .typeDef("Money", t -> t.number(n -> n.fractionDigits(2))
                        .internalDescription("en_US", "a currency amount")
                        .annotation("unit", "EUR"))
                .rootGroup("Order", g -> g.typedefField("Total", "Money"))
                .build().toJson();

        ModelLoader.loadSelfContained(json);   // kernel-valid
        assertEquals("a demo order model", ModelDocument.read(json).modelComment().orElse(null));
        String round = kernelRoundTrip(json);
        assertTrue(round.contains("a currency amount"), () -> "type-def description survives: " + round);
        assertTrue(round.contains("EUR"), "type-def annotation survives");
        assertTrue(round.contains("a demo order model"), "model comment survives");
    }

    @Test
    void editsTypeDefByIdAndModelComment_thenRemoves() throws Exception {
        ModelDocument doc = ModelDocument.read(base())
                .setInternalDescription("Money", "en_US", "edited note")   // a type-def, addressed by id
                .setAnnotation("Money", "unit", "USD")
                .setModelComment("edited comment");
        assertEquals("edited comment", doc.modelComment().orElse(null));
        String round = kernelRoundTrip(doc.toJson());
        assertTrue(round.contains("edited note") && round.contains("USD") && round.contains("edited comment"),
                () -> "type-def + model-comment edits survive: " + round);

        doc.removeInternalDescription("Money", "en_US").removeAnnotation("Money", "unit").clearModelComment();
        assertTrue(doc.modelComment().isEmpty(), "model comment cleared");
        String round2 = kernelRoundTrip(doc.toJson());
        assertFalse(round2.contains("edited note"), "type-def description removed");
        assertFalse(round2.contains("USD"), "type-def annotation removed");
    }
}
