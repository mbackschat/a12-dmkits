package io.github.mbackschat.a12.dm.modelkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configures a string field. Cross-kind nonsense (a numeric range) is simply not on this surface. */
public final class StringFieldBuilder extends FieldAttrs<StringFieldBuilder> {
    private Integer minLength;
    private Integer maxLength;
    private String pattern;
    private String patternMessage;
    private boolean lineBreaksPermitted;
    private boolean alphabeticalSorting;
    private final Map<String, List<String>> suggestions = new LinkedHashMap<>();

    public StringFieldBuilder minLength(int n) {
        this.minLength = n;
        return this;
    }

    public StringFieldBuilder maxLength(int n) {
        this.maxLength = n;
        return this;
    }

    /** Constrain to a pattern; the kernel requires an error {@code message} alongside it (§7). */
    public StringFieldBuilder pattern(String regex, String message) {
        this.pattern = regex;
        this.patternMessage = message;
        return this;
    }

    /** Permit multi-line input (the kernel forbids line breaks by default). */
    public StringFieldBuilder lineBreaks() {
        this.lineBreaksPermitted = true;
        return this;
    }

    /** Present the field's values sorted alphabetically (a UI hint the kernel records). */
    public StringFieldBuilder alphabeticalSorting() {
        this.alphabeticalSorting = true;
        return this;
    }

    /** Add localized input suggestions (a hint list) for one locale; validated against the field's own pattern. */
    public StringFieldBuilder suggestions(String locale, String... values) {
        this.suggestions.put(locale, List.of(values));
        return this;
    }

    protected FieldTypeSpec spec() {
        return new FieldTypeSpec.StringSpec(minLength, maxLength, pattern, patternMessage,
                lineBreaksPermitted, alphabeticalSorting, suggestions);
    }
}
