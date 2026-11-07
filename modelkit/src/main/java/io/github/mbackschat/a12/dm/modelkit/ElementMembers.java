package io.github.mbackschat.a12.dm.modelkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;

/** How {@link ModelDocument} reads and writes the members of a describable element, a type definition or the header. */
final class ElementMembers {

    private ElementMembers() {
    }

    /** Set the value of {@code owner}'s annotation named {@code name}, or append one. */
    static void upsertAnnotation(ObjectNode owner, String name, String value) {
        ArrayNode annotations = owner.has(DmKeys.ANNOTATIONS)
                ? (ArrayNode) owner.get(DmKeys.ANNOTATIONS)
                : owner.putArray(DmKeys.ANNOTATIONS);
        for (JsonNode a : annotations) {
            if (name.equals(a.path(DmKeys.NAME).asText())) {
                ((ObjectNode) a).put(DmKeys.VALUE, value);
                return;
            }
        }
        annotations.addObject().put(DmKeys.NAME, name).put(DmKeys.VALUE, value);
    }

    /** Drop every entry of {@code element[key]} whose {@code field} reads {@code matchValue}; remove the array if it empties. */
    static void filterArray(ObjectNode element, String key, Function<JsonNode, String> field, String matchValue) {
        JsonNode array = element.path(key);
        if (!array.isArray()) {
            return;
        }
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        for (JsonNode e : array) {
            if (!matchValue.equals(field.apply(e))) {
                kept.add(e);
            }
        }
        if (kept.isEmpty()) {
            element.remove(key);
        } else {
            element.set(key, kept);
        }
    }

    static Map<String, String> localizedOf(ObjectNode element, String key) {
        return entryMap(element.path(key), DmKeys.LOCALE, DmKeys.TEXT);
    }

    /** A {@code [{keyField, valueField}]} array as a map, in entry order. */
    static Map<String, String> entryMap(JsonNode entries, String keyField, String valueField) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            out.put(entry.path(keyField).asText(), entry.path(valueField).asText());
        }
        return out;
    }

    /** The note of {@code element}, A12's one unlocalized {@code changeNote} on it; empty when it carries none. */
    static Optional<String> changeNote(ObjectNode element) {
        JsonNode note = element.path(DmKeys.CHANGE_NOTE);
        return note.isTextual() ? Optional.of(note.asText()) : Optional.empty();
    }

    /**
     * A12's order for the members a describable element opens with, every one before the element's body member
     * ({@code ElementSerializerUtil.serializeElementBase}, kernel 31.3.1).
     */
    private static final List<String> ELEMENT_HEAD = List.of(DmKeys.TYPE, DmKeys.ID, DmKeys.NAME, DmKeys.ANNOTATIONS,
            DmKeys.EXTERNAL_DESCRIPTION, DmKeys.INTERNAL_DESCRIPTION, DmKeys.CHANGE_NOTE);

    /**
     * A12's order for a type definition's members, its descriptions the other way round, as A12's serializer
     * re-emitted them (measured 2026-11-07, {@code adapter.ElementMemberOrderTest}).
     */
    private static final List<String> TYPE_DEFINITION = List.of(DmKeys.ID, DmKeys.NAME, DmKeys.INTERNAL_DESCRIPTION,
            DmKeys.EXTERNAL_DESCRIPTION, DmKeys.ANNOTATIONS, DmKeys.FIELD_TYPE, "revision", DmKeys.CHANGE_NOTE);

    /** Set the note, replacing one {@code element} carries. */
    static void setChangeNote(ObjectNode element, String note) {
        element.put(DmKeys.CHANGE_NOTE, note);
    }

    /**
     * Put the members an edit may have appended where A12 writes them: an element's {@link #ELEMENT_HEAD} before
     * its body, a type definition's in {@link #TYPE_DEFINITION} order; any other member follows in its order. The
     * header, which is neither, keeps its order.
     */
    static void inA12Order(ObjectNode node) {
        List<String> order = node.has(node.path(DmKeys.TYPE).asText()) ? ELEMENT_HEAD
                : node.has(DmKeys.FIELD_TYPE) ? TYPE_DEFINITION : List.of();
        Map<String, JsonNode> members = new LinkedHashMap<>();
        order.stream().filter(node::has).forEach(key -> members.put(key, node.get(key)));
        node.properties().forEach(member -> members.putIfAbsent(member.getKey(), member.getValue()));
        node.removeAll();
        node.setAll(members);
    }
}
