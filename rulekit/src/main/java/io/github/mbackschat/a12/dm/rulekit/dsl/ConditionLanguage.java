package io.github.mbackschat.a12.dm.rulekit.dsl;

/** The condition language to render into. EN is the default; the same AST renders to either (§5a). */
public enum ConditionLanguage {
    EN,
    DE;

    /**
     * The language a model's {@code content.modelConfig.conditionLanguage.code} selects: {@code de_DE} is German,
     * and every other code, an absent one included, reads as English.
     */
    public static ConditionLanguage ofModelCode(String code) {
        return "de_DE".equals(code) ? DE : EN;
    }
}
