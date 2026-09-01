package io.github.mbackschat.a12.dm.edit;

/**
 * L2c — a single chaining handle over the model's document configuration, gathering the otherwise-
 * scattered {@code setX} verbs under one discoverable surface ({@code s.config().decimalSeparator(",").…}).
 * Each setter forwards to the matching {@link EditSession} config verb and returns this editor. Obtain
 * from {@link EditSession#config()}.
 */
public final class ConfigEditor {

    private final EditSession session;

    ConfigEditor(EditSession session) {
        this.session = session;
    }

    /** Set the decimal separator (e.g. {@code "."} or {@code ","}). Chains. */
    public ConfigEditor decimalSeparator(String separator) {
        session.setDecimalSeparator(separator);
        return this;
    }

    /**
     * Set the model time zone. UTC/GMT/Europe/Berlin are interpreter-supported; another known Java id is
     * kernel-valid but evaluator-unsupported, while null/blank/unknown ids are invalid. Absence defaults UTC.
     * Chains.
     */
    public ConfigEditor timeZone(String timeZone) {
        session.setTimeZone(timeZone);
        return this;
    }

    /** Set the condition language code (e.g. {@code "en_US"}, {@code "de_DE"}). Chains. */
    public ConfigEditor conditionLanguage(String localeCode) {
        session.setConditionLanguage(localeCode);
        return this;
    }

    /** Allow (or forbid) short-name field references where unique. Chains. */
    public ConfigEditor fieldRefByShortNameAllowed(boolean allowed) {
        session.setFieldRefByShortNameAllowed(allowed);
        return this;
    }

    /**
     * Set the allowed-character set the kernel validates field values against: each entry is a singleton
     * BMP character, an inclusive range such as {@code " -~"}, or one atomic combined grapheme. Pass
     * nothing to select the full-BMP default (surrogates/supplementary characters remain illegal). Chains.
     *
     * @see io.github.mbackschat.a12.dm.modelkit.SupportedCharactersContract
     */
    public ConfigEditor supportedCharacters(String... chars) {
        session.setSupportedCharacters(java.util.List.of(chars));
        return this;
    }

    /** Set the model base year (resolves year-less dates / the BaseYear point-in-time). Chains. */
    public ConfigEditor baseYear(int year) {
        session.setBaseYear(year);
        return this;
    }

    /** Remove the model base year — the inverse of {@link #baseYear(int)}. */
    public ConfigEditor clearBaseYear() {
        session.clearBaseYear();
        return this;
    }

    /** Upsert one per-locale model header label (the model's localized display name). Chains. */
    public ConfigEditor label(String locale, String text) {
        session.setModelLabel(locale, text);
        return this;
    }

    /** Upsert a model-level header annotation. Chains. */
    public ConfigEditor annotation(String name, String value) {
        session.setModelAnnotation(name, value);
        return this;
    }

    /** Set the model-level {@code comment} (a free-text model note / description). Chains. */
    public ConfigEditor comment(String text) {
        session.setModelComment(text);
        return this;
    }
}
