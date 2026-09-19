package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IGroup;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * A group's per-locale display <b>label</b> (the kernel's {@code ILabeled}, which {@code IGroup} carries via
 * {@code IEntity} — the same interface a field's label comes from). Authored on the group builder, uniform
 * with a field label. Verified by kernel read-back ({@code IGroup.getLabel()}), since the DM-JSON deserializer
 * ignores unknown keys (KERNEL-FINDINGS), so a clean load alone proves nothing. Re-homed to {@code :adapter} at
 * decoupling Stage 2 (kernel-oracle verification); {@code dm.core.ModelIndex} is kernel-free.
 *
 * <p>A label is <em>not</em> required to cover every declared locale — that law belongs to enum values alone
 * (KERNEL-FINDINGS KF321/KF406, API-FINDINGS AF149). {@code ElementLabelLocaleTest} owns that measurement;
 * a test demanding a builder refusal here was removed once the coverage demand was, on 2026-09-14.
 */
class GroupLabelTest {

    private static KernelModelOracle load(String json) {
        return KernelModelOracle.ofSelfContained(json);
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
