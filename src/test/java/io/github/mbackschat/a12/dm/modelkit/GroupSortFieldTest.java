package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IGroup;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * Group sort fields (FEATURE-GAPS G28): a repeatable group can declare ordered row {@code sortFields}
 * (each a direct-child field + ASC/DESC). Verified by a <b>kernel read-back</b> — the deserializer ignores
 * unknown keys, so only reading {@code IGroup.getSortFields()} back proves the engine honored what we emit.
 * (Display/USB-only at runtime — the interpreter ignores sort order, INTERPRETER-FINDINGS IF22 — so this is
 * an authoring + read-fidelity feature, not an eval one.)
 */
class GroupSortFieldTest {

    private static IGroup groupAfterRoundTrip(DocumentModelDraft draft, String path) throws Exception {
        return ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(draft.toJson()))).group(path);
    }

    @Test
    void sortFieldsRoundTripThroughTheKernelInDeclaredOrder() throws Exception {
        DocumentModelDraft draft = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.group("Lines", l -> l
                        .repeatable(50)
                        .field("Sku", FieldKind.STRING)
                        .numberField("Amount", n -> { })
                        .sortField("Amount", SortOrder.DESC)
                        .sortField("Sku", SortOrder.ASC)))
                .build();

        List<IGroup.ISortField> sortFields = groupAfterRoundTrip(draft, "/Order/Lines").getSortFields();
        assertEquals(2, sortFields.size(), "both sort fields survive, in declared order");
        assertEquals("Amount", sortFields.get(0).getField().getRelativePath());
        assertEquals(IGroup.ISortField.Order.DESC, sortFields.get(0).getOrder());
        assertEquals("Sku", sortFields.get(1).getField().getRelativePath());
        assertEquals(IGroup.ISortField.Order.ASC, sortFields.get(1).getOrder());
    }

    @Test
    void sortFieldOnANonRepeatableGroupIsRejectedAtBuild() {
        assertThrows(IllegalStateException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("Order", o -> o.group("Line", l -> l
                        .numberField("Amount", n -> { })
                        .sortField("Amount", SortOrder.ASC)))
                .build());
    }

    @Test
    void sortFieldReferencingANonChildIsRejectedAtBuild() {
        assertThrows(IllegalStateException.class, () -> DocumentModelDraft.named("m").locale("en_US")
                .rootGroup("Order", o -> o.group("Lines", l -> l
                        .repeatable(3)
                        .numberField("Amount", n -> { })
                        .sortField("Nonexistent", SortOrder.ASC)))
                .build());
    }
}
