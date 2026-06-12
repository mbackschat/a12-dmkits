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
 * Tier-2 refactor: <b>safe rename</b> of field / group / rule (task #5, CLI-SPEC §6). The library does the
 * declaration rename (name + id, and for a group every descendant id in step); the CLI gate refuses when
 * the element is referenced (since references are not rewritten — that's the parked engine). These tests
 * pin the mechanics + the key property: a group rename re-homes an <em>internal</em> relative-pathed rule
 * without breaking it (it moves with the subtree), confirmed through the kernel.
 */
class RenameRefactorTest {

    private static String orderJson() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Note", FieldKind.STRING)
                        .group("Shipping", sh -> sh
                                .field("PostalCode", FieldKind.STRING)
                                .field("Country", FieldKind.STRING)))
                .build()
                .toJson();
    }

    @Test
    void renameFieldChangesNameAndPathAndStaysKernelValid() {
        String result = EditSession.read(orderJson()).renameField("/Order/Note", "Memo").validate().toJson();
        ModelDocument doc = ModelDocument.read(result);
        assertTrue(doc.fieldPaths().contains("/Order/Memo"));
        assertFalse(doc.fieldPaths().contains("/Order/Note"), "the old path is gone");
    }

    @Test
    void renameGroupRehomesDescendantsAndKeepsAnInternalRuleWorking() {
        // an internal rule scoped at /Order/Shipping, referencing its own fields (relative) — it must
        // travel with the subtree on a group rename.
        String withRule = EditSession.read(orderJson())
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("PostalRequired")
                        .errorField(m.str("/Order/Shipping/PostalCode"))
                        .when(m.str("/Order/Shipping/Country").filled()
                                .and(m.str("/Order/Shipping/PostalCode").notFilled()))
                        .code("POSTAL_REQ").severity(Severity.ERROR)
                        .message(en("A postal code is required once a country is set.")).build())
                .toJson();

        String renamed = EditSession.read(withRule).renameGroup("/Order/Shipping", "Delivery").validate().toJson();
        ModelDocument doc = ModelDocument.read(renamed);
        assertTrue(doc.groupPaths().contains("/Order/Delivery"));
        assertTrue(doc.fieldPaths().contains("/Order/Delivery/PostalCode"), "descendant field re-homed");
        assertFalse(renamed.contains("/Order/Shipping"), "no stale Shipping path remains anywhere");
        assertTrue(renamed.contains("PostalRequired"), "the internal rule travelled with the subtree");
    }

    @Test
    void renameRuleChangesItsNameAndStaysKernelValid() {
        String withRule = EditSession.read(orderJson())
                .addRule(m -> m.rule(m.group("/Order/Shipping")).name("PostalRequired")
                        .errorField(m.str("/Order/Shipping/PostalCode"))
                        .when(m.str("/Order/Shipping/Country").filled()
                                .and(m.str("/Order/Shipping/PostalCode").notFilled()))
                        .code("POSTAL_REQ").severity(Severity.ERROR)
                        .message(en("A postal code is required once a country is set.")).build())
                .toJson();

        String renamed = EditSession.read(withRule)
                .renameRule("/Order/Shipping/PostalRequired", "PostalCodePresent").validate().toJson();
        assertTrue(renamed.contains("PostalCodePresent"));
        assertFalse(renamed.contains("PostalRequired"), "the old rule name is gone");
    }

    @Test
    void renamingAnAbsentElementFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(orderJson()).renameField("/Order/Nope", "X"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelDocument.read(orderJson()).renameGroup("/Order/Nope", "X"));
    }
}
