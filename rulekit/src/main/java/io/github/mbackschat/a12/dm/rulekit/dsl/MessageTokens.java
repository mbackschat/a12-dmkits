package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The interpolation tokens of a rule message, written in one condition language.
 *
 * <p>A12 parses a message token with a grammar of its own, and five of its terminals follow the model's
 * condition language: {@code value}/{@code Wert} and {@code For}/{@code Fuer} among them. A token spelled in the
 * other language is refused, {@code .value} as {@code INVALID_ENTITY} and {@code For} as
 * {@code MVK_UNEXPECTED_TOKEN} (KERNEL-FINDINGS KF198), so a German model needs its tokens from
 * {@code MessageTokens.of(ConditionLanguage.DE)}, or from {@code GroupEditor.messageTokens()}, which reads the
 * model's language. The {@code Dsl} token helpers are {@link #ENGLISH}. A path segment or a category named like
 * a keyword is quoted, as its condition spelling is; the quote is transparent to A12 (KF198).
 *
 * <p>A12 splits a message at every {@code $} before it parses a token ({@code FehlertextUtil}), so a literal key
 * holding one cannot be written as a token, and the keyed tokens refuse it (DEV-API-GAPS DG131;
 * {@code dsl.MessageTokensTest}).
 */
public final class MessageTokens {

    /** The English tokens, which the {@code Dsl} helpers return. */
    public static final MessageTokens ENGLISH = new MessageTokens(ConditionLanguage.EN);

    private static final MessageTokens GERMAN = new MessageTokens(ConditionLanguage.DE);

    private final DslRenderer renderer;

    private MessageTokens(ConditionLanguage language) {
        this.renderer = new DslRenderer(language);
    }

    /** The tokens of {@code language}. */
    public static MessageTokens of(ConditionLanguage language) {
        return switch (language) {
            case EN -> ENGLISH;
            case DE -> GERMAN;
        };
    }

    /** {@code $<path>.value$}, the field's runtime value. */
    public String fieldValue(FieldRef field) {
        return "$" + path(field) + "." + valueTerminal() + "$";
    }

    /** {@code $<path>$}, the field's label. */
    public String fieldLabel(FieldRef field) {
        return "$" + path(field) + "$";
    }

    /** {@code $<path>.value For key$}, the value of a key-selected field; the suffix precedes the key. */
    public String fieldValue(SemanticIndex selected) {
        return keyed(selected.index(), "." + valueTerminal());
    }

    /** {@code $<path> For key$}, the label of a key-selected field. */
    public String fieldLabel(SemanticIndex selected) {
        return keyed(selected.index(), "");
    }

    /**
     * {@code $<path>->Category$}, an enumeration field's category value for the row the message reads, or for the
     * row its key selects when the category carries one ({@link CategoryRef#forIndex(String)}).
     */
    public String fieldCategory(CategoryRef category) {
        return category.key().map(key -> keyed(switch (key) {
            case FirstFilledOperand.LiteralIndex literal -> category.field().semanticIndex(literal.value()).index();
            case FirstFilledOperand.FieldIndex fieldKey -> category.field().semanticIndex(fieldKey.field()).index();
        }, categorySuffix(category))).orElseGet(() -> "$" + path(category.field()) + categorySuffix(category) + "$");
    }

    /** {@code $<path>->Category For "key"$}, the category value of the row a literal key selects. */
    public String fieldCategory(CategoryRef category, String indexValue) {
        return fieldCategory(category.forIndex(indexValue));
    }

    /** {@code $<path>->Category For keyField$}, the field-keyed peer. */
    public String fieldCategory(CategoryRef category, FieldRef keyField) {
        return fieldCategory(category.forIndex(keyField));
    }

    /** {@code $#<groupPath>$}, the 1-based row number of the group's current repetition. */
    public String repetitionIndex(GroupRef group) {
        return "$#" + renderer.messagePath(group.path().toString()) + "$";
    }

    /** {@code $#<groupPath> For key$}, a keyed group's row number, its key spelled as in the condition. */
    public String repetitionIndex(IndexedGroupValue group) {
        String rendered = renderer.render(group);
        requireNoDollar(rendered);
        return "$#" + rendered + "$";
    }

    /** {@code $index('Field').value$}, the firing context's index value by the field's short name (KF280). */
    public String indexValue(FieldRef indexField) {
        return "$index('" + indexField.path().segments().getLast() + "')." + valueTerminal() + "$";
    }

    /** {@code $index('Field')->'Category'$}, the firing context's enum index category (KF280). */
    public String indexCategory(CategoryRef category) {
        return "$index('" + category.field().path().segments().getLast() + "')->'" + category.name() + "'$";
    }

    private String keyed(IndexedFieldValue index, String suffix) {
        String key = renderer.keyClause(index);
        requireNoDollar(key);
        return "$" + path(index.field()) + suffix + " " + key + "$";
    }

    private String path(FieldRef field) {
        return renderer.messagePath(field.path().toString());
    }

    private String categorySuffix(CategoryRef category) {
        return "->" + renderer.messageName(category.name());
    }

    private String valueTerminal() {
        return renderer.language() == ConditionLanguage.DE ? "Wert" : "value";
    }

    private static void requireNoDollar(String key) {
        if (key.indexOf('$') >= 0) {
            throw new IllegalArgumentException("a message token cannot carry a key holding '$', because A12 splits"
                    + " the message at every '$' before it reads a token: " + key);
        }
    }
}
