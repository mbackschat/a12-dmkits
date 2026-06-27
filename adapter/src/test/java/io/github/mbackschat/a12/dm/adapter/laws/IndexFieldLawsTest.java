package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * Index-field auto-rules (FEATURE-GAPS — confirms the ◐). The kernel-team
 * BA doc states that declaring a group's index field <b>auto-generates</b> two rules (no authoring needed):
 * the index value must be <b>unique</b> within each iteration (<code>RepetitionNotUnique(indexField)</code>)
 * and it must be <b>filled</b> when the group is (<code>GroupFilled And FieldNotFilled(indexField)</code>).
 * These differential laws prove that auto-enforcement empirically against the real runtime — no dependence
 * on the generated rules' (kernel-internal) error codes: duplicate index values fire where unique ones don't,
 * and a missing index value on a filled row fires too. So modelkit only declares {@code indexFieldName}; the
 * rule pair is the kernel's.
 */
class IndexFieldLawsTest {

    /** A shipment with a repeatable, index-keyed {@code Lines} group (own domain). LineRef is the row key. */
    private static final String SHIPMENT = DocumentModelDraft.named("shipment").locale("en_US")
            .rootGroup("Shipment", s -> s
                    .field("ShipmentRef", FieldKind.STRING)
                    .group("Lines", lines -> lines
                            .repeatable(50).indexField("LineRef")
                            .field("LineRef", FieldKind.STRING)
                            .field("OrderedQty", FieldKind.NUMBER)))
            .build().toJson();

    /** Two {@code Lines} rows; each carries the given LineRef value at its 1-based row index. */
    private static Set<String> firedForTwoLines(String lineRef1, String lineRef2) {
        RuntimeLaws.Doc doc = RuntimeLaws.doc()
                .group("/Shipment", 1)
                .group("/Shipment/Lines", 1, 1).field("/Shipment/Lines/LineRef", lineRef1, 1, 1, 1)
                .group("/Shipment/Lines", 1, 2).field("/Shipment/Lines/LineRef", lineRef2, 1, 2, 1);
        return RuntimeLaws.firedCodes(SHIPMENT, doc);
    }

    @Test
    void duplicateIndexValuesFireTheAutoUniquenessRuleButUniqueValuesDoNot() {
        assertTrue(firedForTwoLines("A", "B").isEmpty(),
                () -> "distinct LineRefs are valid — nothing fires: " + firedForTwoLines("A", "B"));
        assertFalse(firedForTwoLines("A", "A").isEmpty(),
                "two rows sharing a LineRef must fire the auto-generated uniqueness rule");
    }

    @Test
    void aMissingIndexValueOnAFilledRowFiresTheAutoMandatoryRule() {
        // the row is filled (OrderedQty) but its index LineRef is empty → the auto mandatory rule fires
        RuntimeLaws.Doc doc = RuntimeLaws.doc()
                .group("/Shipment", 1)
                .group("/Shipment/Lines", 1, 1)
                .field("/Shipment/Lines/OrderedQty", "5", 1, 1, 1)
                .emptyField("/Shipment/Lines/LineRef", 1, 1, 1);
        assertFalse(RuntimeLaws.firedCodes(SHIPMENT, doc).isEmpty(),
                "a filled row with no index value must fire the auto-generated mandatory rule");
    }
}
