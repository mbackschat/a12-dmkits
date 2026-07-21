package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** JSON boundary for the single supported profile format. */
final class ModelProfileJson {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ModelProfileJson() {}

    static String write(SynthesisProfile profile) {
        Objects.requireNonNull(profile, "profile");
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(profile) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize model profile", e);
        }
    }

    static SynthesisProfile read(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode profile = root.has("structure") ? root : root.path("data");
            if (!profile.isObject()) {
                throw new IllegalArgumentException("profile JSON must be a bare profile or extraction envelope");
            }
            rejectNegativeNumbers(profile, "$");
            return JSON.treeToValue(profile, SynthesisProfile.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not a valid model profile JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static void rejectNegativeNumbers(JsonNode node, String path) {
        if (node.isIntegralNumber() && node.longValue() < 0) {
            throw new IllegalArgumentException("profile value must be non-negative at " + path);
        }
        if (node.isObject()) {
            node.properties().forEach(entry ->
                    rejectNegativeNumbers(entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectNegativeNumbers(node.get(index), path + "[" + index + "]");
            }
        }
    }
}
