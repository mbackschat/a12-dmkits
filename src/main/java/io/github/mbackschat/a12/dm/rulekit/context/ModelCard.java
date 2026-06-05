package io.github.mbackschat.a12.dm.rulekit.context;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A compact, structured map of a model (F9a {@code describe()}) — every field with its kind (plus
 * decimal scale / enum domain where they apply) and every group with its repeatability. This is what
 * an agent reads to orient itself, instead of burning context on raw DM-JSON.
 */
public record ModelCard(List<Field> fields, List<Group> groups) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ModelCard {
        fields = List.copyOf(fields);
        groups = List.copyOf(groups);
    }

    /** A field: path, short name, value kind, plus scale (numbers) / value domain (enums) when relevant. */
    public record Field(String path, String name, String kind, Optional<Integer> scale, List<String> enumValues) {
        public Field {
            scale = scale == null ? Optional.empty() : scale;
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    /** A group: path and whether it repeats. */
    public record Group(String path, boolean repeatable) {
    }

    /** The card as agent-parseable JSON (files-as-API): {@code {fields:[…], groups:[…]}}. */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode fieldNodes = root.putArray("fields");
        for (Field f : fields) {
            ObjectNode node = fieldNodes.addObject();
            node.put("path", f.path());
            node.put("name", f.name());
            node.put("kind", f.kind());
            f.scale().ifPresent(s -> node.put("scale", s));
            if (!f.enumValues().isEmpty()) {
                ArrayNode values = node.putArray("values");
                f.enumValues().forEach(values::add);
            }
        }
        ArrayNode groupNodes = root.putArray("groups");
        for (Group g : groups) {
            ObjectNode node = groupNodes.addObject();
            node.put("path", g.path());
            node.put("repeatable", g.repeatable());
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render the model card as JSON", e);
        }
    }
}
