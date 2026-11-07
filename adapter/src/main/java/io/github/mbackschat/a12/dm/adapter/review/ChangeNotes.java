package io.github.mbackschat.a12.dm.adapter.review;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * Every element's and type definition's {@code changeNote} (DM 29.6), keyed as the diff keys them: an element by its
 * name-path, a type definition by its id. Read in one traversal, because looking each element up by path costs a
 * walk of the tree per element.
 */
final class ChangeNotes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChangeNotes() {}

    static Map<String, String> of(ModelDocument document) {
        JsonNode content;
        try {
            content = JSON.readTree(document.toJson()).path(DmKeys.CONTENT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a read model failed to re-parse", e);
        }
        Map<String, String> notes = new LinkedHashMap<>();
        collect(content.path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), "", notes);
        for (JsonNode definition : content.path(DmKeys.TYPE_DEFINITIONS)) {
            put(notes, definition.path(DmKeys.ID).asText(), definition);
        }
        return Map.copyOf(notes);
    }

    private static void collect(JsonNode elements, String parent, Map<String, String> notes) {
        for (JsonNode element : elements) {
            String path = parent + "/" + element.path(DmKeys.NAME).asText();
            put(notes, path, element);
            collect(element.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS), path, notes);
        }
    }

    private static void put(Map<String, String> notes, String id, JsonNode node) {
        JsonNode note = node.path(DmKeys.CHANGE_NOTE);
        if (note.isTextual()) {
            notes.putIfAbsent(id, note.asText());
        }
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
