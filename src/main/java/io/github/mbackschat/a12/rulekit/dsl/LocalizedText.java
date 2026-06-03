package io.github.mbackschat.a12.rulekit.dsl;

/**
 * One localized error-message text, keyed by the DM-JSON locale code (e.g. {@code en_US}).
 * Build via {@link Dsl#en(String)} / {@link Dsl#de(String)}. The typed interpolation helper
 * ({@code $Field.value$}) is the F2a follow-up — texts are plain for now.
 */
public record LocalizedText(String locale, String text) {
}
