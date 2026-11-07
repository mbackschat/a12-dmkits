package io.github.mbackschat.a12.dm.adapter.review;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * Every element's and type definition's {@code changeNote} (DM 29.6), keyed as the diff keys them: an element by its
 * name-path, a type definition by its id. Read through the document's own tree, because re-parsing its JSON would
 * need a second, A12-configured parser for the same content.
 */
final class ChangeNotes {

    private ChangeNotes() {}

    static Map<String, String> of(ModelDocument document, ModelDocument.AuthoredState authored) {
        Map<String, String> notes = new LinkedHashMap<>();
        authored.elements().forEach(element -> document.changeNote(element.path())
                .ifPresent(note -> notes.putIfAbsent(element.path(), note)));
        document.typeDefinitionIds().forEach(id -> document.changeNote(id).ifPresent(note -> notes.putIfAbsent(id, note)));
        return Map.copyOf(notes);
    }

    /** {@code aspects} with each element's note added as its {@code changeNote} aspect. */
    static Map<String, Map<String, String>> attach(Map<String, String> notes, Map<String, Map<String, String>> aspects) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        aspects.forEach((id, own) -> {
            Map<String, String> with = new LinkedHashMap<>(own);
            String note = notes.get(id);
            if (note != null) {
                with.put("changeNote", note);
            }
            out.put(id, with);
        });
        return out;
    }
}
