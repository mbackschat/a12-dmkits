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
 * <p><b>Nor is it the rule A12 uses for {@code excludeRules}/{@code excludeComputations}</b>, which reach a
 * custom deserializer's {@code JsonNode.booleanValue()} and are false for every non-Boolean (KERNEL-FINDINGS
 * KF3). A12 has two boolean-reading paths and which one a member takes is per-member, so this is applied only
 * where the POJO path is measured.
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
     *     the member is absent. An absent member and an explicit {@code null} both read as {@link #OFF}.
     */
    public static ConfigFlag ofJsonText(String jsonText) {
        if (jsonText == null || "null".equals(jsonText)) {
            return OFF;
        }
        A12BooleanBinding binding;
        if (jsonText.startsWith("\"")) {
            binding = BooleanBindingPredicatesKt.a12BooleanOfString(decodedString(jsonText));
        } else if ("true".equals(jsonText)) {
            binding = A12BooleanBinding.TRUE;
        } else if ("false".equals(jsonText)) {
            binding = A12BooleanBinding.FALSE;
        } else if (jsonText.startsWith("{") || jsonText.startsWith("[")) {
            binding = A12BooleanBinding.UNREADABLE;
        } else {
            // The literal, not a conversion: an integer wider than any machine type still answers.
            binding = BooleanBindingPredicatesKt.a12BooleanOfNumber(jsonText);
        }
        return switch (binding) {
            case TRUE -> ON;
            case FALSE -> OFF;
            case UNREADABLE -> UNREADABLE;
        };
    }

    /** {@link #ofJsonText} for a member as this package holds it; a missing node is the absent member. */
    static ConfigFlag ofMember(JsonNode member) {
        return ofJsonText(member.isMissingNode() ? null : member.toString());
    }

    private static String decodedString(String jsonText) {
        try {
            return ModelReader.MAPPER.readTree(jsonText).textValue();
        } catch (JsonProcessingException unparsable) {
            throw new IllegalArgumentException("not a JSON string literal: " + jsonText, unparsable);
        }
    }
}
