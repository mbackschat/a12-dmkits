package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;


import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.edit.EditSession;

/**
 * modelkit — {@code indexFieldName} on a group: the child field whose value identifies a
 * row, so two repeatable groups can be iterated in parallel (a kernel slot, used by the logistics and
 * order fixtures). Kernel is the oracle: a repeatable group carrying an index field loads + expands.
 * Own domain (a shipment with parallel line/pick rows).
 */
class IndexFieldTest {

    private static DocumentModelDraft shipment() {
        return DocumentModelDraft.named("shipment").locale("en_US")
                .rootGroup("Shipment", s -> s
                        .field("ShipmentRef", FieldKind.STRING)
                        .group("Lines", lines -> lines
                                .repeatable(50).indexField("LineRef")
                                .field("LineRef", FieldKind.STRING)
                                .field("OrderedQty", FieldKind.NUMBER)))
                .build();
    }

    private static String withIndexReference(String dm, String reference) throws Exception {
        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(dm);
        for (JsonNode element : tree.path("content").path("modelRoot").path("rootGroups").path(0)
                .path("Group").path("elements")) {
            if ("Lines".equals(element.path("name").asText())) {
                ((ObjectNode) element.path("Group")).put("indexFieldName", reference);
                return tree.toString();
            }
        }
        throw new IllegalStateException("the typed shipment must contain Lines");
    }

    @Test
    void aKernelAdmittedRelativeIndexReferenceSurvivesBothTypedWritePaths() throws Exception {
        var session = EditSession.read(shipment().toJson());
        session.group("/Shipment").addField("LineRef", FieldKind.STRING);
        var lineEditor = session.group("/Shipment/Lines");
        String groupPath = lineEditor.ref().path().value();
        String control = session.toJson();
        assertEquals(Verification.KERNEL_CONFIRMED, ModelLoader.loadSelfContained(control));
        String relative = withIndexReference(control, "../LineRef");
        assertEquals(Verification.KERNEL_CONFIRMED, ModelLoader.loadSelfContained(relative));
        assertAll(
                () -> {
                    var doc = ModelDocument.read(control).setIndexField(groupPath, "../LineRef");
                    assertEquals("../LineRef", doc.indexField(groupPath).orElseThrow());
                    assertEquals(Verification.KERNEL_CONFIRMED, ModelLoader.loadSelfContained(doc.toJson()));
                },
                () -> {
                    lineEditor.setIndexField("../LineRef");
                    session.validate();
                    assertEquals("../LineRef", ModelDocument.read(session.toJson())
                            .indexField(groupPath).orElseThrow());
                },
                () -> {
                    var draft = DocumentModelDraft.named("shipment").locale("en_US")
                            .rootGroup("Shipment", root -> root.field("LineRef", FieldKind.STRING)
                                    .group("Lines", lines -> lines.repeatable(50).indexField("../LineRef")
                                            .field("LineRef", FieldKind.STRING)))
                            .build();
                    assertEquals("../LineRef", ModelDocument.read(draft.toJson())
                            .indexField(groupPath).orElseThrow());
                    assertEquals(Verification.KERNEL_CONFIRMED, ModelLoader.loadSelfContained(draft.toJson()));
                });
    }

    private static ModelIndex index(DocumentModelDraft draft) {
        try {
            assertEquals(Verification.KERNEL_CONFIRMED, ModelLoader.loadSelfContained(draft.toJson()));
            return ModelIndex.ofSelfContained(draft.toJson());
        } catch (Exception e) {
            throw new RuntimeException("emitted DM-JSON failed checkConsistency:\n" + draft.toJson(), e);
        }
    }

    @Test
    void aRepeatableGroupWithAnIndexFieldIsKernelValid() {
        ModelIndex index = index(shipment());
        assertTrue(index.isRepeating("/Shipment/Lines"), "the index-keyed group should repeat");
    }

    @Test
    void emitsTheIndexFieldName() {
        assertTrue(shipment().toJson().contains("\"indexFieldName\" : \"LineRef\""), shipment()::toJson);
    }

    @Test
    void theIndexFieldMustNameADirectChildField() throws Exception {
        String missing = withIndexReference(shipment().toJson(), "NotHere");
        assertThrows(ModelConsistencyException.class, () -> ModelLoader.loadSelfContained(missing));
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(shipment().toJson()).setIndexField("/Shipment/Lines", "NotHere"));
        // referential integrity, caught fail-fast before the kernel
        assertThrows(IllegalStateException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.group("Rows", r -> r
                        .repeatable(3).indexField("NotHere")
                        .field("Present", FieldKind.STRING)))
                .build());
    }

    @Test
    void aNonRepeatableGroupWithAnIndexFieldIsKernelValid() {
        DocumentModelDraft draft = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("G", g -> g.group("Single", r -> r
                        .indexField("Key")
                        .field("Key", FieldKind.STRING)))
                .build();
        assertFalse(index(draft).isRepeating("/G/Single"));
        assertTrue(draft.toJson().contains("\"indexFieldName\" : \"Key\""), draft::toJson);
        EditSession session = EditSession.read(draft.toJson());
        var group = session.group("/G/Single");
        String groupPath = group.ref().path().value();
        assertEquals("Key", ModelDocument.read(session.toJson()).indexField(groupPath).orElseThrow());
        session.clearIndexField(groupPath);
        assertTrue(ModelDocument.read(session.toJson()).indexField(groupPath).isEmpty());
        group.setIndexField("Key");
        session.validate();
        assertEquals("Key", ModelDocument.read(session.toJson())
                .indexField(groupPath).orElseThrow());
    }
}
