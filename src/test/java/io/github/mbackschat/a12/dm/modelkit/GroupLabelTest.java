package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IGroup;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * A group's per-locale display <b>label</b> (the kernel's {@code ILabeled}, which {@code IGroup} carries via
 * {@code IEntity} — the same interface a field's label comes from). Authored on the group builder, uniform
 * with a field label, and — once present — required to cover every declared locale. Verified by kernel
 * read-back ({@code IGroup.getLabel()}), since the DM-JSON deserializer ignores unknown keys (KERNEL-FINDINGS),
 * so a clean load alone proves nothing.
 */
class GroupLabelTest {

    private static ModelIndex load(String json) throws Exception {
        return ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(json)));
    }

    @Test
    void authoredGroupLabelRoundTripsThroughTheKernel() throws Exception {
        String json = DocumentModelDraft.named("m").locale("en_US").locale("de_DE")
                .rootGroup("Order", o -> o
                        .group("Lines", lines -> lines.repeatable(50)
                                .label("en_US", "Order lines").label("de_DE", "Bestellzeilen")
                                .field("Sku", FieldKind.STRING))
                        .field("Ref", FieldKind.STRING))
                .build().toJson();

        IGroup lines = load(json).group("/Order/Lines");
        assertEquals("Order lines", lines.getLabel().get(Locale.US), "group label (en_US) honored by the kernel");
        assertEquals("Bestellzeilen", lines.getLabel().get(Locale.GERMANY), "group label (de_DE) honored");
    }

    @Test
    void rootGroupCarriesALabel() throws Exception {
        String json = DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("Order", o -> o.label("en_US", "Order").field("Ref", FieldKind.STRING))
                .build().toJson();

        assertEquals("Order", load(json).group("/Order").getLabel().get(Locale.US), "root group label honored");
    }

    @Test
    void groupLabelMustCoverEveryDeclaredLocale() {
        // the de_DE label is missing — a SPEC error, uniform with field/enum labels
        assertThrows(IllegalArgumentException.class, () -> DocumentModelDraft.named("m").locale("en_US").locale("de_DE")
                .rootGroup("Order", o -> o.label("en_US", "only english").field("Ref", FieldKind.STRING))
                .build().toJson());
    }

    @Test
    void editPathSetsAGroupLabelOnAnExistingModel() throws Exception {
        String base = DocumentModelDraft.named("m").locale("en_US").locale("de_DE")
                .rootGroup("Order", o -> o.field("Ref", FieldKind.STRING))
                .build().toJson();

        String saved = ModelDocument.read(base)
                .setLabel("/Order", Map.of("en_US", "Order", "de_DE", "Bestellung"))
                .toJson();

        assertEquals("Bestellung", load(saved).group("/Order").getLabel().get(Locale.GERMANY),
                "group label set on an existing model via the raw editor");
        assertTrue(saved.contains("Bestellung"));
    }
}
