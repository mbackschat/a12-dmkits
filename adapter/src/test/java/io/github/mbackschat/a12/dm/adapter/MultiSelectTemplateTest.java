package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IGroup;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

/**
 * The <b>Multi-Select</b> group template (FEATURE-GAPS). A {@code usageType: "multi-select"} group
 * holds exactly one repeatable enum/string field (each row = one selection). The kernel's
 * {@code MultiSelectGroupRule} validates it at {@code checkConsistency} (KERNEL-FINDINGS), so a correct
 * template <em>loads</em> and a malformed one is <em>rejected</em> — the kernel is the oracle. Re-homed to
 * {@code :adapter} at decoupling Stage 2; {@code dm.core.ModelIndex} is kernel-free.
 */
class MultiSelectTemplateTest {

    private static KernelModelOracle load(String json) {
        return KernelModelOracle.ofSelfContained(json);
    }

    @Test
    void anEnumMultiSelectIsAcceptedAndCarriesTheUsageTypeMarker() throws Exception {
        String json = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Product", p -> p
                        .multiSelectGroup("Conditions", m -> m
                                .enumField("Condition", "NEW", "USED", "REFURBISHED")
                                .maxRows(3)))
                .build().toJson();

        KernelModelOracle index = load(json);   // kernel-valid → MultiSelectGroupRule passed
        IGroup group = index.group("/Product/Conditions");
        assertEquals("multi-select", group.getUsageType().orElse(null), "the usageType marker round-trips");
        assertTrue(index.isRepeating("/Product/Conditions"), "a multi-select is a repeatable group");
        assertEquals(java.util.List.of("Condition"), index.fieldPaths("/Product/Conditions")
                .map(p -> p.substring(p.lastIndexOf('/') + 1)).toList(), "exactly the one selectable field");
    }

    @Test
    void aStringMultiSelectIsAlsoAccepted() throws Exception {
        String json = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Product", p -> p
                        .multiSelectGroup("Tags", m -> m.stringField("Tag").maxRows(5)))
                .build().toJson();
        assertEquals("multi-select", load(json).group("/Product/Tags").getUsageType().orElse(null));
    }

    @Test
    void aConfiguredStringMultiSelectPreservesItsFieldConfigurationAndTemplateRequiredness() throws Exception {
        String json = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Product", p -> p.multiSelectGroup("Tags", m -> m
                        .stringField("Tag", s -> s.minLength(2).maxLength(8).pattern("^[a-z]+$", "Check tag format.").label("en_US", "Tag"))
                        .maxRows(5)))
                .build().toJson();
        assertEquals("multi-select", load(json).group("/Product/Tags").getUsageType().orElse(null));
        ModelDocument model = ModelDocument.read(json);
        assertEquals(java.util.OptionalInt.of(2), model.fieldMinLength("/Product/Tags/Tag"));
        assertEquals(java.util.OptionalInt.of(8), model.fieldMaxLength("/Product/Tags/Tag"));
        assertEquals(java.util.Optional.of("^[a-z]+$"), model.fieldPattern("/Product/Tags/Tag"));
        assertEquals(java.util.Optional.of(Requiredness.REQUIRED), model.requiredness("/Product/Tags/Tag"));
        assertEquals(java.util.Optional.of(java.util.Map.of("en_US", "Tag")), model.labels("/Product/Tags/Tag"));
    }

    @Test
    void aSingleRepetitionMultiSelectIsRejectedByTheKernel() throws Exception {
        // tamper: the same group with repeatability 1 — MultiSelectGroupRule requires a repeatable group
        String good = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Product", p -> p
                        .multiSelectGroup("Conditions", m -> m.enumField("Condition", "NEW", "USED").maxRows(2)))
                .build().toJson();
        String tampered = good.replace("\"repeatability\" : 2", "\"repeatability\" : 1");
        assertTrue(tampered.contains("\"repeatability\" : 1"), "the tamper applied");

        ModelConsistencyException ex = assertThrows(ModelConsistencyException.class,
                () -> ModelLoader.loadSelfContained(tampered));
        assertTrue(ex.getMessage().toLowerCase().contains("multi") || ex.getMessage().contains("MULTI_SELECT"),
                () -> "the kernel rejects a non-repeatable multi-select: " + ex.getMessage());
    }
}
