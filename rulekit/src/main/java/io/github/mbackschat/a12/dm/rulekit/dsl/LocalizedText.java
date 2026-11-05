package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.A12Locales;

/**
 * One localized error-message text, keyed by the DM-JSON locale code (e.g. {@code en_US}). A model
 * may declare <em>any</em> locales — build via {@link Dsl#text(String, String)}, with
 * {@link Dsl#en(String)} / {@link Dsl#de(String)} as sugar for the common codes. Interpolate a field's
 * runtime value with {@link Dsl#fieldValue(FieldRef)} (F2a) — e.g. {@code en("Fee " + fieldValue(fee) +
 * " exceeds the cap.")} renders the kernel's {@code $<path>.value$} placeholder.
 */
public record LocalizedText(String locale, String text) {

    /**
     * The locale is held as A12 holds it, its {@link A12Locales#key}: A12 reads {@code en-US} as the locale
     * {@code en_US} names and writes it back so (KERNEL-FINDINGS KF676, KF290), so the two spellings are one message
     * locale here too, for coverage, duplicates and the text the kits write.
     */
    public LocalizedText {
        locale = locale == null ? null : A12Locales.key(locale);
    }

    /**
     * The locale law (kernel-verified): messages must cover <em>every</em> declared locale
     * ({@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING}); rulekit additionally rejects messages for
     * <em>undeclared</em> locales — the kernel tolerates those silently, which usually hides a typo
     * or dead text — and <em>duplicate</em> locales, which the DM-JSON load tier also tolerates
     * silently (one text wins; {@code MVK_LANGUAGE_DUPLICATE} fires only in the model-as-document
     * tier — {@code validate.laws.RuleSlotLawsTest}).
     */
    static void checkDeclared(List<String> declaredLocales,
            List<LocalizedText> messages, String what) {
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (LocalizedText message : messages) {
            if (!used.add(message.locale())) {
                throw new IllegalStateException("the " + what + " has more than one message for locale '"
                        + message.locale() + "' (the kernel's model-as-document tier rejects duplicates with"
                        + " MVK_LANGUAGE_DUPLICATE; the DM-JSON tier silently keeps one text)"
                        + " — keep exactly one text per locale");
            }
        }
        List<String> declaredKeys = declaredLocales.stream().map(A12Locales::key).toList();
        for (String declared : declaredLocales) {
            if (!used.contains(A12Locales.key(declared))) {
                throw new IllegalStateException("the " + what + " is missing an error message for"
                        + " declared locale '" + declared + "' (the kernel rejects this with"
                        + " MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING) — add message(text(\"" + declared + "\", …))");
            }
        }
        for (String locale : used) {
            if (!declaredKeys.contains(locale)) {
                throw new IllegalStateException("the " + what + " has an error message for locale '"
                        + locale + "', which the model does not declare (declared: " + declaredLocales
                        + ") — the kernel would silently ignore it; declare the locale or drop the text");
            }
        }
    }
}
