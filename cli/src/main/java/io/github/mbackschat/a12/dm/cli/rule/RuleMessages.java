package io.github.mbackschat.a12.dm.cli.rule;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

/**
 * The single source for emitting a rule's (or computation's) stored error messages (locale → text),
 * <b>raw + uninterpolated</b> (G10) — native-capable, no {@code model eval}. Shared by {@code rule read},
 * {@code rule explain}, and {@code computation read} so they can't drift on the {@code messages} shape (the
 * message is the modeler's own NL — the best seed for an agent explaining the element to a user).
 */
public final class RuleMessages {

    private RuleMessages() {
    }

    /** Append a {@code messages: [{locale, text}]} array to {@code node} (empty when the element carries none). */
    public static void putInto(ObjectNode node, List<LocalizedText> messages) {
        ArrayNode arr = node.putArray("messages");
        for (LocalizedText m : messages) {
            arr.addObject().put("locale", m.locale()).put("text", m.text());
        }
    }
}
