package io.github.mbackschat.a12.dm.core;

import java.util.Set;

/**
 * How A12 reads a JSON value bound to a {@code boolean} member of {@code content.modelConfig}.
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
 * overflow into the unreadable branch for a model A12 loads.
 */
public enum ConfigFlag {

    ON,
    OFF,

    /** A12's own reader refuses the value, so the model does not deserialize and there is no verdict. */
    UNREADABLE;

    /** The spellings Jackson's {@code boolean} binding takes as true, and the ones it takes as false. */
    private static final Set<String> TRUE_SPELLINGS = Set.of("true", "True", "TRUE");
    private static final Set<String> FALSE_SPELLINGS = Set.of("false", "False", "FALSE");

    /**
     * Read one configuration value the way A12's own POJO binding reads it — measured over twenty-two
     * spellings by {@code rulekit.validate.laws.ShortNameFlagSpellingLawsTest}, which asks the Kernel rather
     * than any accessor of ours.
     *
     * @param jsonText the member's value rendered as JSON ({@code JsonNode.toString()}), or {@code null} when
     *     the member is absent. An absent member and an explicit {@code null} both read as {@link #OFF}.
     */
    public static ConfigFlag ofJsonText(String jsonText) {
        if (jsonText == null || jsonText.isEmpty() || "null".equals(jsonText)) {
            return OFF;
        }
        if (jsonText.charAt(0) == '"') {
            return ofString(jsonText.substring(1, jsonText.length() - 1));
        }
        if (TRUE_SPELLINGS.contains(jsonText)) {
            return ON;
        }
        if (FALSE_SPELLINGS.contains(jsonText)) {
            return OFF;
        }
        // A numeric literal is read as an int: non-zero is true. An object, an array and anything else the
        // binding cannot take fall through to the refusal.
        if (!isIntegerLiteral(jsonText)) {
            return UNREADABLE;
        }
        return isZeroLiteral(jsonText) ? OFF : ON;
    }

    private static ConfigFlag ofString(String value) {
        if (TRUE_SPELLINGS.contains(value)) {
            return ON;
        }
        // Jackson's empty-string coercion supplies the type's default BEFORE the recognized-values check, so
        // the empty string is not an unrecognized spelling — it is `false`.
        if (FALSE_SPELLINGS.contains(value) || value.isEmpty()) {
            return OFF;
        }
        return UNREADABLE;
    }

    private static boolean isIntegerLiteral(String text) {
        String digits = unsigned(text);
        return !digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean isZeroLiteral(String text) {
        return unsigned(text).chars().allMatch(c -> c == '0');
    }

    private static String unsigned(String text) {
        return text.startsWith("-") || text.startsWith("+") ? text.substring(1) : text;
    }
}
