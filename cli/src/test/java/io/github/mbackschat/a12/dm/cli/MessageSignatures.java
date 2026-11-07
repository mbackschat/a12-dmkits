package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A runtime leg's {@code messages[]} as order-free signatures, so the interpreter and kernel legs compare on what
 * A12 promises: neither the message order nor an address list's order is matched, and a group of possibly-causing
 * entities is a set, so only the order of the groups themselves is (KERNEL-FINDINGS KF683).
 */
final class MessageSignatures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageSignatures() {
    }

    static List<String> of(JsonNode messages) {
        List<String> signatures = new ArrayList<>();
        for (JsonNode original : messages) {
            ObjectNode message = original.deepCopy();
            for (String channel : List.of("referenced", "fillToFix")) {
                if (message.has(channel)) {
                    message.set(channel, sorted(message.path(channel)));
                }
            }
            ArrayNode groups = MAPPER.createArrayNode();
            message.path("possiblyCausing").forEach(group -> groups.add(sorted(group)));
            message.set("possiblyCausing", groups);
            signatures.add(message.toString());
        }
        signatures.sort(Comparator.naturalOrder());
        return signatures;
    }

    private static ArrayNode sorted(JsonNode addresses) {
        List<JsonNode> pointers = new ArrayList<>();
        addresses.forEach(pointers::add);
        pointers.sort(Comparator.comparing(JsonNode::asText));
        return MAPPER.valueToTree(pointers);
    }
}
