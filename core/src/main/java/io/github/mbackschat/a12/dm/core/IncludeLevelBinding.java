package io.github.mbackschat.a12.dm.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * How A12 binds a mount's {@code includeConfig.includeLevel} to its {@code IncludeLevel} enum (KERNEL-FINDINGS KF651,
 * {@code adapter.laws.IncludeConfigShapeLawsTest}): a constant's name or its ordinal ({@code SINGLE_RG} is 0), as an
 * integer token or as the ordinal's digits, all after a Java {@code trim()}. Case, sign, a leading zero, a fraction,
 * a boolean, {@code null} and the empty text refuse the model.
 *
 * <p>Like {@link ConfigFlag}, the signature takes the value's JSON text, because dmkits-core publishes no third-party
 * type on a public signature.
 */
public enum IncludeLevelBinding {

    SINGLE_RG,
    MODEL_ROOT,

    /** A12's reader refuses the value, so it builds no model. */
    UNREADABLE;

    /**
     * Bind one {@code includeLevel} value.
     *
     * @param jsonText the member rendered as JSON ({@code JsonNode.toString()}), or {@code null} when it is absent,
     *     which is A12's default {@link #SINGLE_RG}
     * @param floatingPointToken whether the parser read the member as a floating-point number, which never binds
     */
    public static IncludeLevelBinding ofParsedMember(String jsonText, boolean floatingPointToken) {
        if (jsonText == null) {
            return SINGLE_RG;
        }
        if (floatingPointToken) {
            return UNREADABLE;
        }
        String bound = jsonText.startsWith("\"") ? decoded(jsonText).trim() : integerText(jsonText);
        return switch (bound == null ? "" : bound) {
            case "SINGLE_RG", "0" -> SINGLE_RG;
            case "MODEL_ROOT", "1" -> MODEL_ROOT;
            default -> UNREADABLE;
        };
    }

    /** {@link #ofParsedMember} for a member as this package holds it; a missing node is the absent member. */
    static IncludeLevelBinding ofMember(JsonNode member) {
        return ofParsedMember(member.isMissingNode() ? null : member.toString(), member.isFloatingPointNumber());
    }

    /** An integer token's value as decimal digits, {@code -0} being 0; null for any other literal. */
    private static String integerText(String literal) {
        String digits = literal.startsWith("-") ? literal.substring(1) : literal;
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        String magnitude = digits.replaceFirst("^0+(?=.)", "");
        return literal.startsWith("-") && !"0".equals(magnitude) ? null : magnitude;
    }

    private static String decoded(String jsonText) {
        try {
            return ModelReader.MAPPER.readTree(jsonText).textValue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException unparsable) {
            throw new IllegalArgumentException("not a JSON string literal: " + jsonText, unparsable);
        }
    }
}
