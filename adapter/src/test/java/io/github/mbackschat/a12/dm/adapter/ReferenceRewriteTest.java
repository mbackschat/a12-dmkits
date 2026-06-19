package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The reference-rewriting primitive behind a true (non-refusing) rename/move (FEATURE-GAPS §3.1):
 * {@link ReferenceRewriter} wraps the kernel's own {@code MoveSupportDM}. This locks two behaviors the
 * wiring depends on — a plain referrer is rewritten to the new path, and (verified against the kernel,
 * <em>contrary</em> to {@code MoveSupportInternal}'s javadoc limitation #6 "References with * are not
 * touched") a <b>starred-group reference</b> like {@code Sum(Items* / Amount)} also follows a repeatable-group
 * rename ({@code Items*} to {@code Lines*}) — there is dedicated {@code starredGroupsNewFullnames} handling.
 * So renaming a repeatable group needs no special {@code *}-refusal. (KERNEL-FINDINGS §10.)
 */
class ReferenceRewriteTest {

    /** /Order { Quantity, Items* { Amount } } + a plain rule on Quantity + a `*`-aggregate rule on Items. */
    private static String orderJson() {
        String base = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .numberField("Quantity", n -> n.fractionDigits(0))
                        .group("Items", it -> it.repeatable(99).numberField("Amount", n -> n.fractionDigits(2))))
                .build()
                .toJson();
        return EditSession.read(base)
                .addRule("/Order", "QtyPositive", "Quantity", "QTY_POS",
                        "FieldFilled(Quantity) And [Quantity] < 0", "ERROR", List.of(en("Quantity must be >= 0.")))
                .addRule("/Order", "AmountSum", "Quantity", "AMT_SUM",
                        "Sum(Items*/Amount) < 0", "ERROR", List.of(en("The amount sum must be >= 0.")))
                .toJson();
    }

    @Test
    void rewritesAPlainReferenceToTheNewPath() {
        String result = ReferenceRewriter.rewriteReferences(orderJson(), Map.of("/Order/Quantity", "/Order/Qty"));
        assertTrue(result.contains("FieldFilled(Qty)"), () -> "the referrer's condition follows the rename: " + result);
        assertFalse(result.contains("FieldFilled(Quantity)"), "the old reference is gone from the condition");
    }

    @Test
    void rewritesAStarredGroupReferenceToo() {
        // renaming the repeatable group rewrites its starred reference: Items*/Amount -> Lines*/Amount
        // (the kernel's dedicated starredGroupsNewFullnames handling — its blanket "* not touched" javadoc
        // is misleading for the group-rename case). So no special *-refusal is needed here.
        String result = ReferenceRewriter.rewriteReferences(orderJson(), Map.of("/Order/Items", "/Order/Lines"));
        assertTrue(result.contains("Sum(Lines*/Amount)"), () -> "the starred-group reference follows the rename: " + result);
        assertFalse(result.contains("Items*/Amount"), "the old starred reference is gone");
    }
}
