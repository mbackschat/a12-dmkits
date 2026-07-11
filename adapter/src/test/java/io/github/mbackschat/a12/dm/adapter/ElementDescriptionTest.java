package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IField;
import com.mgmtp.a12.kernel.md.model.api.IGroup;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * Element <b>descriptions + annotations</b> (the kernel's {@code IDescribed} / {@code IAnnotated}) across
 * elements, via the shared {@code ElementMeta} describe/annotate surface (author) and the element-path
 * edit API ({@code ModelDocument.setInternalDescription} / …). Verified by <b>kernel read-back</b>
 * ({@code IElement.getInternalDescription()} = a {@code Map<Locale,String>}), so a wrong key would be
 * caught — not merely tolerated by the ignore-unknown deserializer. Re-homed to {@code :adapter} at
 * decoupling Stage 2 (kernel-oracle verification); {@code dm.core.ModelIndex} is kernel-free.
 */
class ElementDescriptionTest {

    private static KernelModelOracle load(String json) {
        return KernelModelOracle.ofSelfContained(json);
    }

    @Test
    void authoredGroupDescriptionsAndAnnotationRoundTrip() throws Exception {
        String json = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("Order", g -> g
                        .internalDescription("en_US", "internal note")
                        .externalDescription("en_US", "user note")
                        .annotation("owner", "orders-team")
                        .field("Ref", FieldKind.STRING))
                .build().toJson();
        IGroup group = load(json).group("/Order");
        assertEquals("internal note", group.getInternalDescription().get(Locale.US), "group internalDescription honored");
        assertEquals("user note", group.getExternalDescription().get(Locale.US), "group externalDescription honored");
        assertEquals(1, group.getAnnotations().size(), "the group annotation is carried");
        assertTrue(json.contains("orders-team"));
    }

    @Test
    void editPathSetsThenRemovesDescriptionsAndAnnotationsOnFieldAndGroup() throws Exception {
        String base = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("Order", g -> g.field("Ref", FieldKind.STRING))
                .build().toJson();

        ModelDocument doc = ModelDocument.read(base)
                .setInternalDescription("/Order", "en_US", "g-int")
                .setExternalDescription("/Order/Ref", "en_US", "f-ext")
                .setAnnotation("/Order", "k", "v");

        KernelModelOracle set = load(doc.toJson());
        assertEquals("g-int", set.group("/Order").getInternalDescription().get(Locale.US), "group description set");
        IField ref = set.field("/Order/Ref");
        assertEquals("f-ext", ref.getExternalDescription().get(Locale.US), "field description set on an existing model");
        assertEquals(1, set.group("/Order").getAnnotations().size(), "annotation set");

        doc.removeInternalDescription("/Order", "en_US").removeAnnotation("/Order", "k");
        KernelModelOracle removed = load(doc.toJson());
        assertTrue(removed.group("/Order").getInternalDescription().isEmpty(), "internal description removed");
        assertTrue(removed.group("/Order").getAnnotations().isEmpty(), "annotation removed");
    }
}
