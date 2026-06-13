package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IGroup;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * The <b>Multi-Select</b> group template (FEATURE-GAPS §3.8). A {@code usageType: "multi-select"} group
 * holds exactly one repeatable enum/string field (each row = one selection). The kernel's
 * {@code MultiSelectGroupRule} validates it at {@code checkConsistency} (KERNEL-FINDINGS), so a correct
 * template <em>loads</em> and a malformed one is <em>rejected</em> — the kernel is the oracle.
 */
class MultiSelectTemplateTest {

    private static ModelIndex load(String json) throws Exception {
        return ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(json)));
    }

    @Test
    void anEnumMultiSelectIsAcceptedAndCarriesTheUsageTypeMarker() throws Exception {
        String json = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Product", p -> p
                        .multiSelectGroup("Conditions", m -> m
                                .enumField("Condition", "NEW", "USED", "REFURBISHED")
                                .maxRows(3)))
                .build().toJson();

        ModelIndex index = load(json);   // kernel-valid → MultiSelectGroupRule passed
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
    void aSingleRepetitionMultiSelectIsRejectedByTheKernel() throws Exception {
        // tamper: the same group with repeatability 1 — MultiSelectGroupRule requires a repeatable group
        String good = DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Product", p -> p
                        .multiSelectGroup("Conditions", m -> m.enumField("Condition", "NEW", "USED").maxRows(2)))
                .build().toJson();
        String tampered = good.replace("\"repeatability\" : 2", "\"repeatability\" : 1");
        assertTrue(tampered.contains("\"repeatability\" : 1"), "the tamper applied");

        ModelConsistencyException ex = assertThrows(ModelConsistencyException.class,
                () -> ModelLoader.loadSelfContained(new StringReader(tampered)));
        assertTrue(ex.getMessage().toLowerCase().contains("multi") || ex.getMessage().contains("MULTI_SELECT"),
                () -> "the kernel rejects a non-repeatable multi-select: " + ex.getMessage());
    }
}
