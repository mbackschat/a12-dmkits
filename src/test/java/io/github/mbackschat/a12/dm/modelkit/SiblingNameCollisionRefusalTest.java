package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * That the typed draft refuses a sibling name collision instead of emitting a model A12 cannot build.
 *
 * <p><b>The refusal has a measured kernel basis</b> — {@code rulekit.validate.laws.
 * ElementNameUniquenessLawsTest} measures every pairing of kinds NOT BUILDABLE, on the build tier, so A12
 * reports no code at all and says nothing about the rest of the model. Emitting such a model is the worst
 * outcome the builder can produce: the author gets a codeless refusal from somewhere else, naming neither
 * the group nor the name. It went unnoticed until a fixture for another law authored one by accident.
 *
 * <p><b>Only the AUTHORING path refuses.</b> Reading a stored model that carries the collision must keep
 * working, because reporting on exactly that file is what the checker and the CLI are for — so the check
 * sits where a group is finalized from its builder, not in {@link ElementDraft.Group} itself.
 */
class SiblingNameCollisionRefusalTest {

    @Test
    void twoChildrenOfOneNameAreRefusedWhateverKindsTheyAre() {
        assertEquals("Reference", collisionFrom(w -> w
                .field("Reference", FieldKind.STRING).field("Reference", FieldKind.NUMBER)));
        assertEquals("Bay", collisionFrom(w -> w
                .group("Bay", b -> b.field("Note", FieldKind.STRING))
                .group("Bay", b -> b.field("Other", FieldKind.STRING))));
        assertEquals("Weight", collisionFrom(w -> w
                .field("Weight", FieldKind.NUMBER)
                .group("Weight", b -> b.field("Note", FieldKind.STRING))));
    }

    /** A nested group's children are checked too, and the message names the group that holds them. */
    @Test
    void theCollisionIsCaughtAtEveryDepthAndNamesItsGroup() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DocumentModelDraft.named("warehouse").locale("en_US")
                        .rootGroup("Warehouse", w -> w.group("Bay", b -> b
                                .field("Note", FieldKind.STRING).field("Note", FieldKind.NUMBER)))
                        .build());
        assertTrue(refused.getMessage().contains("'Bay'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("'Note'"), refused.getMessage());
    }

    /** The same name under two different groups stays legal — the law is about paths, not names. */
    @Test
    void aNameReusedInAnotherGroupIsUntouched() {
        String json = DocumentModelDraft.named("warehouse").locale("en_US")
                .rootGroup("Warehouse", w -> w
                        .field("Reference", FieldKind.STRING)
                        .group("Bay", b -> b.field("Reference", FieldKind.STRING)))
                .build().toJson();
        assertTrue(json.contains("\"Reference\""), json);
    }

    /** The colliding name the draft refused, taken from its message. */
    private static String collisionFrom(java.util.function.Consumer<DocumentModelDraft.GroupBuilder> body) {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> DocumentModelDraft.named("warehouse").locale("en_US")
                        .rootGroup("Warehouse", body).build());
        String message = refused.getMessage();
        int opened = message.indexOf("declares '") + "declares '".length();
        return message.substring(opened, message.indexOf('\'', opened));
    }
}
