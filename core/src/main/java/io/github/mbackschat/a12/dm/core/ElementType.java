package io.github.mbackschat.a12.dm.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The DM-JSON element-kind — the closed value set of an element node's {@code "type"} discriminator (M9).
 * An element in a group's {@code elements} array is one of these, and its payload lives under a sub-object
 * keyed by the same wire name ({@code {"type":"Group", …, "Group":{"elements":[…]}}}). Replaces the loose
 * {@code "Group".equals(node.path("type").asText())} membership tests and string {@code if/else} chains with
 * a typed enum + exhaustive {@code switch} (the project's enum-based-dispatch preference); the structural
 * keys themselves are {@link DmKeys}.
 */
public enum ElementType {
    GROUP("Group"),
    FIELD("Field"),
    RULE("Rule"),
    COMPUTATION("Computation");

    private final String wireName;

    ElementType(String wireName) {
        this.wireName = wireName;
    }

    /** The DM-JSON discriminator value — also the key of this element's payload sub-object. */
    public String wireName() {
        return wireName;
    }

    /** The kind named by {@code type}, or {@code null} if it is absent/unknown. */
    public static ElementType fromWire(String type) {
        for (ElementType t : values()) {
            if (t.wireName.equals(type)) {
                return t;
            }
        }
        return null;
    }

    /** The kind of the DM-JSON element {@code node} (its {@code "type"}), or {@code null} if absent/unknown.
     *  Core-internal (Set W): a Jackson-typed signature may not cross a published kit boundary; sibling kits
     *  compose the same test from {@link #wireName()} + {@link DmKeys#TYPE}. */
    static ElementType of(JsonNode node) {
        return fromWire(node.path(DmKeys.TYPE).asText(null));
    }

    /** True iff {@code node}'s {@code "type"} is this kind. Core-internal (Set W), like {@link #of(JsonNode)}. */
    boolean matches(JsonNode node) {
        return wireName.equals(node.path(DmKeys.TYPE).asText());
    }
}
