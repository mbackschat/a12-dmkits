package io.github.mbackschat.a12.dm.rulekit.context;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A compact, structured map of a model (F9a {@code describe()}) — every field with its kind (plus
 * decimal scale / enum domain where they apply), every group with its repeatability, and the declared
 * message {@code locales}. This is what an agent reads to orient itself, instead of burning context on
 * raw DM-JSON. The {@code locales} are the orientation cue for the per-locale-message law: a rule or
 * computation must carry a message for <em>each</em> ({@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING}).
 */
public record ModelCard(List<Field> fields, List<Group> groups, List<String> locales) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ModelCard {
        fields = List.copyOf(fields);
        groups = List.copyOf(groups);
        locales = locales == null ? List.of() : List.copyOf(locales);
    }

    /** A field: path, short name, value kind, plus the declared bounds it carries — scale + numeric
     *  {@code min}/{@code max} (numbers), {@code minLength}/{@code maxLength}/{@code pattern} (strings), and the
     *  value domain (enums) — each present only when declared. */
    public record Field(String path, String name, String kind, Optional<Integer> scale,
                        Optional<BigDecimal> min, Optional<BigDecimal> max,
                        OptionalInt minLength, OptionalInt maxLength, Optional<String> pattern,
                        List<String> enumValues) {
        public Field {
            scale = scale == null ? Optional.empty() : scale;
            min = min == null ? Optional.empty() : min;
            max = max == null ? Optional.empty() : max;
            minLength = minLength == null ? OptionalInt.empty() : minLength;
            maxLength = maxLength == null ? OptionalInt.empty() : maxLength;
            pattern = pattern == null ? Optional.empty() : pattern;
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    /** A group: path, whether it repeats, and (when it does) its maximum repetitions. */
    public record Group(String path, boolean repeatable, int maxRepetitions) {
    }

    /** The card as agent-parseable JSON (files-as-API): {@code {fields:[…], groups:[…], locales:[…]}}. */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode fieldNodes = root.putArray("fields");
        for (Field f : fields) {
            ObjectNode node = fieldNodes.addObject();
            node.put("path", f.path());
            node.put("name", f.name());
            node.put("kind", f.kind());
            f.scale().ifPresent(s -> node.put("scale", s));
            f.min().ifPresent(v -> node.put("min", v));
            f.max().ifPresent(v -> node.put("max", v));
            f.minLength().ifPresent(v -> node.put("minLength", v));
            f.maxLength().ifPresent(v -> node.put("maxLength", v));
            f.pattern().ifPresent(v -> node.put("pattern", v));
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
            if (g.repeatable()) {
                node.put("maxRepetitions", g.maxRepetitions());   // the numeric max rows, only when it repeats
            }
        }
        ArrayNode localeNodes = root.putArray("locales");
        locales.forEach(localeNodes::add);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render the model card as JSON", e);
        }
    }
}
