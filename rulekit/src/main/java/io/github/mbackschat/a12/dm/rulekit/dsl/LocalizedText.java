package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * One localized error-message text, keyed by the DM-JSON locale code (e.g. {@code en_US}). A model
 * may declare <em>any</em> locales — build via {@link Dsl#text(String, String)}, with
 * {@link Dsl#en(String)} / {@link Dsl#de(String)} as sugar for the common codes. Interpolate a field's
 * runtime value with {@link Dsl#fieldValue(FieldRef)} (F2a) — e.g. {@code en("Fee " + fieldValue(fee) +
 * " exceeds the cap.")} renders the kernel's {@code $<path>.value$} placeholder.
 */
public record LocalizedText(String locale, String text) {

    /**
     * The locale law (kernel-verified): messages must cover <em>every</em> declared locale
     * ({@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING}); rulekit additionally rejects messages for
     * <em>undeclared</em> locales — the kernel tolerates those silently, which usually hides a typo
     * or dead text — and <em>duplicate</em> locales, which the DM-JSON load tier also tolerates
     * silently (one text wins; {@code MVK_LANGUAGE_DUPLICATE} fires only in the model-as-document
     * tier — {@code validate.laws.RuleSlotLawsTest}).
     */
    static void checkDeclared(java.util.List<String> declaredLocales,
            java.util.List<LocalizedText> messages, String what) {
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (LocalizedText message : messages) {
            if (!used.add(message.locale())) {
                throw new IllegalStateException("the " + what + " has more than one message for locale '"
                        + message.locale() + "' (the kernel's model-as-document tier rejects duplicates with"
                        + " MVK_LANGUAGE_DUPLICATE; the DM-JSON tier silently keeps one text)"
                        + " — keep exactly one text per locale");
            }
        }
        for (String declared : declaredLocales) {
            if (!used.contains(declared)) {
                throw new IllegalStateException("the " + what + " is missing an error message for"
                        + " declared locale '" + declared + "' (the kernel rejects this with"
                        + " MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING) — add message(text(\"" + declared + "\", …))");
            }
        }
        for (String locale : used) {
            if (!declaredLocales.contains(locale)) {
                throw new IllegalStateException("the " + what + " has an error message for locale '"
                        + locale + "', which the model does not declare (declared: " + declaredLocales
                        + ") — the kernel would silently ignore it; declare the locale or drop the text");
            }
        }
    }
}
