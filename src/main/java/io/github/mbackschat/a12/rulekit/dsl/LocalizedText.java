package io.github.mbackschat.a12.rulekit.dsl;

/**
 * One localized error-message text, keyed by the DM-JSON locale code (e.g. {@code en_US}). A model
 * may declare <em>any</em> locales — build via {@link Dsl#text(String, String)}, with
 * {@link Dsl#en(String)} / {@link Dsl#de(String)} as sugar for the common codes. The typed
 * interpolation helper ({@code $Field.value$}) is the F2a follow-up — texts are plain for now.
 */
public record LocalizedText(String locale, String text) {

    /**
     * The locale law (kernel-verified): messages must cover <em>every</em> declared locale
     * ({@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING}); rulekit additionally rejects messages for
     * <em>undeclared</em> locales — the kernel tolerates those silently, which usually hides a typo
     * or dead text.
     */
    static void checkDeclared(java.util.List<String> declaredLocales,
            java.util.List<LocalizedText> messages, String what) {
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        messages.forEach(m -> used.add(m.locale()));
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
