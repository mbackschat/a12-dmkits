package io.github.mbackschat.a12.dm.edit;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * Refactor (CLI-SPEC §6): <b>move</b> a field/group to a different parent (task #11). The library relocates
 * the node and re-prefixes its id (and, for a group, every descendant id) in step; the CLI rewrites the
 * references first (via {@code adapter.ReferenceRewriter}) so nothing dangles. These tests pin the node
 * surgery + the key properties: a moved field/group lands under the new parent with the old path gone, a
 * group's whole subtree (incl. an internal relative-pathed rule) travels, and the result stays kernel-valid.
 * Moving a group into its own subtree is refused (it would orphan the subtree).
 */
class MoveRefactorTest {

    private static String orderJson() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Note", FieldKind.STRING)
                        .group("Shipping", sh -> sh
                                .field("PostalCode", FieldKind.STRING)
                                .field("Country", FieldKind.STRING))
                        .group("Billing", b -> b
                                .field("Method", FieldKind.STRING)))
                .build()
                .toJson();
    }

    @Test
    void moveFieldRelocatesNodeAndStaysKernelValid() {
        String result = EditSession.read(orderJson())
                .moveField("/Order/Note", "/Order/Shipping").validate().toJson();
        ModelDocument doc = ModelDocument.read(result);
        assertTrue(doc.fieldPaths().contains("/Order/Shipping/Note"), "field re-homed under the new parent");
        assertFalse(doc.fieldPaths().contains("/Order/Note"), "the old path is gone");
    }

    @Test
    void moveGroupRehomesSubtreeAndKeepsAnInternalRuleWorking() {
        // an internal rule scoped at /Order/Shipping, referencing its own fields (relative) — it must
        // travel with the subtree when the group moves under a different parent.
        String withRule = EditSession.read(orderJson())
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("PostalRequired")
                        .errorField(m.str("/Order/Shipping/PostalCode"))
                        .when(m.str("/Order/Shipping/Country").filled()
                                .and(m.str("/Order/Shipping/PostalCode").notFilled()))
                        .code("POSTAL_REQ").severity(Severity.ERROR)
                        .message(en("A postal code is required once a country is set.")).build())
                .toJson();

        String moved = EditSession.read(withRule).moveGroup("/Order/Shipping", "/Order/Billing").validate().toJson();
        ModelDocument doc = ModelDocument.read(moved);
        assertTrue(doc.groupPaths().contains("/Order/Billing/Shipping"), "group re-homed under the new parent");
        assertTrue(doc.fieldPaths().contains("/Order/Billing/Shipping/PostalCode"), "descendant field re-homed");
        assertFalse(moved.contains("/Order/Shipping"), "no stale path remains anywhere");
        assertTrue(moved.contains("PostalRequired"), "the internal rule travelled with the subtree");
    }

    // Reference rewriting on a move (an external rule reference following the field/group to its new path)
    // is the CLI's responsibility — adapter.ReferenceRewriter wraps the kernel's MoveSupportDM, and core
    // cannot depend on the adapter module. It is covered end-to-end by the CLI move-verb tests, not here:
    // the bare core move relocates only the node, so an unrewritten external reference would (correctly) dangle.

    @Test
    void moveGroupIntoItsOwnSubtreeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(orderJson()).moveGroup("/Order/Shipping", "/Order/Shipping"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(orderJson()).moveGroup("/Order/Shipping", "/Order/Shipping/Country"));
    }

    @Test
    void movingAnAbsentElementFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(orderJson()).moveField("/Order/Nope", "/Order/Shipping"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(orderJson()).moveGroup("/Order/Nope", "/Order/Billing"));
    }
}
