package io.github.mbackschat.a12.dm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.predicates.A12BooleanBinding;
import io.github.mbackschat.a12.dm.predicates.BooleanBindingPredicatesKt;

/**
 * How A12 reads a JSON value bound to a primitive {@code boolean} member of a model POJO — of
 * {@code content.modelConfig}, of a {@code Field}, or of a field type (KERNEL-FINDINGS KF569).
 *
 * <p><b>Three outcomes, because folding {@link #UNREADABLE} into {@link #OFF} analyses a model that never
 * loads.</b> Jackson refuses the whole {@code DocumentModelConfig} binding for a value it cannot take, so A12
 * never judges such a model at all — there is no verdict for a reader to mirror.
 *
 * <p><b>This is NOT {@code JsonNode.asBoolean}, and the gap runs in the dangerous direction.</b>
 * {@code asBoolean} takes only the lowercase {@code "true"}, so it reads {@code "True"} as off where A12
 * reads it as on — and for {@code fieldRefByShortNameAllowed} that silently disables the model-wide
 * short-name tier, so a bare reference A12 resolves reads as undeclared. It also truncates a fractional
 * number, answering (affirmatively, for {@code 1.5}) about a model A12 will not load.
 *
 * <p>On DM 29.4 the same law also binds a mount's {@code includeConfig.excludeRules}/{@code excludeComputations},
 * which A12 reads through the same POJO binding (KERNEL-FINDINGS KF651); the custom reader that made them false for
 * every non-Boolean (KF3) left with DM 28.
 *
 * <p>The signature takes the value's JSON TEXT rather than a {@code JsonNode} because dmkits-core publishes no
 * third-party type on a public signature (the {@code PublicApiGuardTest} hard rule). Reading the literal is
 * also what the rule needs: an integer wider than any machine type still answers, where a conversion would
 * overflow into the unreadable branch for a model A12 loads. A string is DECODED before it is judged, so an
 * escaped {@code "\ttrue"} is the tab-led text A12 trims, not the six characters of its encoding.
 */
public enum ConfigFlag {

    ON,
    OFF,

    /** A12's own reader refuses the value, so the model does not deserialize and there is no verdict. */
    UNREADABLE;

    /**
     * Read one configuration value the way A12's own POJO binding reads it — the shared law
     * {@code a12BooleanOfString} / {@code a12BooleanOfNumber} in {@code :predicates}, measured on this member by
     * {@code rulekit.validate.laws.ShortNameFlagSpellingLawsTest}, which asks the Kernel rather than any accessor
     * of ours.
     *
     * @param jsonText the member's value rendered as JSON ({@code JsonNode.toString()}), or {@code null} when
     *     the member is absent. An absent member reads as {@link #OFF}; an explicit {@code null} is
     *     {@link #UNREADABLE} on kernel 31.1.1, whose Jackson 3 refuses null for a primitive (30.8.1 read it as off).
     *     The text is trusted to BE such a rendering, never parsed as a whole: a malformed string literal
     *     throws {@link IllegalArgumentException}, and any other text that is not JSON reaches the number
     *     branch and reads as {@link #UNREADABLE}.
     */
    public static ConfigFlag ofJsonText(String jsonText) {
        if (jsonText == null) {
            return OFF;
        }
        if ("null".equals(jsonText)) {
            return UNREADABLE;
        }
        A12BooleanBinding binding;
        if (jsonText.startsWith("\"")) {
            binding = BooleanBindingPredicatesKt.a12BooleanOfString(decodedString(jsonText));
        } else if ("true".equals(jsonText)) {
            binding = A12BooleanBinding.TRUE;
        } else if ("false".equals(jsonText)) {
            binding = A12BooleanBinding.FALSE;
        } else {
            // The literal, not a conversion: an integer wider than any machine type still answers, and an object or
            // array fails the number's digit test as the reader refuses it.
            binding = BooleanBindingPredicatesKt.a12BooleanOfNumber(jsonText);
        }
        return switch (binding) {
            case TRUE -> ON;
            case FALSE -> OFF;
            case UNREADABLE -> UNREADABLE;
        };
    }

    /**
     * {@link #ofJsonText} for a member a JSON reader has parsed, which may have respelled its number: a decimal node
     * prints {@code 1e0} as {@code 1}. A12 binds only an integer token, so a floating-point one, a token with an
     * exponent or a fraction, is {@link #UNREADABLE} whatever it prints (KERNEL-FINDINGS KF569, corrected 2026-10-22).
     *
     * @param jsonText as for {@link #ofJsonText}
     * @param floatingPointToken whether the parser read the member as a floating-point number
     *     ({@code JsonNode.isFloatingPointNumber()})
     */
    public static ConfigFlag ofParsedMember(String jsonText, boolean floatingPointToken) {
        return floatingPointToken ? UNREADABLE : ofJsonText(jsonText);
    }

    /** {@link #ofParsedMember} for a member as this package holds it; a missing node is the absent member. */
    static ConfigFlag ofMember(JsonNode member) {
        return ofParsedMember(member.isMissingNode() ? null : member.toString(), member.isFloatingPointNumber());
    }

    /** Whether the member as this package holds it sets the flag. */
    static boolean isSet(JsonNode member) {
        return ofMember(member) == ON;
    }

    private static String decodedString(String jsonText) {
        try {
            return ModelReader.MAPPER.readTree(jsonText).textValue();
        } catch (JsonProcessingException unparsable) {
            throw new IllegalArgumentException("not a JSON string literal: " + jsonText, unparsable);
        }
    }
}
