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

    /** Every field kind the group builder declares inline is also a type definition the kernel builds and reads back. */
    @Test
    void everyFieldKindIsAKernelValidTypeDefThatReadsBackAsItsKind() {
        String json = DocumentModelDraft.named("m").locale("en_US")
                .typeDef("Settled", t -> t.bool(b -> b.trueValue("yes")))
                .typeDef("Signed", t -> t.confirm(b -> { }))
                .typeDef("Incurred", t -> t.date(d -> d.format("dd.MM.yyyy")))
                .typeDef("Reported", t -> t.time(d -> d.format("HH:mm:ss")))
                .typeDef("Logged", t -> t.dateTime(d -> d.format("yyyy-MM-dd'T'HH:mm:ss")))
                .typeDef("Cover", t -> t.dateRange(d -> d.format("dd.MM.yyyy").separator("-")))
                .typeDef("Period", t -> t.dateFragment(d -> d.fragmentFormat("yyyy-MM")))
                .typeDef("Policy", t -> t.custom(c -> c.type("PolicyNumber")))
                .rootGroup("Claim", g -> g.typedefField("Paid", "Settled").typedefField("Ack", "Signed")
                        .typedefField("On", "Incurred").typedefField("At", "Reported").typedefField("Entered", "Logged")
                        .typedefField("Covered", "Cover").typedefField("Month", "Period").typedefField("No", "Policy"))
                .build().toJson();

        ModelLoader.loadSelfContained(json);
        var types = ModelDocument.read(json).typeDefinitions();
        assertEquals(new FieldTypeSpec.BooleanSpec("yes", null), types.get("Settled").type());
        assertEquals(new FieldTypeSpec.ConfirmSpec(null, null), types.get("Signed").type());
        assertEquals(new FieldTypeSpec.DateSpec("dd.MM.yyyy", null, false), types.get("Incurred").type());
        assertEquals(new FieldTypeSpec.TimeSpec("HH:mm:ss"), types.get("Reported").type());
        assertEquals(new FieldTypeSpec.DateTimeSpec("yyyy-MM-dd'T'HH:mm:ss"), types.get("Logged").type());
        assertEquals("dd.MM.yyyy", ((FieldTypeSpec.DateRangeSpec) types.get("Cover").type()).format());
        assertEquals(new FieldTypeSpec.DateFragmentSpec("yyyy-MM", false), types.get("Period").type());
        assertEquals(new FieldTypeSpec.CustomSpec("PolicyNumber", null, null), types.get("Policy").type());
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
