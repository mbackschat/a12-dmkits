package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;

/**
 * A12's {@code changeNote}, the one unlocalized note every element and type definition may carry (kernel 31.3,
 * {@code IDescribed}): read, set and removed like the descriptions beside it, through the same element reference.
 */
class ModelDocumentChangeNoteTest {

    private static final String MODEL = DocumentModelDraft.named("claims").locale("en_US")
            .typeDef("PolicyNumber", t -> t.string(s -> s.maxLength(12)))
            .rootGroup("Policy", p -> p.stringField("Holder", f -> { })
                    .group("Rider", r -> r.stringField("Code", f -> { })))
            .build().toJson();

    private static final List<String> REFERENCES = List.of("/Policy", "/Policy/Holder", "/Policy/Rider", "PolicyNumber");

    @Test
    void aNoteIsReadWhereItWasSetAndAbsentEverywhereElse() {
        EditSession session = EditSession.read(MODEL);
        REFERENCES.forEach(reference -> session.setChangeNote(reference, "reviewed " + reference));
        session.group("/Policy/Rider").setChangeNote("reviewed again");

        ModelDocument written = ModelDocument.read(session.toJson());

        assertEquals(List.of(Optional.of("reviewed /Policy"), Optional.of("reviewed /Policy/Holder"),
                        Optional.of("reviewed again"), Optional.of("reviewed PolicyNumber")),
                REFERENCES.stream().map(written::changeNote).toList());
        assertEquals(Optional.empty(), written.changeNote("/Policy/Rider/Code"));
    }

    @Test
    void aRemovedNoteLeavesNoMember() {
        String noted = EditSession.read(MODEL).setChangeNote("/Policy/Holder", "reviewed").toJson();

        EditSession session = EditSession.read(noted);
        session.field("/Policy/Holder").removeChangeNote();
        String removed = session.toJson();

        assertEquals(Optional.empty(), ModelDocument.read(removed).changeNote("/Policy/Holder"));
        assertEquals(MODEL, removed, "removing the only note restores the model byte for byte");
    }
}
