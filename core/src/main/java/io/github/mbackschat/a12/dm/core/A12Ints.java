package io.github.mbackschat.a12.dm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.Optional;

/**
 * A model member read as A12's reader binds it to a Java {@code int} ({@link A12IntBinding}), for the kits that hold
 * their own tree. The member travels as its JSON text, as {@link ConfigFlag#ofJsonText} takes it, so no Jackson
 * type reaches a published signature; it is decoded with the model codec that parsed it, which keeps a number's
 * literal, so the text round trip changes no value.
 */
public final class A12Ints {

    private A12Ints() {}

    /**
     * A group's {@code repeatability} as A12 binds it: {@code "\u0663"} is 3. An absent member, and a value A12 cannot
     * bind and so builds no model from, read as the default 1. The member is a primitive {@code int}, so on kernel 31
     * the text {@code null}, the empty text and a JSON null are such values: Jackson 3 refuses a null for a primitive
     * (KERNEL-FINDINGS KF593, corrected 2026-11-02).
     *
     * @param jsonText the member rendered as JSON ({@code JsonNode.toString()}), or {@code null} when it is absent
     */
    public static int repetitionCount(String jsonText) {
        return repetitionCount(member(jsonText));
    }

    /**
     * The value A12 binds, or none when the member binds no value or cannot be bound.
     *
     * @param jsonText as for {@link #repetitionCount(String)}
     */
    public static Optional<Integer> value(String jsonText) {
        return A12IntBinding.of(member(jsonText)).bound();
    }

    static int repetitionCount(JsonNode member) {
        return A12IntBinding.of(member).bound().orElse(1);
    }

    private static JsonNode member(String jsonText) {
        if (jsonText == null) {
            return MissingNode.getInstance();
        }
        try {
            return ModelReader.MAPPER.readTree(jsonText);
        } catch (JsonProcessingException notJson) {
            throw new IllegalArgumentException("not a member's JSON rendering: " + jsonText, notJson);
        }
    }
}
