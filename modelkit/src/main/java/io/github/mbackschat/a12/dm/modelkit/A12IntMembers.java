package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.A12Ints;
import java.util.Optional;

/**
 * A model POJO's {@code int} member, read as A12's reader binds it ({@link A12Ints}) rather than by
 * {@code JsonNode.asInt}, which takes ASCII digits only and reads the text {@code null} as no number: a stored
 * {@code "\u0663"} was a non-repeatable group here and a three-row group to A12 and every other reader of ours.
 */
final class A12IntMembers {

    private A12IntMembers() {}

    static int repetitionCount(JsonNode member) {
        return A12Ints.repetitionCount(member.isMissingNode() ? null : member.toString());
    }

    static Optional<Integer> value(JsonNode member) {
        return A12Ints.value(member.isMissingNode() ? null : member.toString());
    }
}
